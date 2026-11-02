package io.pickwick.app.data

/**
 * Master -> paired devices index push. Shared by the in-app poll and
 * [IndexCrawlWorker]: the poll alone only ran while the master's app sat
 * on screen for 5+ minutes, and a parent's phone rarely does, so kid devices
 * kept just the 30-video first pages they harvested themselves while the
 * master's deep crawl never left the phone. The worker runs every 15 minutes
 * regardless, right after the crawl that produced the new pages.
 */
object IndexSync {

    /**
     * Push sources whose content hash (or completeness) differs from what each
     * paired device reports. Compared per source, so a one-channel delta ships
     * one small file, not the family's whole index. Blocking network I/O —
     * call off-main. Returns how many source files were pushed.
     */
    suspend fun pushToPeers(index: ChannelIndex, pairingStore: PairingStore): Int {
        var pushed = 0
        var failed = 0
        pairingStore.paired().forEach { device ->
            val remoteStatus = LanClient.indexStatus(device) ?: run {
                android.util.Log.i("Pickwick", "index sync: ${device.name} unreachable")
                return@forEach
            }
            val remote = runCatching { org.json.JSONObject(remoteStatus) }
                .getOrNull() ?: return@forEach
            // Two straight failures = the device went away (or is choking);
            // don't sit through a 5 s connect timeout for every remaining
            // source. Whatever's left ships on the next run.
            var consecutiveFailures = 0
            index.allStates().forEach { (sourceId, state) ->
                if (consecutiveFailures >= 2) return@forEach
                // contentHash is count:newest only, so a completeness
                // flip with no new videos (exhaustion accepted after
                // repeated probes, or a harvest un-completing) would
                // never ship on the hash alone — compare the flag too.
                // Deliberately not folded into the hash: the formula is
                // wire format shared with old builds. A source the
                // remote lacks entirely yields null for both reads and
                // still pushes, as before.
                val remoteEntry = remote.optJSONObject(sourceId)
                if (remoteEntry?.optInt("hash") != state.contentHash() ||
                    remoteEntry?.optBoolean("complete") != state.complete
                ) {
                    val body = index.exportSourceWithState(sourceId) ?: return@forEach
                    if (LanClient.pushIndexSource(device, sourceId, body)) {
                        pushed++
                        consecutiveFailures = 0
                    } else {
                        failed++
                        consecutiveFailures++
                    }
                }
            }
        }
        if (pushed > 0 || failed > 0) {
            android.util.Log.i("Pickwick", "index sync: pushed $pushed sources, $failed failed")
        }
        return pushed
    }
}
