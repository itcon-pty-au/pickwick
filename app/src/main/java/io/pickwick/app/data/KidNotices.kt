package io.pickwick.app.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Kid-facing lines caused by something a parent just did from their phone:
 * bonus minutes, a rules edit, a timeout lifted. Without them the parent has
 * to say it out loud ("I gave you fifteen more"), and until the kid tries to
 * play something the device quietly contradicts what they were told.
 *
 * Nothing is queued. A message emitted while no Pickwick screen is collecting
 * is dropped, not replayed the next time the app opens — a bonus announced an
 * hour late reads as a glitch, and the rules themselves are visible wherever
 * the kid looks anyway.
 */
object KidNotices {

    /** [id] gives an identical repeat its own identity, so the pill re-shows. */
    data class Message(val text: String, val id: Long)

    private val seq = AtomicLong()

    // replay = 0 is the "let it go" part; the one-slot buffer only covers a
    // collector that is mid-recomposition when a push lands.
    private val _messages = MutableSharedFlow<Message>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val messages: SharedFlow<Message> = _messages

    /** Safe from any thread — the LAN server posts from its socket workers. */
    fun post(text: String) {
        _messages.tryEmit(Message(text, seq.incrementAndGet()))
    }

    fun grant(minutes: Int): String =
        "You got $minutes more ${if (minutes == 1) "minute" else "minutes"}! 🎉"

    /**
     * What a pushed config should tell the kid, or null when nothing about
     * their screen time moved — a push that only adds a channel or blocks a
     * video must stay silent.
     *
     * [remainingMin] is today's budget left under the new rules (null when no
     * minute budget is set — see [SessionGuard.remainingTodayMin]); the number
     * is the thing the kid actually wants, so it's spoken whenever there is
     * one to speak.
     *
     * Skip passes ("skip the next break", "skip tonight's bedtime") are
     * deliberately silent: they buy a reprieve the kid meets on their own, and
     * announcing one invites the argument the parent was avoiding.
     */
    fun configChange(
        before: Limits,
        after: Limits,
        remainingMin: Int?,
        now: Long = System.currentTimeMillis()
    ): String? {
        // Active pauses, not merely set ones: a timeout that lapsed at midnight
        // is still on file until the parent's next edit, and rediscovering it
        // the next morning is not news.
        val wasPaused = (before.pausedUntilMillis ?: 0L) > now
        val isPaused = (after.pausedUntilMillis ?: 0L) > now
        val left = remainingMin
            ?.let { " — $it ${if (it == 1) "minute" else "minutes"} left today" }
            .orEmpty()
        return when {
            !wasPaused && isPaused -> "A parent paused screen time for today 💛"
            wasPaused && !isPaused -> "Screen time is back on$left 🎉"
            before.rules() != after.rules() -> "Screen time updated$left ⏰"
            else -> null
        }
    }

    /** The recurring rules alone: the passes above lapse on their own. */
    private fun Limits.rules(): Limits = copy(
        pausedUntilMillis = null,
        breakPassUntilMillis = null,
        windows = windows.map { it.copy(passUntilMillis = null) }
    )
}
