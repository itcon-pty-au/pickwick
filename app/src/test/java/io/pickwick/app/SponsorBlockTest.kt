package io.pickwick.app

import io.pickwick.app.data.SponsorBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** URL → video-id extraction feeding the SponsorBlock lookup. */
class SponsorBlockTest {

    @Test
    fun `extracts ids from the page-url forms the queue actually carries`() {
        assertEquals(
            "dQw4w9WgXcQ",
            SponsorBlock.videoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            SponsorBlock.videoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            SponsorBlock.videoIdOf("https://youtu.be/dQw4w9WgXcQ?t=10")
        )
    }

    @Test
    fun `local and sideloaded uris have no id — lookup is skipped, not attempted`() {
        assertNull(SponsorBlock.videoIdOf("content://com.android.providers/video/17"))
        assertNull(SponsorBlock.videoIdOf("file:///data/user/0/io.pickwick.app/files/dl/abc.mp4"))
    }

    // --- response parsing ---------------------------------------------------

    private fun body(vararg videos: String) = "[${videos.joinToString(",")}]"

    private fun video(id: String, vararg segments: String) =
        """{"videoID":"$id","segments":[${segments.joinToString(",")}]}"""

    private fun seg(start: Double, end: Double, action: String = "skip") =
        """{"actionType":"$action","segment":[$start,$end]}"""

    @Test
    fun `other videos sharing the hash prefix are ignored`() {
        val segments = SponsorBlock.parse(
            body(video("otherVideo1", seg(0.0, 30.0)), video("myVideo0001", seg(5.0, 10.0))),
            "myVideo0001"
        )
        assertEquals(listOf(SponsorBlock.Segment(5_000, 10_000)), segments)
    }

    @Test
    fun `non-skip actions and sub-second marks are dropped`() {
        val segments = SponsorBlock.parse(
            body(
                video(
                    "myVideo0001",
                    seg(1.0, 60.0, action = "mute"),
                    seg(1.0, 60.0, action = "full"),
                    seg(70.0, 70.5), // sub-second: not worth a visible seek
                    seg(80.0, 90.0)
                )
            ),
            "myVideo0001"
        )
        assertEquals(listOf(SponsorBlock.Segment(80_000, 90_000)), segments)
    }

    @Test
    fun `overlapping and adjacent segments merge into one jump`() {
        val segments = SponsorBlock.parse(
            body(
                video(
                    "myVideo0001",
                    seg(10.0, 20.0),
                    seg(15.0, 30.0), // overlaps the first
                    seg(30.0, 40.0), // touches the merged end exactly
                    seg(50.0, 60.0) // separate
                )
            ),
            "myVideo0001"
        )
        assertEquals(
            listOf(
                SponsorBlock.Segment(10_000, 40_000),
                SponsorBlock.Segment(50_000, 60_000)
            ),
            segments
        )
    }

    @Test
    fun `a contained segment never shrinks the merged stretch`() {
        val segments = SponsorBlock.parse(
            body(video("myVideo0001", seg(10.0, 40.0), seg(15.0, 20.0))),
            "myVideo0001"
        )
        assertEquals(listOf(SponsorBlock.Segment(10_000, 40_000)), segments)
    }

    @Test
    fun `garbage and empty bodies read as no segments`() {
        assertEquals(emptyList<SponsorBlock.Segment>(), SponsorBlock.parse("", "myVideo0001"))
        assertEquals(emptyList<SponsorBlock.Segment>(), SponsorBlock.parse("<html>502</html>", "x"))
        assertEquals(emptyList<SponsorBlock.Segment>(), SponsorBlock.parse("[]", "myVideo0001"))
        assertEquals(
            emptyList<SponsorBlock.Segment>(),
            SponsorBlock.parse("""[{"videoID":"myVideo0001"}]""", "myVideo0001")
        )
    }
}
