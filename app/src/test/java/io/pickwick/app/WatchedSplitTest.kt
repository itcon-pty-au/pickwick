package io.pickwick.app

import io.pickwick.app.data.Video
import io.pickwick.app.ui.VideoItem
import io.pickwick.app.ui.splitWatched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unwatched-first ordering for a channel grid. The kid's complaint this
 * answers is scrolling past a wall of already-seen videos, so the invariant
 * that matters is that feed order survives inside each half.
 */
class WatchedSplitTest {

    private fun item(id: String, progress: Float?) = VideoItem(
        Video("https://youtu.be/$id", "Video $id", "A channel", null, 100),
        progress
    )

    private fun urls(items: List<VideoItem>) = items.map { it.video.url.substringAfterLast('/') }

    @Test
    fun `unwatched keep feed order and finished go last`() {
        val items = listOf(
            item("a", 1f), item("b", null), item("c", 1f), item("d", 0.3f), item("e", null)
        )
        val finished = setOf("https://youtu.be/a", "https://youtu.be/c")
        val (fresh, watched) = splitWatched(items) { it in finished }
        // b, d, e keep the order the channel handed us — nothing is re-ranked,
        // only moved down.
        assertEquals(listOf("b", "d", "e"), urls(fresh))
        assertEquals(listOf("a", "c"), urls(watched))
    }

    @Test
    fun `half-watched stays with the unwatched`() {
        // It's something to carry on with, not something seen — and the home
        // screen's "Keep watching" row treats it the same way.
        val items = listOf(item("a", 0.5f), item("b", null))
        val (fresh, watched) = splitWatched(items) { false }
        assertEquals(listOf("a", "b"), urls(fresh))
        assertTrue(watched.isEmpty())
    }

    @Test
    fun `the snapshot rules, not the live progress`() {
        // A video finished during this visit still carries progress 1f, but the
        // snapshot says it was unwatched on arrival — so it must stay put under
        // the kid's thumb rather than slide to the shelf while they look at it.
        val items = listOf(item("a", null), item("b", 1f), item("c", null))
        val (fresh, watched) = splitWatched(items) { false }
        assertEquals(listOf("a", "b", "c"), urls(fresh))
        assertTrue(watched.isEmpty())
    }

    @Test
    fun `an all-watched channel empties the grid, not the shelf`() {
        val items = listOf(item("a", 1f), item("b", 1f))
        val (fresh, watched) = splitWatched(items) { true }
        assertTrue(fresh.isEmpty())
        assertEquals(listOf("a", "b"), urls(watched))
    }

    @Test
    fun `nothing is dropped`() {
        val items = (1..20).map { item("v$it", if (it % 3 == 0) 1f else null) }
        val finished = items.filter { it.progress == 1f }.map { it.video.url }.toSet()
        val (fresh, watched) = splitWatched(items) { it in finished }
        assertEquals(items.size, fresh.size + watched.size)
        assertEquals(items.toSet(), (fresh + watched).toSet())
    }
}
