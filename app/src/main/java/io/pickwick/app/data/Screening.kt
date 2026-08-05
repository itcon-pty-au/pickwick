package io.pickwick.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File

/**
 * Persistent per-video AI verdicts. A video is screened once per rules version;
 * verdicts survive restarts so the catalog isn't re-screened (and re-billed) on
 * every launch. Title/channel/thumb are stored so stats can show what was blocked
 * even after the feed cache moves on.
 */
class ScreeningStore(context: Context) {

    data class Entry(
        /** Strictest across kids — profile-unaware readers stay fail-closed. */
        val verdict: AiScreener.Verdict,
        val reason: String,
        val title: String,
        val channel: String,
        val thumb: String?,
        val rulesVersion: Int,
        val at: Long,
        /** Per-kid verdicts (profile id → verdict); empty on pre-profile entries. */
        val perProfile: Map<String, AiScreener.Verdict> = emptyMap()
    ) {
        fun verdictFor(profileId: String?): AiScreener.Verdict =
            profileId?.let { perProfile[it] } ?: verdict
    }

    private val file = File(context.filesDir, "screening.json")
    private var cache: MutableMap<String, Entry>? = null

    /** (lastModified, length) of the file the cache was built from — see [all]. */
    private var loadedFrom: Pair<Long, Long> = 0L to 0L

    private fun signature() = file.lastModified() to file.length()

    /**
     * More than one instance of this store is alive at a time (the feed's screener
     * writes; the parent's review queue reads), so a cache pinned at construction
     * serves a snapshot that ages out — the review queue used to reveal verdicts a
     * batch at a time, one batch per reopen. Re-read whenever the file has moved
     * on. Length is checked alongside mtime because filesystem timestamps are only
     * second-granular, and a screening batch lands well inside one second.
     */
    @Synchronized
    private fun all(): MutableMap<String, Entry> {
        cache?.let { if (signature() == loadedFrom) return it }
        val map = mutableMapOf<String, Entry>()
        runCatching {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                root.keys().forEach { id ->
                    val o = root.getJSONObject(id)
                    val pp = o.optJSONObject("pp")?.let { obj ->
                        obj.keys().asSequence().associateWith { pid ->
                            runCatching { AiScreener.Verdict.valueOf(obj.getString(pid)) }
                                .getOrDefault(AiScreener.Verdict.REVIEW)
                        }
                    } ?: emptyMap()
                    map[id] = Entry(
                        verdict = runCatching { AiScreener.Verdict.valueOf(o.getString("v")) }
                            .getOrDefault(AiScreener.Verdict.REVIEW),
                        reason = o.optString("why"),
                        title = o.optString("title"),
                        channel = o.optString("channel"),
                        thumb = o.optString("thumb").ifEmpty { null },
                        rulesVersion = o.optInt("rv"),
                        at = o.optLong("at"),
                        perProfile = pp
                    )
                }
            }
        }
        cache = map
        loadedFrom = signature()
        return map
    }

    @Synchronized
    fun get(videoId: String): Entry? = all()[videoId]

    @Synchronized
    fun putAll(entries: Map<String, Entry>) {
        val map = all()
        map.putAll(entries)
        // Cap the file: keep the newest verdicts, drop ancient ones (they'd
        // simply be re-screened if that video ever resurfaces).
        if (map.size > 5000) {
            val keep = map.entries.sortedByDescending { it.value.at }.take(4000)
            map.clear()
            keep.forEach { map[it.key] = it.value }
        }
        persist(map)
    }

    /** Blocked/review verdicts for the current rules, newest first — the stats feed. */
    @Synchronized
    fun flagged(rulesVersion: Int): List<Pair<String, Entry>> =
        all().entries
            .filter { it.value.rulesVersion == rulesVersion && it.value.verdict != AiScreener.Verdict.ALLOW }
            .sortedByDescending { it.value.at }
            .map { it.key to it.value }

    @Synchronized
    fun screenedCount(rulesVersion: Int): Int =
        all().values.count { it.rulesVersion == rulesVersion }

    private fun persist(map: Map<String, Entry>) {
        runCatching {
            val root = JSONObject()
            map.forEach { (id, e) ->
                root.put(id, JSONObject()
                    .put("v", e.verdict.name)
                    .put("why", e.reason)
                    .put("title", e.title)
                    .put("channel", e.channel)
                    .put("thumb", e.thumb ?: "")
                    .put("rv", e.rulesVersion)
                    .put("at", e.at)
                    .apply {
                        if (e.perProfile.isNotEmpty()) {
                            put("pp", JSONObject().apply {
                                e.perProfile.forEach { (pid, v) -> put(pid, v.name) }
                            })
                        }
                    })
            }
            file.writeText(root.toString())
            // Our own write must not read as someone else's to the next all().
            loadedFrom = signature()
        }
    }
}

