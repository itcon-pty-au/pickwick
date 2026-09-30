package io.pickwick.app

import io.pickwick.app.data.*
import org.junit.Assert.*
import org.junit.Test

class ContentGroupTest {
    private val group = ContentGroup(id = "pokemon", name = "Pokemon",
        youtubeSources = setOf("https://www.youtube.com/playlist?list=PLpokemon"), catalogIds = setOf("pokemon-catalog"),
        sessionMinutes = mapOf("alex" to 15, "sam" to 20))

    @Test fun `content rules round trip and change sync fingerprint`() {
        val config = Whitelist(emptyList(), emptySet(), contentGroups = listOf(group))
        assertEquals(config, ConfigStore.fromJson(ConfigStore.toJson(config)))
        assertNotEquals(ConfigStore.fingerprint(config), ConfigStore.fingerprint(config.copy(contentGroups = listOf(group.copy(sessionMinutes = mapOf("alex" to 10))))))
        assertNotEquals(ConfigStore.fingerprint(config), ConfigStore.fingerprint(config.copy(contentGroups = emptyList())))
        assertTrue(ConfigStore.fromJson("{\"entries\":[],\"blocked\":[]}").contentGroups.isEmpty())
    }

    @Test fun `limits are per child and unset means unlimited`() {
        assertEquals(15, group.minutesFor("alex"))
        assertEquals(20, group.minutesFor("sam"))
        assertNull(group.minutesFor("other"))
        assertNull(group.minutesFor(null))
        assertEquals(0, group.copy(sessionMinutes = mapOf("" to 0)).minutesFor(null))
    }

    @Test fun `invalid caps and duplicate identities are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { group.copy(sessionMinutes = mapOf("alex" to -1)) }
        assertThrows(IllegalArgumentException::class.java) { group.copy(sessionMinutes = mapOf("alex" to 1441)) }
        val duplicate = Whitelist(emptyList(), emptySet(), contentGroups = listOf(group, group))
        assertThrows(IllegalArgumentException::class.java) { ConfigStore.fromJson(ConfigStore.toJson(duplicate)) }
    }
}
