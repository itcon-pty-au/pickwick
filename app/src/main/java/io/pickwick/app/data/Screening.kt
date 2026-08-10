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
class ScreeningStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "screening.json"))

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
        val perProfile: Map<String, AiScreener.Verdict> = emptyMap(),
        /**
         * True once the pre-play deep check (description + tags + transcript)
         * has ruled. A deep verdict is the stronger evidence: the play gate
         * runs it once per video per rules version, and a title-only batch
         * must never overwrite it — see [Screener.screenBatch].
         */
        val deep: Boolean = false,
        /**
         * [AiScreener.noteHash] of the channel note this verdict was judged
         * under (0 = none). An ALLOW/REVIEW under a different note is stale —
         * the parent changed the channel's rules — and re-screens; a BLOCK
         * never goes stale: notes exist to catch more junk, not to un-block.
         */
        val noteHash: Int = 0
    ) {
        fun verdictFor(profileId: String?): AiScreener.Verdict =
            profileId?.let { perProfile[it] } ?: verdict
    }

    private fun signature() = Triple(file.path, file.lastModified(), file.length())

    /**
     * More than one instance of this store is alive at a time (the feed's screener
     * writes; the parent's review queue reads; the LAN server imports peer
     * verdicts), which is why the parsed map lives in the companion, not the
     * instance: a per-instance cache went stale after another instance wrote, and
     * rebuilding it re-parsed ~5000 entries of JSON on the main thread during feed
     * filtering. The signature check remains as cheap insurance against a write
     * that somehow bypassed [LOCK]. Length is checked alongside mtime because
     * filesystem timestamps are only second-granular, and a screening batch lands
     * well inside one second. Callers must hold [LOCK].
     */
    private fun all(): MutableMap<String, Entry> {
        cache?.let { if (signature() == cacheKey) return it }
        val map = mutableMapOf<String, Entry>()
        runCatching {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                root.keys().forEach { id -> map[id] = parseEntry(root.getJSONObject(id)) }
            }
        }
        cache = map
        cacheKey = signature()
        return map
    }

    private fun parseEntry(o: JSONObject): Entry {
        val pp = o.optJSONObject("pp")?.let { obj ->
            obj.keys().asSequence().associateWith { pid ->
                runCatching { AiScreener.Verdict.valueOf(obj.getString(pid)) }
                    .getOrDefault(AiScreener.Verdict.REVIEW)
            }
        } ?: emptyMap()
        return Entry(
            verdict = runCatching { AiScreener.Verdict.valueOf(o.getString("v")) }
                .getOrDefault(AiScreener.Verdict.REVIEW),
            reason = o.optString("why"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            thumb = o.optString("thumb").ifEmpty { null },
            rulesVersion = o.optInt("rv"),
            at = o.optLong("at"),
            perProfile = pp,
            deep = o.optBoolean("dc"),
            noteHash = o.optInt("nh")
        )
    }

    private fun entryJson(e: Entry): JSONObject = JSONObject()
        .put("v", e.verdict.name)
        .put("why", e.reason)
        .put("title", e.title)
        .put("channel", e.channel)
        .put("thumb", e.thumb ?: "")
        .put("rv", e.rulesVersion)
        .put("at", e.at)
        .apply { if (e.deep) put("dc", true) }
        .apply { if (e.noteHash != 0) put("nh", e.noteHash) }
        .apply {
            if (e.perProfile.isNotEmpty()) {
                put("pp", JSONObject().apply {
                    e.perProfile.forEach { (pid, v) -> put(pid, v.name) }
                })
            }
        }

    fun get(videoId: String): Entry? = synchronized(LOCK) { all()[videoId] }

    fun putAll(entries: Map<String, Entry>) {
        synchronized(LOCK) {
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
    }

    /** Blocked/review verdicts for the current rules, newest first — the stats feed. */
    fun flagged(rulesVersion: Int): List<Pair<String, Entry>> = synchronized(LOCK) {
        all().entries
            .filter { it.value.rulesVersion == rulesVersion && it.value.verdict != AiScreener.Verdict.ALLOW }
            .sortedByDescending { it.value.at }
            .map { it.key to it.value }
    }

    fun screenedCount(rulesVersion: Int): Int = synchronized(LOCK) {
        all().values.count { it.rulesVersion == rulesVersion }
    }

    /**
     * This device's verdicts under [rulesVersion], serialized for LAN verdict-
     * sharing: each video is billed to the AI once per rules version, by
     * whichever device sees it first — peers import the verdict instead.
     */
    fun exportJson(rulesVersion: Int): String = synchronized(LOCK) {
        val root = JSONObject()
        all().forEach { (id, e) -> if (e.rulesVersion == rulesVersion) root.put(id, entryJson(e)) }
        root.toString()
    }

    /**
     * Merges a peer's verdicts. Only entries under [rulesVersion] (this device's
     * current rules) count, and never over one we already hold for the same
     * rules — our own verdict is just as good, and not overwriting keeps a
     * pull-then-push exchange from ping-ponging entries between devices.
     * The one exception: a peer's deep (pre-play) verdict replaces our
     * title-only one — it judged the actual content, and taking it both saves
     * this device the play-time AI call and carries a TV's blacklist to the
     * phone. Deep-over-shallow is one-way, so it can't ping-pong either.
     * Returns how many were new.
     */
    fun importJson(json: String, rulesVersion: Int): Int {
        val incoming = runCatching { JSONObject(json) }.getOrNull() ?: return 0
        // The existing-check and the merge must sit under one lock acquisition:
        // the LAN server calls this from its own thread while the feed screener
        // is writing, and a gap between them re-creates the lost-update race.
        synchronized(LOCK) {
            val existing = all()
            val fresh = mutableMapOf<String, Entry>()
            incoming.keys().forEach { id ->
                val e = runCatching { parseEntry(incoming.getJSONObject(id)) }.getOrNull()
                    ?: return@forEach
                if (e.rulesVersion != rulesVersion) return@forEach
                val held = existing[id]?.takeIf { it.rulesVersion == rulesVersion }
                if (held == null || (e.deep && !held.deep)) fresh[id] = e
            }
            if (fresh.isNotEmpty()) putAll(fresh)
            return fresh.size
        }
    }

    /** Callers must hold [LOCK]. */
    private fun persist(map: Map<String, Entry>) {
        runCatching {
            val root = JSONObject()
            map.forEach { (id, e) -> root.put(id, entryJson(e)) }
            // Sibling temp file, then rename into place: a crash or power cut
            // mid-write must never truncate this file. all() swallows parse
            // failures, so a torn write wouldn't surface as an error — the whole
            // catalog would silently re-screen, re-billing the AI provider.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                // Android's rename replaces the target atomically; Windows (JVM
                // tests) refuses to, so fall back to delete-then-rename there.
                file.delete()
                tmp.renameTo(file)
            }
            // Our own write must not read as someone else's to the next all().
            cacheKey = signature()
        }
    }

    companion object {
        /**
         * The store is constructed in several places (feed screener, review
         * queue, stats, LAN import) over one file, so per-instance locking
         * (@Synchronized) never protected anything: two instances could
         * interleave read-modify-write and the last writer dropped the other's
         * paid verdicts. Same pattern as [DownloadStore] and [LocalLibrary].
         */
        private val LOCK = Any()

        /** Parsed map + source signature, shared across instances — see [all]. */
        private var cache: MutableMap<String, Entry>? = null
        private var cacheKey: Triple<String, Long, Long>? = null
    }
}

