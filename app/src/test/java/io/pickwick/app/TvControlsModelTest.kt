package io.pickwick.app

import io.pickwick.app.data.YouTubeRepository
import io.pickwick.app.ui.ScrubAccelerator
import io.pickwick.app.ui.TvButton
import io.pickwick.app.ui.chapterAt
import io.pickwick.app.ui.moved
import io.pickwick.app.ui.previousGoesBack
import io.pickwick.app.ui.scrubTarget
import io.pickwick.app.ui.timeLeftLabel
import io.pickwick.app.ui.tvButtons
import io.pickwick.app.ui.upNextSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvControlsModelTest {

    private val longVideo = 3_600_000L

    @Test
    fun `tapping always moves ten seconds, however fast`() {
        val a = ScrubAccelerator()
        (0L..20L).forEach { assertEquals(10_000L, a.next(it * 40, isRepeat = false, longVideo)) }
    }

    @Test
    fun `holding speeds up with time held, not with key events`() {
        val a = ScrubAccelerator()
        a.next(0, false, longVideo)
        // Twenty repeats a second: most land inside the rate limit.
        val steps = (1L..160L).mapNotNull { n -> a.next(n * 50, true, longVideo)?.let { n * 50 to it } }
        assertTrue(steps.size < 60)
        assertEquals(10_000L, steps.first { it.first < 1_500 }.second)
        assertEquals(30_000L, steps.first { it.first >= 1_500 }.second)
        assertEquals(60_000L, steps.first { it.first >= 3_500 }.second)
        assertEquals(120_000L, steps.last().second)
    }

    @Test
    fun `letting go and tapping drops back to ten seconds`() {
        val a = ScrubAccelerator()
        a.next(0, false, longVideo)
        assertEquals(120_000L, a.next(7_000, true, longVideo))
        assertEquals(10_000L, a.next(7_500, false, longVideo))
    }

    @Test
    fun `a short video's hold can't jump most of it at once`() {
        val a = ScrubAccelerator()
        a.next(0, false, 180_000)
        assertEquals(10_000L, a.next(7_000, true, 180_000))
        val b = ScrubAccelerator()
        b.next(0, false, 1_500_000)
        assertEquals(60_000L, b.next(7_000, true, 1_500_000))
    }

    @Test
    fun `the target stays inside the video and short of the end`() {
        assertEquals(0L, scrubTarget(4_000, -1, 10_000, 60_000))
        assertEquals(59_000L, scrubTarget(58_000, 1, 10_000, 60_000))
        assertEquals(0L, scrubTarget(0, 1, 10_000, 500))
    }

    @Test
    fun `a lone video has no previous or next, and tracks show only with a choice`() {
        assertEquals(listOf(TvButton.PlayPause), tvButtons(1, 1, 0, false))
        assertEquals(
            listOf(
                TvButton.Previous, TvButton.PlayPause, TvButton.Next,
                TvButton.Audio, TvButton.Subtitles, TvButton.Quality
            ),
            tvButtons(5, 2, 1, true)
        )
    }

    @Test
    fun `focus stops at the ends and recovers when its button disappears`() {
        val row = listOf(TvButton.PlayPause, TvButton.Subtitles)
        assertEquals(TvButton.Subtitles, row.moved(TvButton.PlayPause, 1))
        assertEquals(TvButton.Subtitles, row.moved(TvButton.Subtitles, 1))
        assertEquals(TvButton.PlayPause, row.moved(TvButton.PlayPause, -1))
        assertEquals(TvButton.PlayPause, row.moved(TvButton.Quality, 1))
    }

    @Test
    fun `previous restarts unless the video has barely begun`() {
        assertFalse(previousGoesBack(0, 1_000))
        assertFalse(previousGoesBack(3, 60_000))
        assertTrue(previousGoesBack(3, 2_000))
    }

    @Test
    fun `chapters name the section the position falls in`() {
        val chapters = listOf(
            YouTubeRepository.Chapter(0, "Intro"),
            YouTubeRepository.Chapter(60_000, "Middle")
        )
        assertEquals("Intro", chapterAt(chapters, 59_999))
        assertEquals("Middle", chapterAt(chapters, 60_000))
        assertNull(chapterAt(emptyList(), 5_000))
        assertNull(chapterAt(listOf(YouTubeRepository.Chapter(30_000, "Late")), 5_000))
    }

    @Test
    fun `up next shows only near the end of a playing video with a next one`() {
        assertEquals(8L, upNextSeconds(true, true, 7_500))
        assertEquals(10L, upNextSeconds(true, true, 10_000))
        assertNull(upNextSeconds(true, true, 10_001))
        assertNull(upNextSeconds(false, true, 5_000))
        assertNull(upNextSeconds(true, false, 5_000))
        assertNull(upNextSeconds(true, true, 0))
    }

    @Test
    fun `the time chip rounds up and reads naturally`() {
        assertEquals("Under a minute left", timeLeftLabel(30_000))
        assertEquals("2 min left", timeLeftLabel(60_001))
        assertEquals("24 min left", timeLeftLabel(24 * 60_000L))
        assertEquals("2 hr left", timeLeftLabel(120 * 60_000L))
        assertEquals("1 hr 40 min left", timeLeftLabel(100 * 60_000L))
    }

    @Test
    fun `storyboard frames map to the right sheet and cell`() {
        val board = YouTubeRepository.Storyboard(
            urls = listOf("a", "b"), frameWidth = 160, frameHeight = 90,
            framesPerX = 5, framesPerY = 5, totalFrames = 40, msPerFrame = 2_000
        )
        assertEquals(YouTubeRepository.Storyboard.Frame("a", 0, 0), board.frameAt(0))
        assertEquals(YouTubeRepository.Storyboard.Frame("a", 160, 90), board.frameAt(12_000))
        assertEquals(YouTubeRepository.Storyboard.Frame("b", 0, 0), board.frameAt(50_000))
        // Past the last frame: holds on it instead of running off the sheet.
        assertEquals(YouTubeRepository.Storyboard.Frame("b", 640, 180), board.frameAt(9_999_999))
    }

    @Test
    fun `a storyboard missing a sheet has no frame there`() {
        val board = YouTubeRepository.Storyboard(listOf("a"), 160, 90, 5, 5, 40, 2_000)
        assertNull(board.frameAt(50_000))
    }
}
