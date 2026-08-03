package io.santatube.app.data

/** What this device is playing right now, for the parent's stats screen. */
object NowPlaying {

    data class State(
        val title: String,
        val channel: String,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val updatedAt: Long
    )

    @Volatile
    private var state: State? = null

    fun update(title: String, channel: String, positionMs: Long, durationMs: Long, playing: Boolean) {
        state = State(title, channel, positionMs, durationMs, playing, System.currentTimeMillis())
    }

    fun clear() {
        state = null
    }

    /** Null once the player stops updating (stale after 30s). */
    fun current(): State? =
        state?.takeIf { System.currentTimeMillis() - it.updatedAt < 30_000 }
}

/**
 * Bridge from the LAN server to whatever player is on screen, so a parent's
 * phone can pause/resume playback ("come to dinner"). The active PlayerActivity
 * registers itself; null means nothing is playing.
 */
object RemotePlayerControl {
    /** Handles "pause" | "play"; returns false when there is no active player. */
    @Volatile
    var handler: ((String) -> Boolean)? = null
}