/**
 * Feed-side gate. Holds the active config, answers "may the kid see this video?"
 * synchronously from the verdict cache, and screens the unscreened in background
 * batches. Fail-closed by design: no verdict (yet) means not visible.
 */
class Screener(private val store: ScreeningStore) {

    @Volatile var config: AiConfig = AiConfig()
    /** Parent allow-overrides already resolved for the active kid (global + per-kid). */
    @Volatile var allowedOverrides: Set<String> = emptySet()
    /** The family's kids — screening judges all of them in one call. */
    @Volatile var profiles: List<Profile> = emptyList()
    /** Whose verdicts gate visibility right now; null = pre-profile behavior. */
    @Volatile var activeProfileId: String? = null

    private val inFlight = mutableSetOf<String>()

    companion object {
        /**
         * At most two AI calls in flight across all sources. A whole-catalog warm
         * kicks one screenAsync per source, and an unthrottled burst trips provider
         * rate limits — which used to strand every batch as "unscreened" (= hidden).
         */
        private val aiCalls = Semaphore(2)

        /** A failed batch retries after these delays before giving up until the next feed load. */
        private val RETRY_DELAYS_MS = longArrayOf(30_000, 120_000)
    }

    /**
     * Whether the kid may see this video right now. With screening off, always.
     * With it on: parent override wins, then a current-rules ALLOW verdict; anything
     * else (blocked, needs-review, not yet screened) stays hidden.
     */
    fun isVisible(video: Video): Boolean {
        if (!config.enabled) return true
        val id = video.videoId ?: return false
        if (id in allowedOverrides) return true
        val e = store.get(id) ?: return false
        return e.rulesVersion == config.rulesVersion &&
            e.verdictFor(activeProfileId) == AiScreener.Verdict.ALLOW
    }

    /**
     * Screens whatever in [videos] has no current verdict, in batches, calling
     * [onUpdated] as each batch of verdicts lands so the UI can re-filter. A failed
     * batch retries with backoff ([RETRY_DELAYS_MS]) — transient provider errors
     * must not strand videos in the unscreened (hidden) state until the next feed
     * load, which on an idle device may be days away.
     */
    fun screenAsync(scope: CoroutineScope, videos: List<Video>, onUpdated: () -> Unit) {
        val cfg = config
        if (!cfg.enabled || cfg.model.isBlank()) return

        val todo = synchronized(inFlight) {
            videos
                .filter { v ->
                    val id = v.videoId ?: return@filter false
                    id !in allowedOverrides &&
                        id !in inFlight &&
                        store.get(id)?.rulesVersion != cfg.rulesVersion
                }
                .distinctBy { it.videoId }
                .also { list -> inFlight += list.mapNotNull { it.videoId } }
        }
        if (todo.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            todo.chunked(30).forEach { batch ->
                try {
                    screenBatch(cfg, batch, onUpdated)
                } finally {
                    // Also on cancellation — otherwise the ids stay "in flight"
                    // forever and are never retried while the app lives.
                    synchronized(inFlight) { inFlight -= batch.mapNotNull { it.videoId }.toSet() }
                }
            }
        }
    }

    private suspend fun screenBatch(cfg: AiConfig, batch: List<Video>, onUpdated: () -> Unit) {
        var attempt = 0
        while (true) {
            val results = try {
                aiCalls.withPermit { AiScreener.screen(cfg, batch, profiles) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Pickwick",
                    "AI screening batch failed (attempt ${attempt + 1})", e)
                if (attempt >= RETRY_DELAYS_MS.size) return
                delay(RETRY_DELAYS_MS[attempt])
                attempt++
                continue
            }
            val byId = batch.associateBy { it.videoId }
            store.putAll(results.associate { r ->
                val v = byId[r.videoId]
                r.videoId to ScreeningStore.Entry(
                    verdict = r.verdict,
                    reason = r.reason,
                    title = v?.title ?: "",
                    channel = v?.channelName ?: "",
                    thumb = v?.thumbnailUrl,
                    rulesVersion = cfg.rulesVersion,
                    at = System.currentTimeMillis(),
                    perProfile = r.perProfile
                )
            })
            onUpdated()
            return
        }
    }
}
