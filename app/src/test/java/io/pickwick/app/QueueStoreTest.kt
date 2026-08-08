package io.pickwick.app

import io.pickwick.app.data.QueueStore
import io.pickwick.app.data.Source
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Video
import io.pickwick.app.data.queuePercents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The queue is the kid's plan for the sitting — order is the whole point, and
 * items must only vanish when a video truly finished. These tests pin the
 * ordering, the refusal semantics (duplicate/cap adds return false so badges
 * don't lie), and the per-item drain resolution that keeps a cross-channel
 * queue from billing every video at the first channel's rate.
 */
class QueueStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storeFile() = File(tmp.root, "queue.tsv")

    private fun video(n: Int, channel: String = "Stories") =
        Video("https://youtube.com/watch?v=vid$n", "Video $n", channel, null, 60L * n)

    @Test
    fun `queue order survives a reload through a fresh instance`() {
        val store = QueueStore(storeFile())
        store.add(video(1)); store.add(video(2)); store.add(video(3))
        val reread = QueueStore(storeFile()).load()
        assertEquals(listOf("Video 1", "Video 2", "Video 3"), reread.map { it.title })
    }

    @Test
    fun `add appends to the end and a duplicate add is a no-op`() {
        val store = QueueStore(storeFile())
        assertTrue(store.add(video(1)))
        assertTrue(store.add(video(2)))
        // Re-adding the first video must not move it to the end either.
        assertFalse(store.add(video(1)))
        assertEquals(listOf("Video 1", "Video 2"), store.load().map { it.title })
    }

    @Test
    fun `add refuses past the cap`() {
        val store = QueueStore(storeFile())
        repeat(QueueStore.MAX_ITEMS) { i -> assertTrue(store.add(video(i))) }
        assertFalse(store.add(video(QueueStore.MAX_ITEMS)))
        assertEquals(QueueStore.MAX_ITEMS, store.load().size)
    }

    @Test
    fun `remove drops exactly the finished item and keeps order`() {
        val store = QueueStore(storeFile())
        store.add(video(1)); store.add(video(2)); store.add(video(3))
        store.remove(video(2).url)
        assertEquals(listOf("Video 1", "Video 3"), store.load().map { it.title })
    }

    @Test
    fun `move clamps at the ends and swaps neighbours`() {
        val list = listOf(video(1), video(2), video(3))
        // Pure helper first: the store's move() is just load → moved → save.
        assertEquals(
            listOf("Video 2", "Video 1", "Video 3"),
            QueueStore.moved(list, video(2).url, -1).map { it.title }
        )
        assertEquals(list, QueueStore.moved(list, video(1).url, -1))
        assertEquals(list, QueueStore.moved(list, video(3).url, +1))
        assertEquals(list, QueueStore.moved(list, "https://unknown", +1))

        val store = QueueStore(storeFile())
        list.forEach { store.add(it) }
        store.move(video(3).url, -1)
        assertEquals(listOf("Video 1", "Video 3", "Video 2"), store.load().map { it.title })
    }

    @Test
    fun `tabs and newlines in titles never corrupt the row`() {
        val store = QueueStore(storeFile())
        store.add(Video("https://youtube.com/watch?v=weird", "Tab\there\nand break", "Ch\tannel", null, 5L))
        store.add(video(2))
        val reread = QueueStore(storeFile()).load()
        assertEquals(2, reread.size)
        assertEquals("Tab here and break", reread[0].title)
        assertEquals("Ch annel", reread[0].channelName)
    }

    @Test
    fun `a corrupt line is skipped, not fatal`() {
        val store = QueueStore(storeFile())
        store.add(video(1))
        storeFile().appendText("\nnot-enough\tcolumns")
        assertEquals(listOf("Video 1"), store.load().map { it.title })
    }

    @Test
    fun `queuePercents resolves each item by its own channel, defaulting unknown to 100`() {
        val channels = listOf(
            Source("a", "u", "Stories", null, SourceKind.CHANNEL, timeMultiplierPercent = 50),
            Source("b", "u", "Cartoons", null, SourceKind.CHANNEL, timeMultiplierPercent = 150)
        )
        val queue = listOf(video(1, "Stories"), video(2, "Cartoons"), video(3, "Gone Channel"))
        // One flat rate here would bill the cartoon at story prices (or vice
        // versa) — the money-accounting bug this helper exists to prevent.
        assertEquals(listOf(50, 150, 100), queuePercents(queue, channels))
    }
}
