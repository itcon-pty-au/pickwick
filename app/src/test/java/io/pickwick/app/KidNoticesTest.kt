package io.pickwick.app

import io.pickwick.app.data.ALL_DAYS
import io.pickwick.app.data.KidNotices
import io.pickwick.app.data.Limits
import io.pickwick.app.data.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a pushed config tells the kid. The cost of a false positive is a pill
 * every time a parent adds a channel, so silence is the default and only the
 * rules themselves — never the passes that lapse on their own — speak.
 */
class KidNoticesTest {

    private val now = 1_700_000_000_000L
    private val rules = Limits(sessionMinutes = 30, weekdaySessions = 3, breakMinutes = 30)

    private fun change(before: Limits, after: Limits, remaining: Int? = 45) =
        KidNotices.configChange(before, after, remaining, now)

    @Test
    fun `an unchanged config says nothing`() {
        assertNull(change(rules, rules))
        // The reconcile sweep re-pushes byte-identical configs; a family that
        // adds a channel must not set the TV talking.
        assertNull(change(Limits(), Limits()))
    }

    @Test
    fun `a rules edit names the minutes left`() {
        val after = rules.copy(sessionMinutes = 45)
        assertEquals("Screen time updated — 45 minutes left today ⏰", change(rules, after))
    }

    @Test
    fun `no minute budget means no invented number`() {
        val after = rules.copy(weekdaySessions = null)
        assertEquals("Screen time updated — 45 minutes left today ⏰", change(rules, after))
        assertEquals("Screen time updated ⏰", change(rules, after, remaining = null))
    }

    @Test
    fun `a bedtime edit counts as a rules change`() {
        val bedtime = TimeWindow("w1", "Bedtime", 19 * 60 + 30, 7 * 60, ALL_DAYS)
        val before = rules.copy(windows = listOf(bedtime))
        val after = rules.copy(windows = listOf(bedtime.copy(startMin = 20 * 60 + 30)))
        assertTrue(change(before, after)!!.startsWith("Screen time updated"))
        // Removing the last window is a change too, in the other direction.
        assertTrue(change(before, rules)!!.startsWith("Screen time updated"))
    }

    @Test
    fun `skip passes stay silent`() {
        val bedtime = TimeWindow("w1", "Bedtime", 19 * 60 + 30, 7 * 60, ALL_DAYS)
        val before = rules.copy(windows = listOf(bedtime))
        // "Skip tonight's bedtime" and "skip the next break" are reprieves the
        // kid meets on their own; announcing one invites the argument the
        // parent was avoiding.
        assertNull(change(before, before.copy(windows = listOf(bedtime.copy(passUntilMillis = now + 3_600_000)))))
        assertNull(change(before, before.copy(breakPassUntilMillis = now + 3_600_000)))
    }

    @Test
    fun `pausing and resuming both announce themselves`() {
        val paused = rules.copy(pausedUntilMillis = now + 3_600_000)
        assertEquals("A parent paused screen time for today 💛", change(rules, paused))
        assertEquals("Screen time is back on — 45 minutes left today 🎉", change(paused, rules))
    }

    @Test
    fun `a pause that lapsed at midnight is not news`() {
        // Still on file until the parent's next edit; rediscovering it the
        // next morning must not read as a fresh timeout.
        val stale = rules.copy(pausedUntilMillis = now - 3_600_000)
        assertNull(change(rules, stale))
        assertNull(change(stale, rules))
    }

    @Test
    fun `a pause pushed alongside a rules edit reports the pause`() {
        val after = rules.copy(sessionMinutes = 45, pausedUntilMillis = now + 3_600_000)
        assertEquals("A parent paused screen time for today 💛", change(rules, after))
    }

    @Test
    fun `grants read as a gift, and one minute is singular`() {
        assertEquals("You got 15 more minutes! 🎉", KidNotices.grant(15))
        assertEquals("You got 1 more minute! 🎉", KidNotices.grant(1))
        assertEquals("Screen time updated — 1 minute left today ⏰",
            change(rules, rules.copy(sessionMinutes = 45), remaining = 1))
    }
}
