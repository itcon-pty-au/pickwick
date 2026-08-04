package io.pickwick.app

import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.data.WhitelistExporter
import io.pickwick.app.data.WhitelistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen-time multiplier: serialization, fingerprint, and export behavior. */
class ConfigStoreJsonTest {

    private fun entry(id: String, percent: Int = 100) = WhitelistEntry(
        id, "https://www.youtube.com/channel/$id", "Channel $id",
        SourceKind.CHANNEL, timeMultiplierPercent = percent
    )

    @Test
    fun `multiplier survives a JSON round-trip`() {
        val config = Whitelist(
            sources = listOf(entry("UCa", 50), entry("UCb"), entry("UCc", 0)),
            blockedVideoIds = emptySet()
        )
        val parsed = ConfigStore.fromJson(ConfigStore.toJson(config))
        assertEquals(50, parsed.sources[0].timeMultiplierPercent)
        assertEquals(100, parsed.sources[1].timeMultiplierPercent)
        assertEquals(0, parsed.sources[2].timeMultiplierPercent)
    }

    @Test
    fun `default multiplier is omitted from JSON so old builds parse unchanged`() {
        val json = ConfigStore.toJson(Whitelist(listOf(entry("UCa")), emptySet()))
        assertFalse(json.contains("\"time\""))
    }

    @Test
    fun `configs without multipliers keep their pre-multiplier fingerprint shape`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        val halved = Whitelist(listOf(entry("UCa", 50)), emptySet())
        // Same entries at default rate hash identically…
        assertEquals(
            ConfigStore.fingerprint(plain),
            ConfigStore.fingerprint(Whitelist(listOf(entry("UCa", 100)), emptySet()))
        )
        // …but a changed rate must change the fingerprint (it changes behavior).
        assertNotEquals(ConfigStore.fingerprint(plain), ConfigStore.fingerprint(halved))
    }

    @Test
    fun `parent pause survives a JSON round-trip and clears back to null`() {
        val until = 1_785_800_000_000L
        val paused = Whitelist(
            listOf(entry("UCa")), emptySet(),
            limits = io.pickwick.app.data.Limits(sessionMinutes = 30, pausedUntilMillis = until)
        )
        val parsed = ConfigStore.fromJson(ConfigStore.toJson(paused))
        assertEquals(until, parsed.limits.pausedUntilMillis)
        assertEquals(30, parsed.limits.sessionMinutes)

        // Resume writes null — the field must vanish from JSON, not linger as 0.
        val resumed = ConfigStore.fromJson(
            ConfigStore.toJson(paused.copy(limits = paused.limits.copy(pausedUntilMillis = null)))
        )
        assertEquals(null, resumed.limits.pausedUntilMillis)
    }

    @Test
    fun `pause changes the fingerprint so offline reconcile delivers it`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        val paused = plain.copy(
            limits = plain.limits.copy(pausedUntilMillis = 1_785_800_000_000L)
        )
        // Unpaused configs keep their pre-pause hash shape…
        assertEquals(
            ConfigStore.fingerprint(plain),
            ConfigStore.fingerprint(plain.copy(limits = plain.limits.copy(pausedUntilMillis = null)))
        )
        // …but pausing must change it (syncConfigState re-pushes on mismatch).
        assertNotEquals(ConfigStore.fingerprint(plain), ConfigStore.fingerprint(paused))
    }

    @Test
    fun `exported multiplier comment does not corrupt re-import`() {
        val exported = WhitelistExporter.toText(
            Whitelist(listOf(entry("UC4a-Gbdw7vOaccHmFo40b9g", 25)), emptySet())
        )
        assertTrue(exported.contains("# screen time 25%"))
        val reparsed = WhitelistParser.parse(exported)
        assertEquals(1, reparsed.sources.size)
        assertEquals("UC4a-Gbdw7vOaccHmFo40b9g", reparsed.sources[0].id)
        // Files carry links only — the multiplier itself is UI/sync-managed.
        assertEquals(100, reparsed.sources[0].timeMultiplierPercent)
    }
}
