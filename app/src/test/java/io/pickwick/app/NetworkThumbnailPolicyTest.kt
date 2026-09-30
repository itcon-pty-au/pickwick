package io.pickwick.app

import io.pickwick.app.data.NetworkThumbnailPolicy
import org.junit.Assert.*
import org.junit.Test

class NetworkThumbnailPolicyTest {
    @Test fun seeksPastEpisodeIntro() {
        assertEquals(listOf(300_000_000L, 600_000_000L, 900_000_000L),
            NetworkThumbnailPolicy.timesUs(1200))
    }

    @Test fun shortClipsStayWithinDuration() {
        assertTrue(NetworkThumbnailPolicy.timesUs(1).all { it in 0 until 1_000_000L })
    }

    @Test fun rejectsBlackAndNearlyBlackFrames() {
        assertTrue(NetworkThumbnailPolicy.isNearBlack(IntArray(100) { 0xff000000.toInt() }))
        assertTrue(NetworkThumbnailPolicy.isNearBlack(IntArray(100) { 0xff101010.toInt() }))
        assertTrue(NetworkThumbnailPolicy.isNearBlack(IntArray(100) {
            if (it < 4) 0xffffffff.toInt() else 0xff000000.toInt()
        }))
    }

    @Test fun acceptsVisibleSceneWithDarkBorders() {
        assertFalse(NetworkThumbnailPolicy.isNearBlack(IntArray(100) {
            if (it < 60) 0xff409060.toInt() else 0xff000000.toInt()
        }))
    }
}
