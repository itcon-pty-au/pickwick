package io.pickwick.app

import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.Limits
import io.pickwick.app.data.Profile
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.data.isValidDirectionPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kid profiles: config round-trip, fingerprint compatibility, visibility rules. */
class ProfileConfigTest {

    private fun entry(id: String, profileIds: Set<String> = emptySet()) = WhitelistEntry(
        id, "https://www.youtube.com/channel/$id", "Channel $id",
        SourceKind.CHANNEL, profileIds = profileIds
    )

    private val dave = Profile(
        id = "aaaa1111", name = "Dave", colorArgb = 0xFFE53935L, avatar = "🦖",
        age = 4, limits = Limits(sessionMinutes = 20, weekdaySessions = 1), pin = null
    )
    private val katy = Profile(
        id = "bbbb2222", name = "Katy", colorArgb = 0xFF1E88E5L, avatar = "🦊",
        age = 12, limits = Limits(sessionMinutes = 45, weekdaySessions = 2,
            bedtimeStartMin = 21 * 60, bedtimeEndMin = 7 * 60),
        pin = "UDLR"
    )

    @Test
    fun `profiles and per-kid fields survive a JSON round-trip`() {
        val config = Whitelist(
            sources = listOf(entry("UCa"), entry("UCb", setOf(dave.id))),
            blockedVideoIds = setOf("dQw4w9WgXcQ"),
            profiles = listOf(dave, katy),
            blockedFor = mapOf("aaaaaaaaaaa" to setOf(dave.id)),
            allowedFor = mapOf("bbbbbbbbbbb" to setOf(katy.id)),
            deviceProfiles = mapOf("0123456789abcdef0123456789abcdef" to dave.id)
        )
        val parsed = ConfigStore.fromJson(ConfigStore.toJson(config))

        assertEquals(config.profiles, parsed.profiles)
        assertEquals(config.blockedFor, parsed.blockedFor)
        assertEquals(config.allowedFor, parsed.allowedFor)
        assertEquals(config.deviceProfiles, parsed.deviceProfiles)
        assertEquals(setOf(dave.id), parsed.sources[1].profileIds)
        assertEquals(emptySet<String>(), parsed.sources[0].profileIds)
    }

