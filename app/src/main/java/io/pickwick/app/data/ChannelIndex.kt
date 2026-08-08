package io.pickwick.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val dir = File(context.filesDir, "search-index").apply { mkdirs() }
    private val manifestFile = File(dir, "manifest.json")

    /** Per-source crawl state, persisted so a crawl survives process death. */
    data class SourceState(
        /** Videos indexed so far. */
        val count: Int,
        /** Newest videoId seen (delta anchor). */
        val newestVideoId: String?,
        /** True once the crawl reached the channel's oldest video. */
        val complete: Boolean
    )

    /** In-memory copy of the manifest; disk is the source of truth. */
    private var states: Map<String, SourceState> = loadManifest()

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
        return runCatching {
            val arr = JSONArray(f.readText())
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
    }

    private fun saveSource(sourceId: String, videos: List<IndexedVideo>) {
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

    /** Whitelist edit removed a source — its index goes with it. */
    fun dropSource(sourceId: String) {
        sourceFile(sourceId).delete()
        states = states - sourceId
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
        val o = JSONObject()
        states.forEach { (id, s) ->
            o.put(id, JSONObject().apply {
                put("count", s.count)
                s.newestVideoId?.let { put("newest", it) }
                put("complete", s.complete)
            })
        }
        manifestFile.writeText(o.toString())
    }

    /** Compact per-source fingerprint for /index-status: what a peer needs to
     *  decide whether a push is worthwhile. */
    fun statusJson(): String {
        val o = JSONObject()
        states.forEach { (id, s) ->
            o.put(id, JSONObject().apply {
                put("count", s.count)
                put("complete", s.complete)
                // Content hash: count+newest catches both deltas and rebuilds.
                put("hash", (s.count.toString() + ":" + (s.newestVideoId ?: "")).hashCode())
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
        saveSource(sourceId, loadSourceFromJson(json, sourceId))
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

    private fun loadSourceFromJson(json: String, sourceId: String): List<IndexedVideo> =
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

    /** All file work off-main — called from the LAN worker and the crawler. */
    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
