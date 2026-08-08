package io.pickwick.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ChannelIndex merge is file-backed and Android-dependent, so this tests
 * the pure merge rule directly: count must always equal the deduped total,
 * whether deltas prepend or the crawl appends.
 */
class ChannelIndexMergeTest {

    // Mirror of the merge in ChannelIndex.addVideos, kept in lockstep.
    private fun merge(
        existing: List<String>,
        batch: List<String>,
        append: Boolean
    ): List<String> {
        val known = existing.toHashSet()
        val fresh = batch.filter { it !in known }
        return if (append) existing + fresh else fresh + existing
    }

    @Test
    fun `delta prepends new videos without double counting`() {
        var index = listOf("a", "b", "c")
        // Page 1 refetch: a is still newest, x and y are brand new.
        index = merge(index, listOf("x", "y", "a"), append = false)
        assertEquals(listOf("x", "y", "a", "b", "c"), index)
        // Re-harvesting the same page changes nothing.
        index = merge(index, listOf("x", "y", "a"), append = false)
        assertEquals(5, index.size)
    }

    @Test
    fun `crawl appends history without double counting overlap`() {
        // Page 1 (delta) then a history page that overlaps the last item.
        var index = merge(emptyList(), listOf("n1", "n2", "n3"), append = false)
        index = merge(index, listOf("n3", "o1", "o2"), append = true)
        assertEquals(listOf("n1", "n2", "n3", "o1", "o2"), index)
        assertEquals(5, index.size)
    }

    @Test
    fun `restart rewalk of page 1 is a no-op for count`() {
        // Process died mid-crawl: cursor lost, crawl restarts at page 1 and
        // hits videos the delta already stored. Append path must not re-add.
        var index = merge(emptyList(), listOf("a", "b", "c"), append = false)
        index = merge(index, listOf("a", "b", "c"), append = true)
        assertEquals(3, index.size)
    }
}