    @Test
    fun `profile-free configs keep their pre-profile JSON and fingerprint shape`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        val json = ConfigStore.toJson(plain)
        assertFalse(json.contains("\"profiles\""))
        assertFalse(json.contains("\"blockedFor\""))
        assertFalse(json.contains("\"deviceProfiles\""))
        // A config that never uses profiles must hash like an old build's.
        assertEquals(
            ConfigStore.fingerprint(plain),
            ConfigStore.fingerprint(plain.copy(profiles = emptyList()))
        )
    }

    @Test
    fun `adding profiles or per-kid visibility changes the fingerprint`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        val withKids = plain.copy(profiles = listOf(dave, katy))
        assertNotEquals(ConfigStore.fingerprint(plain), ConfigStore.fingerprint(withKids))

        val restricted = withKids.copy(sources = listOf(entry("UCa", setOf(dave.id))))
        assertNotEquals(ConfigStore.fingerprint(withKids), ConfigStore.fingerprint(restricted))

        // A changed PIN must sync too — it gates the picker on the TV.
        val newPin = withKids.copy(profiles = listOf(dave, katy.copy(pin = "LLRR")))
        assertNotEquals(ConfigStore.fingerprint(withKids), ConfigStore.fingerprint(newPin))
    }

    @Test
    fun `entry visibility - empty set means everyone including future kids`() {
        val shared = entry("UCa")
        assertTrue(shared.visibleTo(dave.id))
        assertTrue(shared.visibleTo("some-future-kid"))
        assertTrue(shared.visibleTo(null))

        val davesOnly = entry("UCb", setOf(dave.id))
        assertTrue(davesOnly.visibleTo(dave.id))
        assertFalse(davesOnly.visibleTo(katy.id))
    }

    @Test
    fun `per-kid blocks and allows overlay the family-wide sets`() {
        val config = Whitelist(
            sources = listOf(entry("UCa")),
            blockedVideoIds = setOf("ggggggggggg"),
            aiAllowedVideoIds = setOf("hhhhhhhhhhh"),
            profiles = listOf(dave, katy),
            blockedFor = mapOf("aaaaaaaaaaa" to setOf(dave.id)),
            allowedFor = mapOf("bbbbbbbbbbb" to setOf(katy.id))
        )
        // Family-wide block hits everyone; per-kid only its kids.
        assertTrue(config.isBlockedFor("ggggggggggg", dave.id))
        assertTrue(config.isBlockedFor("ggggggggggg", katy.id))
        assertTrue(config.isBlockedFor("aaaaaaaaaaa", dave.id))
        assertFalse(config.isBlockedFor("aaaaaaaaaaa", katy.id))

        assertTrue("hhhhhhhhhhh" in config.allowedIdsFor(dave.id))
        assertTrue("bbbbbbbbbbb" in config.allowedIdsFor(katy.id))
        assertFalse("bbbbbbbbbbb" in config.allowedIdsFor(dave.id))
    }

    @Test
    fun `limitsFor returns the kid's rules with the family pause on top`() {
        val config = Whitelist(
            sources = emptyList(), blockedVideoIds = emptySet(),
            limits = Limits(sessionMinutes = 99, pausedUntilMillis = 1_785_800_000_000L),
            profiles = listOf(dave)
        )
        val effective = config.limitsFor(dave.id)
        assertEquals(20, effective.sessionMinutes) // Dave's own, not the legacy 99
        assertEquals(1_785_800_000_000L, effective.pausedUntilMillis) // family pause wins

        // Unknown/absent profile falls back to the family-wide limits.
        assertEquals(99, config.limitsFor(null).sessionMinutes)
        assertEquals(99, config.limitsFor("nope").sessionMinutes)
    }

    @Test
    fun `direction pins validate and malformed stored pins are dropped on parse`() {
        assertTrue(isValidDirectionPin("UDLR"))
        assertTrue(isValidDirectionPin("UUUU"))
        // The center/OK button counts as a step too.
        assertTrue(isValidDirectionPin("UCDC"))
        assertTrue(isValidDirectionPin("CCCC"))
        assertFalse(isValidDirectionPin("UDL"))
        assertFalse(isValidDirectionPin("UDLRX"))
        assertFalse(isValidDirectionPin("1234"))

        val hacked = ConfigStore.toJson(
            Whitelist(emptyList(), emptySet(), profiles = listOf(katy.copy(pin = "UDLR")))
        ).replace("\"pin\": \"UDLR\"", "\"pin\": \"whatever\"")
        assertNull(ConfigStore.fromJson(hacked).profiles.first().pin)
    }

    @Test
    fun `only judgment-relevant kid changes force a catalog re-screen`() {
        val kids = listOf(dave, katy)
        // Renames, pins, colors, avatars, limits: cosmetic to the AI — no re-screen.
        org.junit.Assert.assertFalse(
            io.pickwick.app.data.screeningJudgmentChanged(
                kids, listOf(dave.copy(name = "David", pin = "CCCC", colorArgb = 0x1L), katy)
            )
        )
        // Removing a kid leaves the others' verdicts valid — no re-screen.
        org.junit.Assert.assertFalse(
            io.pickwick.app.data.screeningJudgmentChanged(kids, listOf(dave))
        )
        // A new kid has no verdicts yet — re-screen.
        assertTrue(
            io.pickwick.app.data.screeningJudgmentChanged(
                listOf(dave), kids
            )
        )
        // An age change changes what's appropriate — re-screen.
        assertTrue(
            io.pickwick.app.data.screeningJudgmentChanged(
                kids, listOf(dave.copy(age = 5), katy)
            )
        )
        // First kids ever also count as new.
        assertTrue(io.pickwick.app.data.screeningJudgmentChanged(emptyList(), listOf(dave)))
    }

    @Test
    fun `ai config with profiles still round-trips independently`() {
        val config = Whitelist(
            sources = emptyList(), blockedVideoIds = emptySet(),
            ai = AiConfig(enabled = true, model = "m", childAge = 6, rulesVersion = 2),
            profiles = listOf(dave, katy)
        )
        val parsed = ConfigStore.fromJson(ConfigStore.toJson(config))
        assertEquals(config.ai, parsed.ai)
        assertEquals(2, parsed.profiles.size)
    }
}
