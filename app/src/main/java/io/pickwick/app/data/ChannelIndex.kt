package io.pickwick.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent, per-source search index: every video of every whitelisted
 * channel/playlist, trickle-crawled once and then kept current with page-1
 * deltas. Built only on the family's master device (see
 * [Whitelist.masterDeviceToken]); every other device receives it over the LAN
 * (`/index` endpoints in LanServer) so YouTube's rate limit is paid once.
 *
 * Storage: one JSON file per source under filesDir/search-index/, plus a
 * manifest of per-source state. Per-source files so a removed whitelist entry
 * is one delete, and a pushed delta replaces one file, not a monolith.
 */
class ChannelIndex(context: Context) {

    // No disk work here: construction happens on the main thread (activity
    // setup, remember{} in settings). mkdirs runs lazily on the write paths,
    // and the manifest loads on first state access — callers of anything
    // file-backed are already on Dispatchers.IO.
    private val dir = File(context.filesDir, "search-index")
    private val manifestFile = File(dir, "manifest.json")

    /** Per-source crawl state, persisted so a crawl survives process death. */
    data class SourceState(
        /** Videos indexed so far. */
        val count: Int,
        /** Newest videoId seen (delta anchor). */
        val newestVideoId: String?,
        /** True once the crawl reached the channel's oldest video. */
        val complete: Boolean
    ) {
        /** Cheap change fingerprint: count+newest catches both deltas and
         *  rebuilds. Shared by statusJson and the master's push comparison. */
        fun contentHash(): Int = (count.toString() + ":" + (newestVideoId ?: "")).hashCode()
    }

    /** In-memory copy of the manifest; disk is the source of truth. Loaded on
     *  first access (off-main), and seeds the shared flow as a side effect. */
    private var statesBacking: Map<String, SourceState>? = null
    private var states: Map<String, SourceState>
        get() = statesBacking ?: loadManifest().also {
            statesBacking = it
            sharedStates.value = it
        }
        set(value) {
            statesBacking = value
            sharedStates.value = value
        }

    /** One indexed video — the fields search and the results screen need. */
    data class IndexedVideo(
        val videoId: String,
        val title: String,
        val channelName: String,
        val thumbnailUrl: String?,
        val durationSeconds: Long,
        /** The whitelisted source this came from (drives profile visibility). */
        val sourceId: String
    ) {
        fun toVideo(): Video = Video(
            url = "https://www.youtube.com/watch?v=$videoId",
            title = title,
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds
        )
    }

    // ---- query -----------------------------------------------------------