/**
 * Feed-side gate. Holds the active config, answers "may the kid see this video?"
 * synchronously from the verdict cache, and screens the unscreened in background
 * batches. Fail-closed by design: no verdict (yet) means not visible.
 */
/** [store] is public for the gates that share its verdicts (DownloadChecker). */
class Screener(val store: ScreeningStore) {

    @Volatile var config: AiConfig = AiConfig()
    /** Parent allow-overrides already resolved for the active kid (global + per-kid). */
    @Volatile var allowedOverrides: Set<String> = emptySet()
    /** Channel name → the parents' channel-specific instructions (see WhitelistEntry.aiNote). */
    @Volatile var channelNotes: Map<String, String> = emptyMap()
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
        if (e.rulesVersion != config.rulesVersion) return false
        if (e.verdictFor(activeProfileId) != AiScreener.Verdict.ALLOW) return false
        // An ALLOW earned under a different channel note is unproven against
        // the current one — fail closed until the re-screen lands. Entries
        // whose strictest verdict is BLOCK are exempt (they never re-screen,
        // so a per-kid ALLOW inside one must not go permanently dark).
        return e.verdict == AiScreener.Verdict.BLOCK ||
            e.noteHash == AiScreener.noteHash(channelNotes[video.channelName])
    }

    /**
     * Whether [isVisible]'s "hidden" would merely mean "no verdict yet" — i.e. a
     * screening call could still clear it, as opposed to an existing deny. Lets
     * search count "awaiting screening" separately from "held for review".
     */
    fun needsScreening(video: Video): Boolean {
        val cfg = config
        if (!cfg.enabled) return false
        val id = video.videoId ?: return false
        if (id in allowedOverrides) return false
        val e = store.get(id) ?: return true
        if (e.rulesVersion != cfg.rulesVersion) return true
        // Blocked stays blocked across note edits — the parent's ask was
        // "filter more junk", not "re-litigate what's already out".
        if (e.verdict == AiScreener.Verdict.BLOCK) return false
        return e.noteHash != AiScreener.noteHash(channelNotes[video.channelName])
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
                    // needsScreening owns the staleness rules (rules version,
                    // channel-note hash, blocked-stays-blocked, overrides).
                    id !in inFlight && needsScreening(v)
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
                aiCalls.withPermit {
                    AiScreener.screen(cfg, batch, profiles) { channelNotes[it.channelName] }
                }
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
            // A play-press deep check can land for one of these ids while the
            // batch was in flight; its verdict judged the actual content and
            // must not be replaced by this title-only one. A deep verdict made
            // under an *outdated* channel note is the exception — overwriting
            // it un-hides the video now, and the play gate re-runs its own
            // deep pass (the entry loses the deep flag) at the next press.
            val fresh = results.filterNot { r ->
                val existing = store.get(r.videoId) ?: return@filterNot false
                val note = byId[r.videoId]?.channelName?.let { channelNotes[it] }
                existing.deep && existing.rulesVersion == cfg.rulesVersion &&
                    (existing.verdict == AiScreener.Verdict.BLOCK ||
                        existing.noteHash == AiScreener.noteHash(note))
            }
            store.putAll(fresh.associate { r ->
                val v = byId[r.videoId]
                r.videoId to ScreeningStore.Entry(
                    verdict = r.verdict,
                    reason = r.reason,
                    title = v?.title ?: "",
                    channel = v?.channelName ?: "",
                    thumb = v?.thumbnailUrl,
                    rulesVersion = cfg.rulesVersion,
                    at = System.currentTimeMillis(),
                    perProfile = r.perProfile,
                    noteHash = AiScreener.noteHash(v?.channelName?.let { channelNotes[it] })
                )
            })
            onUpdated()
            return
        }
    }
}
