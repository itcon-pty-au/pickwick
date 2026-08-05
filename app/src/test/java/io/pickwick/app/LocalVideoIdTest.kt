package io.pickwick.app

import io.pickwick.app.data.LocalLibrary
import io.pickwick.app.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sideloaded videos carry synthetic pickwick://local/<hash> URLs; videoId is
 * the key into blocking, downloads and thumbnails, so the derivation must hold
 * for both URL families.
 */
class LocalVideoIdTest {

    private fun video(url: String) = Video(url, "t", "c", null, 0L)

    @Test
    fun `youtube urls derive the v parameter`() {
        assertEquals(
            "dQw4w9WgXcQ",
            video("https://www.youtube.com/watch?v=dQw4w9WgXcQ").videoId
        )
    }

    @Test
    fun `local urls expose the synthetic id`() {
        val id = LocalLibrary.idFor("content://com.android.externalstorage.documents/tree/x")
        assertEquals(id, video(LocalLibrary.URL_PREFIX + id).videoId)
    }

    @Test
    fun `synthetic ids are stable and 16 hex chars`() {
        val a = LocalLibrary.idFor("content://provider/doc/A")
        assertEquals(a, LocalLibrary.idFor("content://provider/doc/A"))
        assertNotEquals(a, LocalLibrary.idFor("content://provider/doc/B"))
        assertEquals(16, a.length) // can't collide with an 11-char YouTube id
        assert(a.all { it in "0123456789abcdef" })
    }

    @Test
    fun `other urls have no id`() {
        assertNull(video("https://example.com/clip.mp4").videoId)
    }
}
