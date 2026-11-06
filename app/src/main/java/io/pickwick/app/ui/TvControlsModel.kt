package io.pickwick.app.ui

import io.pickwick.app.data.YouTubeRepository

/** Where the remote's arrows act while the TV player's controls are up. */
internal enum class TvZone { Timeline, Buttons }

internal enum class TvButton { Previous, PlayPause, Next, Audio, Subtitles, Quality }

internal enum class TvTrackPanel { Hidden, Audio, Subtitles, Quality }

/**
 * The button row for what's playing. Previous and Next need a lineup: a parent
 * who left custom-playlist autoplay off asked for each video to end back on
 * the playlist, and that launch hands the player a single video. Track buttons
 * appear only when there is something to choose between.
 */
internal fun tvButtons(
    queueSize: Int,
    audioTracks: Int,
    subtitles: Int,
    hasQualityPicker: Boolean
): List<TvButton> = buildList {
    if (queueSize > 1) add(TvButton.Previous)
    add(TvButton.PlayPause)
    if (queueSize > 1) add(TvButton.Next)
    if (audioTracks > 1) add(TvButton.Audio)
    if (subtitles > 0) add(TvButton.Subtitles)
    if (hasQualityPicker) add(TvButton.Quality)
}

/** The neighbor of [from] in [direction], staying on the ends; the first button if [from] is gone. */
internal fun List<TvButton>.moved(from: TvButton, direction: Int): TvButton {
    val at = indexOf(from)
    return if (at < 0) first() else this[(at + direction).coerceIn(0, lastIndex)]
}

/**
 * How far each press or hold of ◀ ▶ moves the scrub target. A tap is always
 * [TAP_STEP_MS], so tapping stays predictable however fast it is. Holding speeds
 * up with *time held*: the remote repeats about twenty times a second, so
 * counting key events would cross a whole video in moments. Steps are capped to
 * a share of the video, or a short one would be over in a single jump.
 */
internal class ScrubAccelerator {
    private var heldSince = 0L
    private var lastStepAt = 0L

    /** The step to apply now, or null when this key repeat is inside the rate limit. */
    fun next(nowMs: Long, isRepeat: Boolean, durationMs: Long): Long? {
        if (!isRepeat) {
            heldSince = nowMs
            lastStepAt = nowMs
            return TAP_STEP_MS
        }
        if (nowMs - lastStepAt < REPEAT_INTERVAL_MS) return null
        lastStepAt = nowMs
        val held = nowMs - heldSince
        val step = when {
            held < 1_500 -> TAP_STEP_MS
            held < 3_500 -> 30_000L
            held < 6_000 -> 60_000L
            else -> 120_000L
        }
        return step.coerceAtMost((durationMs / MIN_STEPS_ACROSS).coerceAtLeast(TAP_STEP_MS))
    }

    companion object {
        const val TAP_STEP_MS = 10_000L
        const val REPEAT_INTERVAL_MS = 150L
        const val MIN_STEPS_ACROSS = 25L
    }
}

/** The target stops short of the very end: landing there would count as finishing the video. */
internal fun scrubTarget(fromMs: Long, direction: Int, stepMs: Long, durationMs: Long): Long =
    (fromMs + direction * stepMs).coerceIn(0, (durationMs - 1_000).coerceAtLeast(0))

internal fun chapterAt(chapters: List<YouTubeRepository.Chapter>, positionMs: Long): String? =
    chapters.lastOrNull { it.startMs <= positionMs }?.title

/** Previous restarts unless the video has barely begun, so a second press goes back further. */
internal fun previousGoesBack(index: Int, positionMs: Long): Boolean =
    index > 0 && positionMs <= RESTART_THRESHOLD_MS

internal const val RESTART_THRESHOLD_MS = 5_000L

/** Seconds on the up-next card, or null while it shouldn't show. */
internal fun upNextSeconds(hasNext: Boolean, playing: Boolean, remainingMs: Long): Long? =
    if (hasNext && playing && remainingMs in 1..UP_NEXT_WINDOW_MS) (remainingMs + 999) / 1000 else null

internal const val UP_NEXT_WINDOW_MS = 10_000L

/** The screen-time chip. Rounded up, matching how the 5 and 1 minute warnings count. */
internal fun timeLeftLabel(remainingMs: Long): String {
    val minutes = (remainingMs + 59_999) / 60_000
    return when {
        remainingMs < 60_000 -> "Under a minute left"
        minutes < 90 -> "$minutes min left"
        minutes % 60 == 0L -> "${minutes / 60} hr left"
        else -> "${minutes / 60} hr ${minutes % 60} min left"
    }
}

/**
 * Titles and thumbnails for the up-next card, set by whoever launches the
 * player. In memory rather than in the intent: a long playlist's lineup is
 * already a large binder transaction, and the card can do without a title in the
 * rare case the process was rebuilt. Same static handoff as [ListenService].
 */
internal object PlayerQueueInfo {
    data class Item(val title: String, val thumbnailUrl: String?)

    @Volatile
    var byUrl: Map<String, Item> = emptyMap()
}
