package io.pickwick.app

import io.pickwick.app.data.SessionGuard
import org.junit.Assert.*
import org.junit.Test

class FreeSourceBudgetTest {
    @Test fun `switching to a free catalog after spending the daily budget remains allowed`() {
        assertFalse(SessionGuard.budgetReached(600_000, 600_000, 0))
        assertFalse(SessionGuard.budgetReached(900_000, 600_000, 0))
    }
    @Test fun `paid catalogs stop at the budget boundary at every rate`() {
        listOf(25, 50, 75, 100, 125, 150).forEach { rate ->
            assertFalse(SessionGuard.budgetReached(599_999, 600_000, rate))
            assertTrue(SessionGuard.budgetReached(600_000, 600_000, rate))
        }
    }
    @Test fun `an unset budget never blocks a catalog`() {
        assertFalse(SessionGuard.budgetReached(Long.MAX_VALUE, null, 100))
    }
}
