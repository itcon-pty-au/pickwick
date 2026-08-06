package io.pickwick.app.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Parent-gated app settings: the PIN (salted, stretched, never plaintext). */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = prefs.contains("pin")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("pin", "$VERSION:${salt.toHex()}:$ITERATIONS:${pbkdf2(salt, pin, ITERATIONS)}")
            .apply()
    }

    /**
     * Both hash formats verify; a PIN that still checks out against the legacy
     * one is re-stored stretched on the spot, so existing families upgrade
     * silently on their next visit to settings rather than being locked out.
     *
     * Costs ~100 ms of CPU by design — call it off the main thread.
     */
    fun checkPin(pin: String): Boolean {
        val stored = prefs.getString("pin", null) ?: return false
        val parts = stored.split(':')
        return when {
            // v1:<salt>:<iterations>:<hash>
            parts.size == 4 && parts[0] == VERSION -> {
                val iterations = parts[2].toIntOrNull() ?: return false
                constantTimeEquals(pbkdf2(parts[1].fromHex(), pin, iterations), parts[3])
            }
            // Legacy <salt>:<sha256>, a single unstretched round.
            parts.size == 2 -> {
                val ok = constantTimeEquals(legacyHash(parts[0].fromHex(), pin), parts[1])
                if (ok) setPin(pin)
                ok
            }
            else -> false
        }
    }

    private fun pbkdf2(salt: ByteArray, pin: String, iterations: Int): String =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256))
            .encoded
            .toHex()

    private fun legacyHash(salt: ByteArray, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray()).toHex()

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val VERSION = "v1"

        /**
         * A parent PIN is four digits — 10k possibilities. One SHA-256 round
         * falls to exhaustive search the instant the hash is read off the
         * device, so stretch it far enough that the whole keyspace costs real
         * time, while a single check stays imperceptible on a TV box.
         */
        const val ITERATIONS = 120_000
    }
}