    /**
     * Token match over title + channel name, restricted to [visibleSourceIds]
     * (the active kid's whitelist view + parent rulings applied by the caller).
     * Pure in-memory — fast enough to run per keystroke if we ever want to.
     */
    fun search(query: String, visibleSourceIds: Set<String>): List<IndexedVideo> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val out = mutableListOf<IndexedVideo>()
        for (sourceId in visibleSourceIds) {
            for (v in loadSource(sourceId)) {
                val hay = (v.title + " " + v.channelName).lowercase()
                if (terms.all { it in hay }) out += v
            }
        }
        return out
    }

    // ---- storage ---------------------------------------------------------

    private fun sourceFile(sourceId: String) =
        File(dir, sourceId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun loadSource(sourceId: String): List<IndexedVideo> {
        val f = sourceFile(sourceId)
        if (!f.exists()) return emptyList()
        return parseSource(f.readText(), sourceId)
    }

    private fun saveSource(sourceId: String, videos: List<IndexedVideo>) {
        dir.mkdirs()
        val arr = JSONArray()
        videos.forEach { v ->
            arr.put(JSONObject().apply {
                put("id", v.videoId)
                put("t", v.title)
                put("c", v.channelName)
                v.thumbnailUrl?.let { put("th", it) }
                put("d", v.durationSeconds)
            })
        }
        sourceFile(sourceId).writeText(arr.toString())
    }

    /** Whitelist edit removed a source — its index and crawl cursor go with it. */
    fun dropSource(sourceId: String) {
        sourceFile(sourceId).delete()
        dropCursor(sourceId)
        states = states - sourceId
        saveManifest()
    }

    /**
     * Revive a source that was prematurely marked complete (an old build
     * trusted a full first page with no continuation as "exhausted"). The
     * videos already indexed stay; the crawler just gets to keep going.
     */
    fun unmarkComplete(sourceId: String) {
        val s = states[sourceId] ?: return
        if (!s.complete) return
        states = states + (sourceId to s.copy(complete = false))
        saveManifest()
    }

    /**
     * Merge videos into a source's index, deduped on videoId. [append] = the
     * batch is older history from the back-catalog crawl (goes after what's
     * known); false = fresh page-1/delta videos (go before). Callers always
     * pass their batch newest-first.
     */
    fun addVideos(
        sourceId: String,
        videos: List<IndexedVideo>,
        complete: Boolean? = null,
        append: Boolean = false
    ) {
        if (videos.isEmpty() && complete == null) return
        val existing = loadSource(sourceId)
        val known = existing.mapTo(HashSet()) { it.videoId }
        val fresh = videos.filter { it.videoId !in known }
        if (fresh.isEmpty() && complete == null) return
        val merged = if (append) existing + fresh else fresh + existing
        saveSource(sourceId, merged)
        val prev = states[sourceId]
        states = states + (sourceId to SourceState(
            count = merged.size,
            newestVideoId = merged.firstOrNull()?.videoId ?: prev?.newestVideoId,
            complete = complete ?: prev?.complete ?: false
        ))
        saveManifest()
    }

    fun state(sourceId: String): SourceState? = states[sourceId]
    fun allStates(): Map<String, SourceState> = states

    // ---- manifest + LAN sync ----------------------------------------------

    private fun loadManifest(): Map<String, SourceState> = runCatching {
        if (!manifestFile.exists()) return emptyMap()
        val o = JSONObject(manifestFile.readText())
        o.keys().asSequence().associateWith { id ->
            val s = o.getJSONObject(id)
            SourceState(
                count = s.optInt("count", 0),
                newestVideoId = s.optString("newest").ifEmpty { null },
                complete = s.optBoolean("complete", false)
            )
        }
    }.getOrDefault(emptyMap())

    private fun saveManifest() {
        dir.mkdirs()
        val o = JSONObject()
        states.forEach { (id, s) ->
            o.put(id, JSONObject().apply {
                put("count", s.count)
                s.newestVideoId?.let { put("newest", it) }
                put("complete", s.complete)
            })
        }
        manifestFile.writeText(o.toString())
        sharedStates.value = states
    }

    /**
     * Re-read the manifest from disk and republish the shared flow. The flow
     * is process-local, so a crawl that ran in the WorkManager process (or
     * before this screen opened) only shows up after a refresh — the settings
     * refresh icon calls this.
     */
    fun refresh() {
        states = loadManifest()
        sharedStates.value = states
        lastRun.value = lastRunInfo()
    }

    // ---- run telemetry (diagnostics in the settings status section) --------

    private val runFile = File(dir, "last-run.json")

    /** One crawl worker run's outcome, for the "Previous run" diagnostics line. */
    data class RunInfo(val atMillis: Long, val pages: Int, val failed: Boolean)

    fun recordRun(pages: Int, failed: Boolean) {
        runCatching {
            dir.mkdirs()
            runFile.writeText(
                JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("pages", pages)
                    .put("failed", failed)
                    .toString()
            )
        }
        lastRun.value = RunInfo(System.currentTimeMillis(), pages, failed)
    }

    fun lastRunInfo(): RunInfo? = runCatching {
        if (!runFile.exists()) return null
        val o = JSONObject(runFile.readText())
        RunInfo(o.getLong("at"), o.optInt("pages", 0), o.optBoolean("failed", false))
    }.getOrNull()

    /** Compact per-source fingerprint for /index-status: what a peer needs to
     *  decide whether a push is worthwhile. */
    fun statusJson(): String {
        val o = JSONObject()
        states.forEach { (id, s) ->
            o.put(id, JSONObject().apply {
                put("count", s.count)
                put("complete", s.complete)
                put("hash", s.contentHash())
            })
        }
        return o.toString()
    }

    /** Serialize one source for a LAN push. Null when we have nothing for it. */
    fun exportSource(sourceId: String): String? {
        val f = sourceFile(sourceId)
        return if (f.exists()) f.readText() else null
    }

    /** Apply a pushed source file (from the master). Replaces ours wholesale. */
    fun importSource(sourceId: String, json: String, state: SourceState) {
        saveSource(sourceId, parseSource(json, sourceId))
        states = states + (sourceId to state)
        saveManifest()
    }

    /** Wire format for a LAN push: state line, then the video array. */
    fun exportSourceWithState(sourceId: String): String? {
        val videos = exportSource(sourceId) ?: return null
        val s = states[sourceId] ?: return null
        val head = JSONObject().apply {
            put("count", s.count)
            s.newestVideoId?.let { put("newest", it) }
            put("complete", s.complete)
        }
        return head.toString() + "\n" + videos
    }

    /** Receiver side of [exportSourceWithState]. */
    fun importSourceWithState(sourceId: String, body: String) {
        val nl = body.indexOf('\n')
        if (nl <= 0) return
        val head = runCatching { JSONObject(body.substring(0, nl)) }.getOrNull() ?: return
        importSource(
            sourceId,
            body.substring(nl + 1),
            SourceState(
                count = head.optInt("count", 0),
                newestVideoId = head.optString("newest").ifEmpty { null },
                complete = head.optBoolean("complete", false)
            )
        )
    }

    private fun parseSource(json: String, sourceId: String): List<IndexedVideo> =
        runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                IndexedVideo(
                    videoId = o.getString("id"),
                    title = o.getString("t"),
                    channelName = o.optString("c"),
                    thumbnailUrl = o.optString("th").ifEmpty { null },
                    durationSeconds = o.optLong("d", 0),
                    sourceId = sourceId
                )
            }
        }.getOrDefault(emptyList())

    // ---- crawl cursors ----------------------------------------------------

    private val cursorsFile = File(dir, "cursors.json")

    /**
     * Opaque per-source pagination cursor, persisted so a WorkManager run
     * resumes where the previous one stopped instead of re-walking from page 1
     * (which stalled any channel deeper than one run's page budget forever).
     * The JSON is the crawler's — this just stores it.
     */
    fun loadCursor(sourceId: String): String? = runCatching {
        if (!cursorsFile.exists()) return null
        JSONObject(cursorsFile.readText()).optString(sourceId).ifEmpty { null }
    }.getOrNull()

    fun saveCursor(sourceId: String, json: String) = updateCursors { it.put(sourceId, json) }

    fun dropCursor(sourceId: String) = updateCursors { it.remove(sourceId) }

    private fun updateCursors(mutate: (JSONObject) -> Unit) {
        runCatching {
            val o = if (cursorsFile.exists()) JSONObject(cursorsFile.readText()) else JSONObject()
            mutate(o)
            dir.mkdirs()
            cursorsFile.writeText(o.toString())
        }
    }

    companion object {
        /**
         * Process-wide live view of every instance's states, so the settings
         * screen (its own ChannelIndex instance) watches the ViewModel/worker
         * instances' progress without sharing an object. Updated on every
         * manifest write; seeded lazily by the first instance.
         */
        val sharedStates = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, SourceState>())

        /** Live last-run info, so the settings line updates the moment a run lands. */
        val lastRun = kotlinx.coroutines.flow.MutableStateFlow<RunInfo?>(null)
    }
}
