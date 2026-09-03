package io.pickwick.app

import io.pickwick.app.data.SessionGuard
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowPassTest {
    private val min = 60_000L

    @Test fun `grants inside a window stack instead of overlapping`() {
        val now = 1_000_000L
        val first = SessionGuard.extendPass(0L, now, 15)
        val second = SessionGuard.extendPass(first, now + 10_000L, 15)
        assertEquals(now + 15 * min, first)
        assertEquals(now + 30 * min, second)
    }

    @Test fun `a lapsed pass counts from now`() {
        val now = 5_000_000L
        assertEquals(now + 15 * min, SessionGuard.extendPass(now - 60 * min, now, 15))
    }

    @Test fun `taking back shortens a live pass but not below now`() {
        val now = 1_000_000L
        val pass = now + 30 * min
        assertEquals(now + 15 * min, SessionGuard.shrinkPass(pass, now, 15))
        assertEquals(now, SessionGuard.shrinkPass(pass, now, 45))
    }

    @Test fun `taking back leaves a lapsed pass alone`() {
        val now = 1_000_000L
        assertEquals(now - min, SessionGuard.shrinkPass(now - min, now, 15))
        assertEquals(0L, SessionGuard.shrinkPass(0L, now, 15))
    }
}
