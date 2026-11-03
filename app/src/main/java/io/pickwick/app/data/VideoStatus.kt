package io.pickwick.app.data

import android.content.Context

/**
 * What the extractor says about a hand-picked video: can it be played in the
 * app, and is it a live broadcast right now. No API key and no guessing — the
 * same StreamInfo the player resolves from.
 */
object VideoStatus {

    /** [playable] false covers private, removed, blocked and age-restricted videos. */
    data class Info(val playable: Boolean, val live: Boolean)

    /**
     * The tile state for [video] given its last known status: LIVE only while
     * it still is, Offline once it can't be played (or a live item went off air).
     * [Video.live] on the way in is the flag stored when the video was added.
     */
    fun apply(
        video: Video,
        entry: VideoStatusCache.Entry?,
        now: Long = System.currentTimeMillis()
    ): Video =
        if (entry == null) video else video.copy(
            live = video.live && entry.live,
            offline = !entry.playable ||
                (entry.failed && now - entry.checkedAt < VideoStatusCache.FAILED_MS) ||
                (video.live && !entry.live)
        )
}

/** Last known [VideoStatus] per video id, so checks stay short and the tiles paint at once. */
class VideoStatusCache(context: Context) {

    data class Entry(
        val playable: Boolean,
        val live: Boolean,
        val checkedAt: Long,
        /** The player gave up on it at [checkedAt]; a good check clears it, so does time. */
        val failed: Boolean = false
    )

    private val prefs =
        context.applicationContext.getSharedPreferences("video_status", Context.MODE_PRIVATE)

    fun all(): Map<String, Entry> = prefs.all.mapNotNull { (id, v) ->
        val p = (v as? String)?.split('|') ?: return@mapNotNull null
        if (p.size < 4) return@mapNotNull null
        id to Entry(p[0] == "1", p[1] == "1", p[2].toLongOrNull() ?: 0L, p[3] == "1")
    }.toMap()

    private fun encode(e: Entry) =
        "${if (e.playable) 1 else 0}|${if (e.live) 1 else 0}|${e.checkedAt}|${if (e.failed) 1 else 0}"

    /**
     * Re-checks the [ids] whose last check is older than [TTL_MS] with [check]
     * (null = couldn't tell, so the old answer stays). Sequential: it runs in
     * the background and the extractor is rate-limited anyway.
     */
    suspend fun refresh(
        ids: List<String>,
        now: Long = System.currentTimeMillis(),
        check: suspend (String) -> VideoStatus.Info?
    ) {
        val known = all()
        for (id in ids.distinct()) {
            if (known[id]?.let { now - it.checkedAt < TTL_MS } == true) continue
            val info = check(id) ?: continue
            prefs.edit().putString(id, encode(Entry(info.playable, info.live, now))).apply()
        }
    }

    /** The player couldn't play [videoId]: its tile reads Offline for a while. */
    fun markFailed(videoId: String, now: Long = System.currentTimeMillis()) {
        val e = all()[videoId] ?: Entry(playable = true, live = false, checkedAt = now)
        prefs.edit().putString(videoId, encode(e.copy(checkedAt = now, failed = true))).apply()
    }

    companion object {
        /** Short on purpose: an app open re-checks, but no constant polling. */
        const val TTL_MS = 10 * 60 * 1000L

        /** How long a playback failure keeps a tile Offline before it may be tried again. */
        const val FAILED_MS = 10 * 60 * 1000L
    }
}
