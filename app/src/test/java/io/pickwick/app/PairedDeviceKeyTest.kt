package io.pickwick.app

import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.removePaired
import io.pickwick.app.data.renamePaired
import io.pickwick.app.data.upsertPaired
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The paired list is keyed by [PairedDevice.key] (identity, else host:port) —
 * never by [PairedDevice.token], because every entry carries THIS phone's own
 * token. Token-keyed operations once made adding a second device silently
 * replace the first, so the list could never hold more than one.
 */
class PairedDeviceKeyTest {

    private val myToken = "a".repeat(32)
    private val tv = PairedDevice("Living room TV", "192.168.0.10", 8765, myToken, id = "tv-id")
    private val tablet = PairedDevice("Kid tablet", "192.168.0.20", 8765, myToken, id = "tablet-id")

    @Test
    fun `second device with the same phone token does not replace the first`() {
        val list = emptyList<PairedDevice>().upsertPaired(tv).upsertPaired(tablet)
        assertEquals(listOf(tv, tablet), list)
    }

    @Test
    fun `re-pairing the same address replaces rather than duplicates`() {
        val rescanned = tv.copy(name = "TV again")
        assertEquals(listOf(tablet, rescanned), listOf(tv, tablet).upsertPaired(rescanned))
    }

    @Test
    fun `re-pairing a moved device with a known identity replaces the stale entry`() {
        val moved = tv.copy(host = "192.168.0.55")
        assertEquals(listOf(tablet, moved), listOf(tv, tablet).upsertPaired(moved))
    }

    @Test
    fun `id-less legacy entry keys on its address and survives an unrelated add`() {
        val legacy = PairedDevice("Old TV", "192.168.0.30", 8766, myToken)
        assertEquals("192.168.0.30:8766", legacy.key)
        assertEquals(listOf(legacy, tablet), listOf(legacy).upsertPaired(tablet))
    }

    @Test
    fun `remove takes out only the keyed device`() {
        assertEquals(listOf(tablet), listOf(tv, tablet).removePaired(tv.key))
    }

    @Test
    fun `remove works for an id-less entry via its host-port key`() {
        val legacy = PairedDevice("Old TV", "192.168.0.30", 8766, myToken)
        assertEquals(listOf(tv), listOf(tv, legacy).removePaired("192.168.0.30:8766"))
    }

    @Test
    fun `rename touches only the keyed device`() {
        val renamed = listOf(tv, tablet).renamePaired(tablet.key, "Nina's tablet")
        assertEquals(listOf(tv, tablet.copy(name = "Nina's tablet")), renamed)
    }
}
