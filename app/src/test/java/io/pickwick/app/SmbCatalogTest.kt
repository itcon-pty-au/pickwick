package io.pickwick.app

import io.pickwick.app.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmbCatalogTest {
    private val catalog = SmbCatalog(id = "test-catalog", name = "Pokemon", host = "192.168.0.1", share = "USB",
        root = "Kids/Pokemon", guest = false, username = "parent", password = "test-secret",
        credentialVersion = "revision-1", profileIds = setOf("kid"), timePercent = 50)

    @Test fun `episode URLs preserve unicode spaces plus and hash characters`() {
        val path = "Season 01/Pok\u00e9mon + friends #1.mp4"
        assertEquals(catalog.id to path, SmbPaths.parse(catalog.url(path)))
        assertEquals(catalog.url(path), catalog.copy(host = "new-host", password = "new-secret").url(path))
    }

    @Test fun `path traversal is rejected before reaching the share`() {
        listOf("../secret", "Season/../../secret", "Season\\..\\secret", "file:stream", "a\u0000b").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { SmbPaths.clean(path) }
        }
        assertThrows(IllegalArgumentException::class.java) { SmbPaths.parse("pickwick://smb/test/%2e%2e/secret") }
        assertThrows(IllegalArgumentException::class.java) { SmbPaths.parse("pickwick://smb/test/Season%2f..%2fsecret") }
        assertThrows(IllegalArgumentException::class.java) { SmbPaths.parse("https://smb/test/episode.mp4") }
    }

    @Test fun `natural order keeps seasons and episodes in numeric order without overflow`() {
        val input = listOf("Episode 10.mp4", "Episode 2.mp4", "Episode 9999999999999999999999.mp4", "Episode 1.mp4")
        assertEquals(listOf(input[3], input[1], input[0], input[2]), input.sortedWith(SmbPaths.naturalOrder))
    }

    @Test fun `catalog visibility fails closed for an unknown child`() {
        assertTrue(catalog.visibleTo("kid"))
        assertFalse(catalog.visibleTo("other"))
        assertFalse(catalog.visibleTo(null))
        assertTrue(catalog.copy(profileIds = emptySet()).visibleTo(null))
    }

    @Test fun `removing a child revokes playback even from an everyone catalog`() {
        val everyone = catalog.copy(profileIds = emptySet())
        val w = Whitelist(emptyList(), emptySet(), profiles = listOf(Profile(id = "kid", name = "Kid")), networkCatalogs = listOf(everyone))
        assertEquals(everyone, w.networkCatalogFor(catalog.id, "kid"))
        assertNull(w.networkCatalogFor(catalog.id, "removed"))
        assertNull(w.networkCatalogFor(catalog.id, null))
        assertNull(w.copy(networkCatalogs = emptyList()).networkCatalogFor(catalog.id, "kid"))
    }

    @Test fun `config round trip includes network rules and only transports credentials explicitly`() {
        val config = Whitelist(emptyList(), emptySet(), networkCatalogs = listOf(catalog))
        assertEquals(config, ConfigStore.fromJson(ConfigStore.toJson(config)))
        val stored = ConfigStore.toJson(config, includeSecrets = false)
        assertFalse(stored.contains("test-secret"))
        assertFalse(JSONObject(stored).getJSONArray("networkCatalogs").getJSONObject(0).has("password"))
        assertEquals(catalog.copy(password = ""), ConfigStore.fromJson(stored).networkCatalogs.single())
    }

    @Test fun `password stripping also works without an AI object and preserves unknown fields`() {
        val input = JSONObject().put("future", 123).put("networkCatalogs", org.json.JSONArray().put(catalog.toJson(true))).toString()
        val stripped = ConfigStore.stripSecrets(input)
        assertFalse(stripped.contains("test-secret"))
        assertEquals(123, JSONObject(stripped).getInt("future"))
    }

    @Test fun `catalog edits and credential revisions trigger configuration sync`() {
        fun hash(c: SmbCatalog) = ConfigStore.fingerprint(Whitelist(emptyList(), emptySet(), networkCatalogs = listOf(c)))
        listOf(catalog.copy(root = "Other"), catalog.copy(timePercent = 0), catalog.copy(profileIds = emptySet()),
            catalog.copy(host = "router"), catalog.copy(credentialVersion = "revision-2")).forEach { assertNotEquals(hash(catalog), hash(it)) }
    }

    @Test fun `old configs load without network catalogs`() {
        assertTrue(ConfigStore.fromJson("{\"entries\":[],\"blocked\":[]}").networkCatalogs.isEmpty())
    }

    @Test fun `network video IDs are distinct from YouTube and local IDs`() {
        val video = Video(catalog.url("Season 1/Episode 1.mp4"), "Episode 1", "Pokemon", null, 1200)
        assertTrue(video.videoId!!.startsWith("smb-"))
        assertEquals(video.videoId, video.copy(title = "Renamed").videoId)
    }
}
