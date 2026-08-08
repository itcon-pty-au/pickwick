package io.pickwick.app.data

import java.util.Calendar

/**
 * Window arithmetic, kept free of Android and of the clock so it can be unit
 * tested. Everything works in *week minutes*: `(dayOfWeek - 1) * 1440 +
 * minuteOfDay`, with `dayOfWeek` in [Calendar]'s 1..7 (Sunday = 1). Doing the
 * maths modulo a week is what makes a Friday 19:30–07:00 bedtime survive both
 * midnight and the Saturday/Sunday week boundary without special cases.
 */
object TimeWindows {

    const val WEEK_MIN = 7 * 24 * 60

    /** One occurrence of a window: absolute week-minute start, and its length. */
    private data class Occurrence(val window: TimeWindow, val startAbs: Int, val lengthMin: Int)

    /**
     * A window's occurrences are keyed to the day it *starts*, never the day
     * it's currently running through: a Friday-night bedtime crossing midnight
     * must not evaporate at 00:00 when Saturday's day set takes over.
     */
    private fun occurrences(windows: List<TimeWindow>): List<Occurrence> =
        windows.flatMap { w ->
            val length = when {
                w.endMin > w.startMin -> w.endMin - w.startMin
                w.endMin == w.startMin -> 0
                else -> w.endMin + 24 * 60 - w.startMin
            }
            // start == end is zero length, not 24 hours. The settings UI won't
            // build one; a hand-edited config that does simply blocks nothing,
            // which is the safe way to read an ambiguous rule.
            if (length <= 0) emptyList()
            else w.days.sorted().map { d -> Occurrence(w, (d - 1) * 24 * 60 + w.startMin, length) }
        }

    private fun weekMinute(dayOfWeek: Int, minuteOfDay: Int) =
        (dayOfWeek - 1) * 24 * 60 + minuteOfDay

    /** Minutes from [fromAbs] into the occurrence, or null when it isn't running. */
    private fun elapsedIn(o: Occurrence, fromAbs: Int): Int? =
        ((fromAbs - o.startAbs + WEEK_MIN) % WEEK_MIN).takeIf { it < o.lengthMin }

    /**
     * The window blocking playback right now, or null. Overlaps are allowed and
     * deliberately unvalidated — any window covering the moment blocks it — so
     * this returns the one that ends *last*, which is the one whose reopening
     * time is worth telling a kid about.
     */
    fun activeAt(windows: List<TimeWindow>, dayOfWeek: Int, minuteOfDay: Int): TimeWindow? {
        val nowAbs = weekMinute(dayOfWeek, minuteOfDay)
        return occurrences(windows)
            .mapNotNull { o -> elapsedIn(o, nowAbs)?.let { o to o.lengthMin - it } }
            .maxByOrNull { it.second }?.first?.window
    }

    /**
     * Minutes until nothing blocks any more, or null when nothing blocks now.
     * Chains through back-to-back and overlapping windows (school hours ending
     * where a second window begins is one wait, not two), so it answers the
     * question the kid actually asked: when can I watch again?
     */
    fun blockedForMin(windows: List<TimeWindow>, dayOfWeek: Int, minuteOfDay: Int): Int? {
        val nowAbs = weekMinute(dayOfWeek, minuteOfDay)
        val all = occurrences(windows)
        var cursor = 0
        // One pass per window can extend the wait at most once; the bound keeps
        // a pathological config (many chained windows) from spinning forever.
        repeat(all.size + 1) {
            val remaining = all.mapNotNull { o ->
                elapsedIn(o, (nowAbs + cursor) % WEEK_MIN)?.let { o.lengthMin - it }
            }.maxOrNull() ?: return cursor.takeIf { it > 0 }
            cursor += remaining
        }
        return cursor
    }

    /**
     * Minutes from now until this window's current-or-next occurrence ends —
     * what a parent's "skip this one" pass is set to, so it lapses by itself
     * rather than needing to be taken back.
     */
    fun minutesUntilEndOfNext(window: TimeWindow, dayOfWeek: Int, minuteOfDay: Int): Int? {
        val nowAbs = weekMinute(dayOfWeek, minuteOfDay)
        return occurrences(listOf(window)).minOfOrNull { o ->
            val elapsed = elapsedIn(o, nowAbs)
            if (elapsed != null) o.lengthMin - elapsed
            else (o.startAbs - nowAbs + WEEK_MIN) % WEEK_MIN + o.lengthMin
        }
    }

    /** Minutes until the next window starts blocking, or null when there are none. */
    fun minutesUntilNextStart(windows: List<TimeWindow>, dayOfWeek: Int, minuteOfDay: Int): Int? {
        val nowAbs = weekMinute(dayOfWeek, minuteOfDay)
        return occurrences(windows)
            .map { (it.startAbs - nowAbs + WEEK_MIN) % WEEK_MIN }
            .minOrNull()
    }
}
