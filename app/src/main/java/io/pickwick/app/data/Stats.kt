package io.pickwick.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phone-side snapshot of each paired device's last stats payload, so the parent
 * can review the day — and the "Waiting for your OK" queue can list videos the
 * device is holding back — while the TV is off.
 * One file per device token, the payload wrapped with its fetch time so the UI
 * can say how old the snapshot is.
 */
class StatsCache(context: Context) {

    private val dir = File(context.filesDir, "stats_cache").apply { mkdirs() }
    private val digest = DigestStore(File(context.filesDir, "digest"))

    fun save(deviceToken: String, statsJson: String) {
        runCatching {
            File(dir, "$deviceToken.json").writeText(
                JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("stats", statsJson)
                    .toString()
            )
        }
        // Channel totals in the payload are lifetime numbers; the weekly digest
        // needs a per-day baseline to diff against, so every cached snapshot
        // also stamps today's totals. Same-day saves overwrite, so the row ends
        // up being "totals as of the last sync that day". Keyed per kid, not
        // just per device: a shared TV reports whichever profile is active, and
        // diffing kid A's totals against a row written under kid B would invent
        // viewing. Callers already run this off-main.
        runCatching {
            val root = JSONObject(statsJson)
            root.optJSONArray("topChannels")?.let { top ->
                val channels = (0 until top.length()).associate { i ->
                    val o = top.getJSONObject(i)
                    o.getString("name") to o.getInt("min")
                }
                val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(java.util.Date())
                val key = DigestStore.key(deviceToken, root.optString("profileName").ifEmpty { null })
                digest.record(key, today, channels)
            }
        }
    }

    /** The last snapshot as (fetchedAt, payload json), or null if never fetched. */
    fun load(deviceToken: String): Pair<Long, String>? = runCatching {
        val file = File(dir, "$deviceToken.json")
        if (!file.exists()) return@runCatching null
        val root = JSONObject(file.readText())
        root.getLong("at") to root.getString("stats")
    }.getOrNull()
}

/** Builds this device's stats payload (TV side) and parses it (phone side). */
object Stats {

    data class RecentVideo(
        val title: String,
        val channel: String,
        val thumbnailUrl: String?,
        val percent: Int,
        val watchedAt: Long
    )

    /** A video the AI held back on this device, awaiting the parent's word. */
    data class AiFlagged(
        val videoId: String,
        val title: String,
        val channel: String,
        val thumbnailUrl: String?,
        /** "BLOCK" or "REVIEW" (unsure). */
        val verdict: String,
        val reason: String,
        val at: Long
    )

    data class Payload(
        val watchedTodayMin: Int,
        val budgetTodayMin: Int?,
        val bonusTodayMin: Int,
        val sittingWatchedMin: Int,
        val sittingCapMin: Int?,
        val state: String,
        val breakUntil: String?,
        /** "🦊 Noa" — whose day this payload describes; null pre-profiles. */
        val profileName: String? = null,
        val nowPlaying: NowPlaying.State?,
        val recent: List<RecentVideo>,
        val topChannels: List<Pair<String, Int>>,
        val history: List<Pair<String, Int>>,
        val watchlistCount: Int,
        /**
         * True when some source on the device has a non-1x time multiplier, so
         * watched/sitting minutes are *counted* minutes rather than real ones.
         */
        val multipliersActive: Boolean = false,
        /** Videos screened under the current rules; null when AI screening is off. */
        val aiScreened: Int? = null,
        /** Cached-feed videos with no verdict yet — hidden on the device until screened. */
        val aiPending: Int = 0,
        val aiFlagged: List<AiFlagged> = emptyList()
    )

