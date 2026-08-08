package io.pickwick.app

import io.pickwick.app.data.Digest
import io.pickwick.app.data.DigestStore
import io.pickwick.app.data.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The weekly digest must never dress lifetime numbers up as the week's: channel
 * minutes only appear once a baseline exists, and a young store labels the
 * shorter span it actually covers. Day math is the other minefield — the window
 * is exactly 7 days ending today, absent history days read as 0, and today's
 * minutes come from the live field, not from history (which excludes today).
 */
class DigestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun payload(
        watchedToday: Int = 0,
        history: List<Pair<String, Int>> = emptyList(),
        topChannels: List<Pair<String, Int>> = emptyList(),
        flagged: List<Stats.AiFlagged> = emptyList()
    ) = Stats.Payload(
        watchedTodayMin = watchedToday,
        budgetTodayMin = null, bonusTodayMin = 0,
        sittingWatchedMin = 0, sittingCapMin = null,
        state = "ok", breakUntil = null,
        nowPlaying = null, recent = emptyList(),
        topChannels = topChannels, history = history,
        watchlistCount = 0, aiFlagged = flagged
    )

    @Test
    fun `week is seven days ending today, crossing month boundaries`() {
        val keys = Digest.weekDayKeys("20260301")
        assertEquals(7, keys.size)
        assertEquals("20260223", keys.first())
        assertEquals("20260301", keys.last())
    }

    @Test
    fun `absent days are zero and today comes from the live field, not history`() {
        val weekly = Digest.assemble(
            payload(
                watchedToday = 25,
                // History spans further back than the window and never has today.
                history = listOf("20260720" to 99, "20260803" to 40, "20260805" to 10)
            ),
            baselines = emptyList(),
            todayKey = "20260808"
        )
        assertEquals(7, weekly.days.size)
        assertEquals("20260802" to 0, weekly.days[0])
        assertEquals("20260803" to 40, weekly.days[1])
        assertEquals("20260805" to 10, weekly.days[3])
        assertEquals("20260808" to 25, weekly.days.last())
        assertEquals(75, weekly.totalMin)
    }

    @Test
    fun `no baseline means no channel numbers, not lifetime totals`() {
        val weekly = Digest.assemble(
            payload(topChannels = listOf("Bluey" to 500)),
            baselines = emptyList(),
            todayKey = "20260808"
        )
        assertTrue(weekly.topChannels.isEmpty())
        assertNull(weekly.channelsSinceDay)
    }

    @Test
    fun `full baseline yields week deltas sorted descending, zero deltas dropped`() {
        val weekly = Digest.assemble(
            payload(topChannels = listOf("Bluey" to 500, "Numberblocks" to 200, "Quiet" to 80)),
            baselines = listOf(
                DigestStore.Row("20260801", mapOf("Bluey" to 450, "Numberblocks" to 100, "Quiet" to 80))
            ),
            todayKey = "20260808"
        )
        assertEquals(listOf("Numberblocks" to 100, "Bluey" to 50), weekly.topChannels)
        // Baseline predates the window, so the numbers count from its first day.
        assertEquals("20260802", weekly.channelsSinceDay)
    }

    @Test
    fun `young store labels the shorter span it covers`() {
        val weekly = Digest.assemble(
            payload(topChannels = listOf("Bluey" to 500)),
            // Oldest row is mid-window: deltas count from the day after it.
            baselines = listOf(
                DigestStore.Row("20260805", mapOf("Bluey" to 480)),
                DigestStore.Row("20260807", mapOf("Bluey" to 490))
            ),
            todayKey = "20260808"
        )
        assertEquals(listOf("Bluey" to 20), weekly.topChannels)
        assertEquals("20260806", weekly.channelsSinceDay)
    }

    @Test
    fun `a channel first watched this week has no baseline entry and counts fully`() {
        val weekly = Digest.assemble(
            payload(topChannels = listOf("NewShow" to 30)),
            baselines = listOf(DigestStore.Row("20260731", mapOf("Bluey" to 100))),
            todayKey = "20260808"
        )
        assertEquals(listOf("NewShow" to 30), weekly.topChannels)
    }

    @Test
    fun `blocked list keeps only this week's holds, newest first`() {
        val start = Digest.weekStartMs("20260808")
        fun flag(at: Long, title: String) =
            Stats.AiFlagged("id-$title", title, "ch", null, "BLOCK", "why", at)
        val weekly = Digest.assemble(
            payload(flagged = listOf(
                flag(start - 1, "old"),
                flag(start + 1_000, "early"),
                flag(start + 500_000, "late")
            )),
            baselines = emptyList(),
            todayKey = "20260808"
        )
        assertEquals(listOf("late", "early"), weekly.blocked.map { it.title })
    }

    @Test
    fun `summary facts carry totals, channels and hold reasons`() {
        val weekly = Digest.assemble(
            payload(
                watchedToday = 10,
                topChannels = listOf("Bluey" to 60),
                flagged = listOf(Stats.AiFlagged(
                    "v1", "Scary compilation", "SomeCh", null, "BLOCK",
                    "Too intense for age 5", Digest.weekStartMs("20260808") + 1
                ))
            ),
            baselines = listOf(DigestStore.Row("20260801", mapOf("Bluey" to 10))),
            todayKey = "20260808"
        )
        val facts = Digest.summaryFacts("Noa", weekly)
        assertTrue(facts.contains("Child: Noa"))
        assertTrue(facts.contains("Total: 10 min"))
        assertTrue(facts.contains("Bluey (50 min)"))
        assertTrue(facts.contains("Too intense for age 5"))
    }

    @Test
    fun `a baseline older than the window is labeled with its true span, not the week`() {
        val weekly = Digest.assemble(
            payload(topChannels = listOf("Bluey" to 500)),
            // Phone last synced two weeks ago — the delta spans since then.
            baselines = listOf(DigestStore.Row("20260725", mapOf("Bluey" to 400))),
            todayKey = "20260808"
        )
        assertEquals(listOf("Bluey" to 100), weekly.topChannels)
        // Predates the window start (20260802) — the UI shows it as "since Jul 26".
        assertEquals("20260726", weekly.channelsSinceDay)
    }

    @Test
    fun `store keys separate kids on a shared device and survive emoji names`() {
        val a = DigestStore.key("tok", "🦊 Noa")
        val b = DigestStore.key("tok", "🐸 Ari")
        val none = DigestStore.key("tok", null)
        assertTrue(a != b && a != none && b != none)
        assertEquals("tok", none)
        // Filename-safe: token is hex, suffix must be too.
        assertTrue(Regex("^tok-p-?[0-9a-f]+$").matches(a))
    }

    // --- DigestStore persistence -------------------------------------------

    @Test
    fun `store round-trips, overwrites same-day rows and trims old ones`() {
        val store = DigestStore(tmp.newFolder())
        store.record("tok", "20260807", mapOf("Bluey" to 5))
        store.record("tok", "20260807", mapOf("Bluey" to 9))
        for (d in 10..25) store.record("tok", "202607$d", mapOf("Bluey" to d))

        val rows = store.load("tok")
        assertEquals(DigestStore.KEEP_DAYS, rows.size)
        // Newest kept, oldest trimmed, same-day overwritten.
        assertEquals("20260807" to mapOf("Bluey" to 9), rows.last().let { it.day to it.channels })
        assertTrue(rows.first().day > "20260710")
        // Devices don't leak into each other.
        assertTrue(store.load("other").isEmpty())
    }

    @Test
    fun `corrupt store file reads as empty, not a crash`() {
        val dir = tmp.newFolder()
        val store = DigestStore(dir)
        java.io.File(dir, "tok.json").writeText("not json")
        assertTrue(store.load("tok").isEmpty())
    }
}
