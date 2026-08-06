package io.pickwick.app

import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI API key travels to paired devices but must never reach `config.json`,
 * which is listed for cloud backup.
 */
class ConfigSecretsTest {

    private fun config(key: String) = Whitelist(
        sources = listOf(
            WhitelistEntry("UCa", "https://www.youtube.com/channel/UCa", "A", SourceKind.CHANNEL)
        ),
        blockedVideoIds = emptySet(),
        ai = AiConfig(enabled = true, model = "some/model", apiKey = key, rules = "be kind")
    )

    @Test
    fun `a push carries the key so the device can screen`() {
        val json = ConfigStore.toJson(config("sk-secret"))
        assertTrue(json.contains("sk-secret"))
        assertEquals("sk-secret", ConfigStore.fromJson(json).ai.apiKey)
    }

    @Test
    fun `the on-disk copy leaves the key out`() {
        val json = ConfigStore.toJson(config("sk-secret"), includeSecrets = false)
        assertFalse(json.contains("sk-secret"))
        assertFalse(json.contains("apiKey"))
        // Everything else still round-trips.
        val parsed = ConfigStore.fromJson(json)
        assertEquals("some/model", parsed.ai.model)
        assertEquals("be kind", parsed.ai.rules)
        assertEquals(1, parsed.sources.size)
    }

    @Test
    fun `stripping a pushed payload keeps fields this build does not know`() {
        val pushed = JSONObject(ConfigStore.toJson(config("sk-secret")))
            .put("somethingNewerPhonesSend", "keep me")
            .toString()

        val stored = ConfigStore.stripSecrets(pushed)

        assertFalse(stored.contains("sk-secret"))
        val root = JSONObject(stored)
        assertEquals("keep me", root.getString("somethingNewerPhonesSend"))
        assertFalse(root.getJSONObject("ai").has("apiKey"))
        // updatedAt is preserved verbatim, not restamped — the phone's edit time
        // is what both ends compare.
        assertEquals(
            JSONObject(pushed).getLong("updatedAt"),
            root.getLong("updatedAt")
        )
    }

    @Test
    fun `stripping is a no-op on payloads without a key`() {
        val json = ConfigStore.toJson(config("sk-secret"), includeSecrets = false)
        assertEquals(json, ConfigStore.stripSecrets(json))
        assertEquals("not json", ConfigStore.stripSecrets("not json"))
    }

    @Test
    fun `the key still changes the fingerprint so a rotated key reaches devices`() {
        // The canonical string never leaves the device — only its 4-byte digest
        // does — so hashing the key here is safe, and necessary: sync re-pushes
        // only on a fingerprint mismatch.
        assertFalse(
            ConfigStore.fingerprint(config("sk-one")) == ConfigStore.fingerprint(config("sk-two"))
        )
    }
}
