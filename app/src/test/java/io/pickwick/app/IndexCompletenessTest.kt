package io.pickwick.app

import io.pickwick.app.data.ChannelIndex
import io.pickwick.app.data.IndexCrawler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The "fully indexed" flag drives whether a channel is ever crawled again, so
 * both directions of a wrong answer hurt: a flag that flips off every run
 * re-probes small channels forever (the decay bug), one that sticks on hides
 * history from search. These pin the two guards — the bounded exhaustion
 * probe and the harvest-evidence recovery — against a real file-backed index.
 */
class IndexCompletenessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newIndex() = ChannelIndex(File(tmp.root, "search-index"))

    private fun vids(vararg ids: String) = ids.map {
        ChannelIndex.IndexedVideo(
            videoId = it, title = "t $it", channelName = "c",
            thumbnailUrl = null, durationSeconds = 60, sourceId = "s"
        )
    }

    @Test
    fun `third consecutive parked probe accepts exhaustion, earlier ones do not`() {
        val index = newIndex()
        index.addVideos("s", vids("a", "b"), complete = false)

        assertFalse(IndexCrawler.recordParkedProbe(index, "s"))
        assertFalse(index.state("s")!!.complete)
        assertFalse(IndexCrawler.recordParkedProbe(index, "s"))
        assertFalse(index.state("s")!!.complete)
        assertTrue(IndexCrawler.recordParkedProbe(index, "s"))
        assertTrue(index.state("s")!!.complete)
        // Accepting exhaustion consumed the streak.
        assertEquals(0, index.loadProbeCount("s"))
    }

    @Test
    fun `a real continuation between probes resets the streak`() {
        val index = newIndex()
        index.addVideos("s", vids("a"), complete = false)

        IndexCrawler.recordParkedProbe(index, "s")
        IndexCrawler.recordParkedProbe(index, "s")
        // crawlOnce drops the counter when a probe yields a usable continuation.
        index.dropProbeCount("s")
        assertFalse(IndexCrawler.recordParkedProbe(index, "s"))
        assertFalse(IndexCrawler.recordParkedProbe(index, "s"))
        assertFalse(index.state("s")!!.complete)
        assertTrue(IndexCrawler.recordParkedProbe(index, "s"))
    }

    @Test
    fun `probe streak survives a process death and the park's own forgetCursor`() {
        val index = newIndex()
        index.addVideos("s", vids("a"), complete = false)
        IndexCrawler.recordParkedProbe(index, "s")
        IndexCrawler.recordParkedProbe(index, "s")
        // The park branch forgets the cursor on every probe — that must not
        // take the streak with it, or the third probe never arrives.
        index.dropCursor("s")

        val reread = newIndex() // fresh instance = the next worker run's process
        assertEquals(2, reread.loadProbeCount("s"))
        assertTrue(IndexCrawler.recordParkedProbe(reread, "s"))
    }

    @Test
    fun `append harvest with unknown videos clears complete`() {
        val index = newIndex()
        // Crawler finished the walk: explicit complete on the append path.
        index.addVideos("s", vids("a", "b"), complete = true, append = true)
        assertTrue(index.state("s")!!.complete)

        // A kid scrolled deeper than the crawl and surfaced unknown history.
        index.addVideos("s", vids("b", "old1", "old2"), append = true)
        assertFalse(index.state("s")!!.complete)
        assertEquals(4, index.state("s")!!.count)
    }

    @Test
    fun `page-1 prepend with new videos keeps complete`() {
        val index = newIndex()
        index.addVideos("s", vids("a", "b"), complete = true, append = true)

        // New upload at the top: normal forward growth, not missing history.
        index.addVideos("s", vids("new", "a"))
        assertTrue(index.state("s")!!.complete)
        assertEquals(3, index.state("s")!!.count)
    }

    @Test
    fun `crawler append passing complete explicitly stays complete`() {
        val index = newIndex()
        index.addVideos("s", vids("a", "b"), complete = false)
        // Last crawl page: fresh videos AND complete=true in one call.
        index.addVideos("s", vids("c", "d"), complete = true, append = true)
        assertTrue(index.state("s")!!.complete)
        assertEquals(4, index.state("s")!!.count)
    }

    @Test
    fun `dropSource drops the probe streak too`() {
        val index = newIndex()
        index.addVideos("s", vids("a"), complete = false)
        IndexCrawler.recordParkedProbe(index, "s")
        IndexCrawler.recordParkedProbe(index, "s")

        index.dropSource("s")
        assertEquals(0, index.loadProbeCount("s"))
        // Re-added later (whitelist round trip): the old streak must not
        // count against the fresh crawl.
        index.addVideos("s", vids("a"), complete = false)
        assertFalse(IndexCrawler.recordParkedProbe(index, "s"))
    }
}
