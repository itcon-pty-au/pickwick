package io.pickwick.app

import io.pickwick.app.data.StreamChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamChunkerTest {

    @Test
    fun `googlevideo hosts match, others do not`() {
        assertTrue(StreamChunker.isGoogleVideo("rr3---sn-abc.googlevideo.com"))
        assertTrue(StreamChunker.isGoogleVideo("googlevideo.com"))
        assertFalse(StreamChunker.isGoogleVideo("evilgooglevideo.com"))
        assertFalse(StreamChunker.isGoogleVideo("youtube.com"))
        assertFalse(StreamChunker.isGoogleVideo(null))
    }

    @Test
    fun `clen is read from the query`() {
        assertEquals(
            123456L,
            StreamChunker.clenOf("https://r.googlevideo.com/videoplayback?itag=22&clen=123456&x=1")
        )
        assertNull(StreamChunker.clenOf("https://r.googlevideo.com/videoplayback?itag=22"))
        assertNull(StreamChunker.clenOf("https://r.googlevideo.com/videoplayback?clen=0"))
        assertNull(StreamChunker.clenOf("https://r.googlevideo.com/videoplayback?clen=abc"))
        // No query string at all.
        assertNull(StreamChunker.clenOf("https://r.googlevideo.com/videoplayback"))
    }

    @Test
    fun `chunkUrl appends range and rn, preserving other params`() {
        assertEquals(
            "https://h/vp?itag=22&clen=100&range=0-99&rn=1",
            StreamChunker.chunkUrl("https://h/vp?itag=22&clen=100", 0, 99, 1)
        )
    }

    @Test
    fun `chunkUrl replaces stale range and rn params`() {
        assertEquals(
            "https://h/vp?itag=22&range=50-149&rn=2",
            StreamChunker.chunkUrl("https://h/vp?range=0-49&rn=1&itag=22", 50, 149, 2)
        )
    }

    @Test
    fun `chunkUrl handles a bare url without a query`() {
        assertEquals(
            "https://h/vp?range=0-9&rn=0",
            StreamChunker.chunkUrl("https://h/vp", 0, 9, 0)
        )
    }
}
