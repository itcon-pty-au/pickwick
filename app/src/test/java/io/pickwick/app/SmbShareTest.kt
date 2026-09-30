package io.pickwick.app

import io.pickwick.app.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmbShareTest {
    private val first = SmbCatalog(id = "pokemon", name = "Pokemon", host = "router", share = "USB",
        root = "Kids/Pokemon", guest = false, username = "parent", password = "secret",
        profileIds = setOf("kid"), timePercent = 50)
    private val second = first.copy(id = "movies", name = "Movies", root = "Kids/Movies", timePercent = 100)

    @Test fun `migration groups matching connections and preserves catalog identity and rules`() {
        val legacy = Whitelist(emptyList(), emptySet(), networkCatalogs = listOf(first, second))
        val migrated = legacy.migrateNetworkShares()
        assertEquals(1, migrated.networkShares.size)
        assertEquals(migrated.networkShares.single().id, migrated.networkCatalogs[0].shareId)
        migrated.networkCatalogs.zip(legacy.networkCatalogs).forEach { (next, old) ->
            assertEquals(old, next.copy(shareId = null))
            assertEquals(old.url("Season 01/Episode.mp4"), next.url("Season 01/Episode.mp4"))
        }
        assertEquals(migrated, migrated.migrateNetworkShares())
        assertEquals(migrated, ConfigStore.fromJson(ConfigStore.toJson(migrated)))
    }

    @Test fun `different credentials shares and ports are not merged`() {
        val variants = listOf(first, second.copy(password = "other"), second.copy(username = "other"),
            second.copy(domain = "other"), second.copy(guest = true), second.copy(share = "Other"), second.copy(port = 1445))
            .mapIndexed { i, c -> c.copy(id = "catalog-$i") }
        assertEquals(variants.size, Whitelist(emptyList(), emptySet(), networkCatalogs = variants).migrateNetworkShares().networkShares.size)
    }

    @Test fun `connection edits apply to all catalogs without changing their rules`() {
        val migrated = Whitelist(emptyList(), emptySet(), networkCatalogs = listOf(first, second)).migrateNetworkShares()
        val changed = migrated.copy(networkShares = migrated.networkShares.map { it.copy(host = "new-router", password = "new-secret") })
            .resolveNetworkShares()
        assertTrue(changed.networkCatalogs.all { it.host == "new-router" && it.password == "new-secret" })
        assertEquals(listOf(50, 100), changed.networkCatalogs.map { it.timePercent })
        assertEquals(migrated.networkCatalogs.map { it.root }, changed.networkCatalogs.map { it.root })
        assertNotEquals(ConfigStore.fingerprint(migrated), ConfigStore.fingerprint(changed))
    }

    @Test fun `empty connections round trip and never put secrets in disk payloads`() {
        val connection = SmbShare.fromCatalog(first)
        val config = Whitelist(emptyList(), emptySet(), networkShares = listOf(connection))
        val payload = ConfigStore.toJson(config)
        assertEquals(config, ConfigStore.fromJson(payload))
        assertFalse(ConfigStore.toJson(config, false).contains("secret"))
        val stripped = JSONObject(ConfigStore.stripSecrets(JSONObject(payload).put("future", 123).toString()))
        assertEquals(123, stripped.getInt("future"))
        assertFalse(stripped.getJSONArray("networkShares").getJSONObject(0).has("password"))
        assertNotEquals(ConfigStore.fingerprint(config), ConfigStore.fingerprint(config.copy(networkShares = emptyList())))
    }

    @Test fun `missing referenced connection fails closed`() {
        val config = Whitelist(emptyList(), emptySet(), networkCatalogs = listOf(first.copy(shareId = "missing")))
        assertThrows(IllegalArgumentException::class.java) { config.resolveNetworkShares() }
    }
}
