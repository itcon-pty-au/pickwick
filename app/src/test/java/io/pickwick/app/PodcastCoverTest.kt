package io.pickwick.app

import io.pickwick.app.data.PodcastChannel
import io.pickwick.app.data.PodcastLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PodcastCoverTest {

    @get:Rule val folder = TemporaryFolder()

    private val feedId = "feed-1"
    private val library by lazy { PodcastLibrary(folder.root) }
    private fun channel(image: String?) = PodcastChannel("Show", image, emptyList())
    private fun filesEnding(suffix: String) = folder.root.listFiles().orEmpty().filter { it.name.endsWith(suffix) }

    @Test fun `unknown feed has no cover and no listing`() {
        assertNull(library.cover(feedId))
        assertFalse(library.hasListing(feedId))
    }

    @Test fun `saving a listing makes its cover readable`() {
        library.save(feedId, channel("https://example.com/a.jpg"))
        assertEquals("https://example.com/a.jpg", library.cover(feedId))
        assertTrue(library.hasListing(feedId))
    }

    @Test fun `a new save replaces the cover`() {
        library.save(feedId, channel("https://example.com/a.jpg"))
        library.save(feedId, channel("https://example.com/b.jpg"))
        assertEquals("https://example.com/b.jpg", library.cover(feedId))
    }

    @Test fun `a show with no art reads as no cover`() {
        library.save(feedId, channel(null))
        assertNull(library.cover(feedId))
        assertTrue(library.hasListing(feedId))
    }

    /** A device upgrading from a build that only wrote the listing file. */
    @Test fun `a listing saved before cover files existed still yields its cover`() {
        library.save(feedId, channel("https://example.com/a.jpg"))
        filesEnding(".cover").forEach(File::delete)

        assertEquals("https://example.com/a.jpg", library.cover(feedId))
        assertEquals(1, filesEnding(".cover").size)
    }

    /** The point of the file: home must not have to parse the listing on every refresh. */
    @Test fun `the cover is read without parsing the listing`() {
        library.save(feedId, channel("https://example.com/a.jpg"))
        filesEnding(".json").single().writeText("not json")

        assertEquals("https://example.com/a.jpg", library.cover(feedId))
    }

    @Test fun `dropping a feed removes its cover`() {
        library.save(feedId, channel("https://example.com/a.jpg"))
        library.drop(feedId)

        assertNull(library.cover(feedId))
        assertFalse(library.hasListing(feedId))
    }
}
