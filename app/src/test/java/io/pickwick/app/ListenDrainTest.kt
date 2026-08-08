package io.pickwick.app

import io.pickwick.app.data.LISTEN_MULTIPLIERS
import io.pickwick.app.data.listenDrainPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screen-off listening drain: the channel's own rate scaled by the family
 * listening rate. The player charges this per 5s tick, so wrong math here is
 * wrong screen-time accounting.
 */
class ListenDrainTest {

    @Test
    fun `unset listening rate leaves the channel rate untouched`() {
        assertEquals(100, listenDrainPercent(100, null))
        assertEquals(25, listenDrainPercent(25, null))
        assertEquals(0, listenDrainPercent(0, null))
    }

    @Test
    fun `rates multiply, so FREE channels stay free and discounts stack`() {
        assertEquals(50, listenDrainPercent(100, 50))
        // A junk-food penalty listened at half rate: 150 × 50% = exactly 75.
        assertEquals(75, listenDrainPercent(150, 50))
        // FREE wins from either side.
        assertEquals(0, listenDrainPercent(0, 50))
        assertEquals(0, listenDrainPercent(100, 0))
    }

    @Test
    fun `listening never costs more than watching`() {
        // The chip deliberately offers no penalty rates; if one ever appears,
        // this documents the intent.
        LISTEN_MULTIPLIERS.filterNotNull().forEach { rate ->
            assertTrue(rate <= 100)
            assertTrue(listenDrainPercent(100, rate) <= 100)
        }
    }
}
