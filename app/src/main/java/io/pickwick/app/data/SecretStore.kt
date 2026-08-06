package io.pickwick.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Credentials that must not travel with the rest of the config: today just the
 * AI provider's API key.
 *
 * Everything else Pickwick stores is family-local — losing it costs the parents
 * their curation. The API key is different: it is a real credential with a
 * balance attached, and it used to sit in the clear inside `config.json`, which
 * is listed in `backup_rules.xml` and so rides to Google's cloud backup. Here it
 * is Keystore-encrypted at rest and, because the backup rules are include-only
 * and never name this file, it stays on the device.
 *
 * Opening this costs a Keystore round trip, so callers only reach for it when
 * the family actually has AI screening set up — see [ConfigStore.load].
 */
class SecretStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        runCatching {
            EncryptedSharedPreferences.create(
                FILE,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            // A wedged Keystore must not take AI screening down with it. This
            // file is excluded from backup the same way, so the key still never
            // leaves the device — it just isn't encrypted at rest.
            android.util.Log.w("Pickwick", "encrypted prefs unavailable — storing plainly", it)
            appContext.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
        }
    }

    fun aiApiKey(): String = runCatching { prefs.getString(KEY_AI, "").orEmpty() }.getOrDefault("")

    fun setAiApiKey(value: String) {
        runCatching { prefs.edit().putString(KEY_AI, value).apply() }
    }

    private companion object {
        const val FILE = "secrets"
        const val FALLBACK_FILE = "secrets_plain"
        const val KEY_AI = "ai_api_key"
    }
}
