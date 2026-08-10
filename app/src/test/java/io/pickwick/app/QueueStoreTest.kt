package io.pickwick.app

import io.pickwick.app.data.QueueStore
import io.pickwick.app.data.QueuedVideo
import io.pickwick.app.data.Source
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Video
import io.pickwick.app.data.WatchProgress
import io.pickwick.app.data.finishedSinceQueued
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
            QueueStore.moved(list, video(2).url, -1, Video::url).map { it.title }
        )
        assertEquals(list, QueueStore.moved(list, video(1).url, -1, Video::url))
        assertEquals(list, QueueStore.moved(list, video(3).url, +1, Video::url))
        assertEquals(list, QueueStore.moved(list, "https://unknown", +1, Video::url))

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
    fun `line-up timestamps survive a reload and a reorder`() {
        val store = QueueStore(storeFile())
        store.add(video(1), addedAt = 1_000L)
        store.add(video(2), addedAt = 2_000L)
        store.move(video(2).url, -1)
        val reread = QueueStore(storeFile()).entries()
        assertEquals(listOf(2_000L, 1_000L), reread.map { it.addedAt })
    }

    @Test
    fun `a row written before timestamps existed reads as addedAt zero`() {
        // Upgrading a family's TV must not resurrect everything already watched:
        // 0 means "queued before time began", so any past finish still drains it.
        storeFile().writeText("https://youtube.com/watch?v=old\tOld\tStories\t\t60")
        val entry = QueueStore(storeFile()).entries().single()
        assertEquals("Old", entry.video.title)
        assertEquals(0L, entry.addedAt)
        assertTrue(entry.finishedSinceQueued(WatchProgress(60_000, 60_000, 5_000L)))
    }

    @Test
    fun `a video watched before it was queued stays in the lineup`() {
        val entry = QueuedVideo(video(1), addedAt = 10_000L)
        // Finished last week, queued tonight: a deliberate rewatch.
        assertFalse(entry.finishedSinceQueued(WatchProgress(60_000, 60_000, 9_000L)))
        // Finished during this sitting: drain it.
        assertTrue(entry.finishedSinceQueued(WatchProgress(60_000, 60_000, 11_000L)))
        // Part-watched since queuing, and never watched at all: both stay.
        assertFalse(entry.finishedSinceQueued(WatchProgress(30_000, 60_000, 11_000L)))
        assertFalse(entry.finishedSinceQueued(null))
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
