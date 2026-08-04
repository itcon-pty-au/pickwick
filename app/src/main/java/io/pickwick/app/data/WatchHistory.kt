package io.pickwick.app.data

import android.content.Context

data class WatchProgress(
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long = 0L
) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /**
     * Finished means actually reached the end (STATE_ENDED saves 100%), with a
     * small 2% grace for backing out during the credits. Deliberately strict: a
     * video the session guard cut off at 91% must stay resumable in Keep watching.
     */
    val isFinished: Boolean get() = fraction >= 0.98f
}

/**
 * Local, on-device watch history. No accounts, no cloud — the device is the profile.
 * Keyed by video page URL.
 */
class WatchHistoryStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("watch_history", Context.MODE_PRIVATE)

    fun progress(videoUrl: String): WatchProgress? {
        val raw = prefs.getString(videoUrl, null) ?: return null
        val parts = raw.split('|')
        if (parts.size < 2) return null
        val pos = parts[0].toLongOrNull() ?: return null
        val dur = parts[1].toLongOrNull() ?: return null
        return WatchProgress(pos, dur, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    fun save(videoUrl: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        // commit(), not apply(): a force-kill (deploy, crash, power cut) drops
        // apply()'s pending async write — the kid's in-progress video would fall
        // out of Keep watching. Synchronous write bounds any loss to one tick.
        prefs.edit()
            .putString(videoUrl, "$positionMs|$durationMs|${System.currentTimeMillis()}")
            .commit()
    }

    /** Every recorded progress entry, for cross-device sync. */
    fun all(): Map<String, WatchProgress> =
        prefs.all.mapNotNull { (url, raw) ->
            (raw as? String) ?: return@mapNotNull null
            progress(url)?.let { url to it }
        }.toMap()

    /** Merge another device's history: per video, the newer timestamp wins. */
    fun mergeAll(incoming: Map<String, WatchProgress>) {
        val editor = prefs.edit()
        incoming.forEach { (url, p) ->
            val local = progress(url)
            if (local == null || p.lastWatchedAt > local.lastWatchedAt) {
                editor.putString(url, "${p.positionMs}|${p.durationMs}|${p.lastWatchedAt}")
            }
        }
        editor.apply()
    }
}