    fun build(context: Context): String {
        val app = context.applicationContext
        val config = ConfigStore(app).load()
        // With profiles, report the kid this device is showing right now — the
        // parent's dashboard says whose day it is looking at.
        val activeProfile = config.profiles.takeIf { it.isNotEmpty() }?.let { profiles ->
            val assigned = config.deviceProfiles[PairingStore(app).deviceToken()]
            profiles.firstOrNull { it.id == assigned }
                ?: profiles.firstOrNull { it.id == ActiveProfileStore(app).activeId() }
                ?: profiles.first()
        }
        val suffix = ProfileNamespace(app).suffixFor(activeProfile?.id)
        val guard = SessionGuard(app, suffix)
        val snap = guard.snapshot()
        val history = guard.history()
        // Effectively unbounded: the digest diffs these lifetime totals against a
        // daily baseline, and a truncated list makes a channel that climbs into
        // it look like a week of binge (its whole lifetime counts as new). A
        // family whitelist holds dozens of channels, so "all of them" is cheap.
        val usage = ChannelUsage(app, suffix).topChannels(limit = 500)
        val watchHistory = WatchHistoryStore(app, suffix)
        val videoCache = VideoCache(app)
        val sources = SourceCache(app).load()

        // Recent videos: watch history joined with cached metadata for titles/art.
        val known = sources.flatMap { videoCache.load(it.id) }.distinctBy { it.url }
        val recent = known.mapNotNull { video ->
            val p = watchHistory.progress(video.url) ?: return@mapNotNull null
            if (p.lastWatchedAt <= 0) return@mapNotNull null
            Triple(video, p.fraction, p.lastWatchedAt)
        }.sortedByDescending { it.third }.take(15)

        val root = JSONObject()
            .put("watchedTodayMin", snap.watchedTodayMin)
            .put("budgetTodayMin", snap.budgetTodayMin ?: JSONObject.NULL)
            .put("bonusTodayMin", snap.bonusTodayMin)
            .put("sittingWatchedMin", snap.sittingWatchedMin)
            .put("sittingCapMin", snap.sittingCapMin ?: JSONObject.NULL)
            .put("state", snap.state)
            .put("breakUntil", snap.breakUntil ?: JSONObject.NULL)
            .put("watchlistCount", SavedListStore(app, suffix).load().size)
        activeProfile?.let { root.put("profileName", it.name) }

        NowPlaying.current()?.let { np ->
            root.put("nowPlaying", JSONObject()
                .put("title", np.title)
                .put("channel", np.channel)
                .put("positionMs", np.positionMs)
                .put("durationMs", np.durationMs)
                .put("playing", np.playing))
        }

        root.put("recent", JSONArray().apply {
            recent.forEach { (video, fraction, at) ->
                put(JSONObject()
                    .put("title", video.title)
                    .put("channel", video.channelName)
                    .put("thumb", video.thumbnailUrl ?: "")
                    .put("percent", (fraction * 100).toInt())
                    .put("at", at))
            }
        })
        root.put("topChannels", JSONArray().apply {
            usage.forEach { (name, mins) ->
                put(JSONObject().put("name", name).put("min", mins))
            }
        })
        root.put("history", JSONArray().apply {
            history.forEach { (day, mins) ->
                put(JSONObject().put("day", day).put("min", mins))
            }
        })

        // Reported from the device's own config, so the label matches what this
        // device actually enforced — not what the phone believes it pushed.
        root.put("multipliers", config.sources.any { it.timeMultiplierPercent != 100 })

        if (config.ai.enabled) {
            val screening = ScreeningStore(app)
            val version = config.ai.rulesVersion
            // Already-resolved items (parent allowed or permanently blocked) drop out.
            val resolved = config.aiAllowedVideoIds + config.blockedVideoIds
            // Cached-feed videos still waiting for a verdict. They are hidden on
            // this device (fail-closed), so a stalled screening run must show up
            // here as a number — not read as "nothing new".
            val pending = known.mapNotNull { it.videoId }.distinct()
                .count { id -> id !in resolved && screening.get(id)?.rulesVersion != version }
            root.put("ai", JSONObject()
                .put("screened", screening.screenedCount(version))
                .put("pending", pending)
                .put("flagged", JSONArray().apply {
                    screening.flagged(version)
                        .filterNot { (id, _) -> id in resolved }
                        // Soft cap so a runaway store can't bloat the payload; in
                        // practice the parent sees every held-back video.
                        .take(500)
                        .forEach { (id, e) ->
                            put(JSONObject()
                                .put("id", id)
                                .put("title", e.title)
                                .put("channel", e.channel)
                                .put("thumb", e.thumb ?: "")
                                .put("verdict", e.verdict.name)
                                .put("why", e.reason)
                                .put("at", e.at))
                        }
                }))
        }
        return root.toString()
    }

    fun parse(json: String): Payload? = runCatching {
        val root = JSONObject(json)
        fun optInt(name: String): Int? =
            if (root.isNull(name)) null else root.getInt(name)

        val np = root.optJSONObject("nowPlaying")?.let {
            NowPlaying.State(
                title = it.getString("title"),
                channel = it.optString("channel"),
                positionMs = it.getLong("positionMs"),
                durationMs = it.getLong("durationMs"),
                playing = it.getBoolean("playing"),
                updatedAt = System.currentTimeMillis()
            )
        }
        val recentArr = root.optJSONArray("recent") ?: JSONArray()
        val recent = (0 until recentArr.length()).map { i ->
            val o = recentArr.getJSONObject(i)
            RecentVideo(
                title = o.getString("title"),
                channel = o.optString("channel"),
                thumbnailUrl = o.optString("thumb").ifEmpty { null },
                percent = o.optInt("percent"),
                watchedAt = o.optLong("at")
            )
        }
        val topArr = root.optJSONArray("topChannels") ?: JSONArray()
        val top = (0 until topArr.length()).map { i ->
            val o = topArr.getJSONObject(i)
            o.getString("name") to o.getInt("min")
        }
        val histArr = root.optJSONArray("history") ?: JSONArray()
        val hist = (0 until histArr.length()).map { i ->
            val o = histArr.getJSONObject(i)
            o.getString("day") to o.getInt("min")
        }

        val aiObj = root.optJSONObject("ai")
        val flaggedArr = aiObj?.optJSONArray("flagged") ?: JSONArray()
        val flagged = (0 until flaggedArr.length()).map { i ->
            val o = flaggedArr.getJSONObject(i)
            AiFlagged(
                videoId = o.getString("id"),
                title = o.optString("title"),
                channel = o.optString("channel"),
                thumbnailUrl = o.optString("thumb").ifEmpty { null },
                verdict = o.optString("verdict"),
                reason = o.optString("why"),
                at = o.optLong("at")
            )
        }

        Payload(
            watchedTodayMin = root.optInt("watchedTodayMin"),
            budgetTodayMin = optInt("budgetTodayMin"),
            bonusTodayMin = root.optInt("bonusTodayMin"),
            sittingWatchedMin = root.optInt("sittingWatchedMin"),
            sittingCapMin = optInt("sittingCapMin"),
            state = root.optString("state"),
            breakUntil = if (root.isNull("breakUntil")) null else root.getString("breakUntil"),
            profileName = root.optString("profileName").ifEmpty { null },
            nowPlaying = np,
            recent = recent,
            topChannels = top,
            history = hist,
            watchlistCount = root.optInt("watchlistCount"),
            multipliersActive = root.optBoolean("multipliers", false),
            aiScreened = aiObj?.optInt("screened"),
            aiPending = aiObj?.optInt("pending") ?: 0,
            aiFlagged = flagged
        )
    }.getOrNull()
}
