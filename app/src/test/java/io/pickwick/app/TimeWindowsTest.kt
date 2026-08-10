package io.pickwick.app

import io.pickwick.app.data.ALL_DAYS
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.Limits
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.TimeWindow
import io.pickwick.app.data.TimeWindows
import io.pickwick.app.data.WEEKDAYS
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TimeWindowsTest {

    private val bedtime = TimeWindow(
        id = "bedtime", label = "Bedtime", startMin = 19 * 60 + 30, endMin = 7 * 60
    )
    private val school = TimeWindow(
        id = "school", label = "School hours",
        startMin = 8 * 60 + 30, endMin = 15 * 60, days = WEEKDAYS
    )

    private val MON = Calendar.MONDAY
    private val FRI = Calendar.FRIDAY
    private val SAT = Calendar.SATURDAY
    private val SUN = Calendar.SUNDAY

    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun `an every-day bedtime blocks the evening and the small hours`() {
        val ws = listOf(bedtime)
        assertEquals("Bedtime", TimeWindows.activeAt(ws, MON, at(20))?.label)
        assertEquals("Bedtime", TimeWindows.activeAt(ws, MON, at(2))?.label)
        assertNull(TimeWindows.activeAt(ws, MON, at(17)))
    }

    @Test
    fun `a window is judged by the day it starts, so Friday night survives midnight`() {
        // Saturday-only bedtime: 00:30 on Saturday belongs to Friday's night,
        // which is not selected, so it must not block.
        val saturdayOnly = bedtime.copy(days = setOf(SAT))
        assertNull(TimeWindows.activeAt(listOf(saturdayOnly), SAT, at(0, 30)))
        assertEquals("Bedtime", TimeWindows.activeAt(listOf(saturdayOnly), SAT, at(23))?.label)
        // ...and Saturday's own night runs on into Sunday morning.
        assertEquals("Bedtime", TimeWindows.activeAt(listOf(saturdayOnly), SUN, at(3))?.label)
    }

    @Test
    fun `weekday school hours skip the weekend`() {
        assertEquals("School hours", TimeWindows.activeAt(listOf(school), MON, at(10))?.label)
        assertNull(TimeWindows.activeAt(listOf(school), SAT, at(10)))
    }

    @Test
    fun `blocked time counts to when nothing blocks any more`() {
        // 10:00 Monday: school runs to 15:00, so five hours.
        assertEquals(5 * 60, TimeWindows.blockedForMin(listOf(school, bedtime), MON, at(10)))
        assertNull(TimeWindows.blockedForMin(listOf(school, bedtime), MON, at(16)))
    }

    @Test
    fun `overlapping windows chain into a single wait`() {
        // Homework 14:00-20:00 overlaps school's tail and bedtime's start, so a
        // kid blocked at 10:00 Monday waits all the way to the 07:00 reopening.
        val homework = TimeWindow(
            id = "hw", label = "Homework", startMin = 14 * 60, endMin = 20 * 60, days = WEEKDAYS
        )
        val mins = TimeWindows.blockedForMin(listOf(school, homework, bedtime), MON, at(10))
        assertEquals(21 * 60, mins) // 10:00 Monday → 07:00 Tuesday
    }

    @Test
    fun `next start looks past the end of the week`() {
        // Friday 16:00 with weekday-only school: the next start is Monday 08:30.
        assertEquals(
            64 * 60 + 30, // Fri 16:00 → Mon 08:30
            TimeWindows.minutesUntilNextStart(listOf(school), FRI, at(16))
        )
    }

    @Test
    fun `a pass runs to the end of the occurrence it skips`() {
        // Mid-window: what's left of it. Before it: the wait plus its length.
        assertEquals(5 * 60, TimeWindows.minutesUntilEndOfNext(school, MON, at(10)))
        // An hour before it opens: that hour, plus the window's 6h30 length.
        assertEquals(7 * 60 + 30, TimeWindows.minutesUntilEndOfNext(school, MON, at(8, 30) - 60))
    }

    @Test
    fun `a zero-length window blocks nothing rather than everything`() {
        val zero = TimeWindow(id = "z", label = "Odd", startMin = 600, endMin = 600)
        assertNull(TimeWindows.activeAt(listOf(zero), MON, at(10)))
    }

    // --- config round-trip -------------------------------------------------

    private fun config(limits: Limits) = Whitelist(
        sources = listOf(
            WhitelistEntry("UCa", "https://www.youtube.com/channel/UCa", null, SourceKind.CHANNEL)
        ),
        blockedVideoIds = emptySet(),
        limits = limits
    )

    @Test
    fun `windows survive a JSON round-trip`() {
        val limits = Limits(sessionMinutes = 30, windows = listOf(bedtime, school))
        val parsed = ConfigStore.fromJson(ConfigStore.toJson(config(limits)))
        assertEquals(limits.windows, parsed.limits.windows)
    }

    @Test
    fun `a pre-windows config migrates its bedtime into the list`() {
        val legacy = """
            {"entries":[],"blocked":[],
             "limits":{"session":30,"bedtimeStart":1170,"bedtimeEnd":420}}
        """.trimIndent()
        val parsed = ConfigStore.fromJson(legacy)
        assertEquals(1, parsed.limits.windows.size)
        assertEquals(1170, parsed.limits.windows[0].startMin)
        assertEquals(ALL_DAYS, parsed.limits.windows[0].days)
    }

    @Test
    fun `a migrated bedtime keeps its old fingerprint and its old JSON keys`() {
        // The one shape older builds could express must hash the same after the
        // upgrade, or every family re-pushes their config for no reason.
        val migrated = config(Limits(sessionMinutes = 30, windows = listOf(bedtime)))
        val json = ConfigStore.toJson(migrated)
        assertTrue(json.contains("\"bedtimeStart\": 1170"))
        // The same family as an old build would have written it.
        val legacy = ConfigStore.fromJson(
            """
            {"entries":[{"id":"UCa","url":"https://www.youtube.com/channel/UCa","kind":"CHANNEL"}],
             "blocked":[],
             "limits":{"session":30,"bedtimeStart":1170,"bedtimeEnd":420}}
            """.trimIndent()
        )
        assertEquals(
            ConfigStore.fingerprint(legacy),
            ConfigStore.fingerprint(migrated)
        )
        // A richer schedule can't be expressed the old way, so it must differ.
        val richer = config(Limits(sessionMinutes = 30, windows = listOf(bedtime, school)))
        assertTrue(ConfigStore.fingerprint(richer) != ConfigStore.fingerprint(migrated))
    }

    @Test
    fun `a break skip round-trips and changes the fingerprint`() {
        val plain = config(Limits(sessionMinutes = 30, breakMinutes = 30))
        val skipped = config(
            Limits(sessionMinutes = 30, breakMinutes = 30, breakPassUntilMillis = 1_800_000_000_000L)
        )
        // Same reason as the window pass: the reconcile only re-pushes on a
        // mismatch, so a skip that didn't move the hash would never reach a
        // sleeping TV.
        assertTrue(ConfigStore.fingerprint(plain) != ConfigStore.fingerprint(skipped))
        val parsed = ConfigStore.fromJson(ConfigStore.toJson(skipped))
        assertEquals(
            1_800_000_000_000L,
            parsed.limits.breakPassUntilMillis
        )
    }

    @Test
    fun `allow-listening round-trips and moves the fingerprint`() {
        // Same reason as a pass: the reconcile only re-pushes on a mismatch, so
        // ticking the box has to move the hash or the kid's phone never hears
        // that bedtime got its exception.
        val plain = config(Limits(windows = listOf(bedtime)))
        val listening = config(Limits(windows = listOf(bedtime.copy(allowListening = true))))
        assertTrue(ConfigStore.fingerprint(plain) != ConfigStore.fingerprint(listening))
        val parsed = ConfigStore.fromJson(ConfigStore.toJson(listening))
        assertTrue(parsed.limits.windows.single().allowListening)
    }

    @Test
    fun `a window without the box keeps blocking everything after an upgrade`() {
        // The field is new, so every config already out there lacks it: absent
        // must read as the plain block, never as a loosened bedtime.
        val old = ConfigStore.fromJson(
            """
            {"entries":[],"blocked":[],
             "limits":{"windows":[{"id":"w1","label":"Bedtime","start":1170,"end":420}]}}
            """.trimIndent()
        )
        assertFalse(old.limits.windows.single().allowListening)
    }

    @Test
    fun `a pass changes the fingerprint so a sleeping device still hears about it`() {
        val plain = config(Limits(windows = listOf(school)))
        val passed = config(Limits(windows = listOf(school.copy(passUntilMillis = 1_800_000_000_000L))))
        assertTrue(ConfigStore.fingerprint(plain) != ConfigStore.fingerprint(passed))
    }
}
