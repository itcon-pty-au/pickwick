package io.pickwick.app.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** Parent-gated app settings: the PIN (salted hash, never plaintext). */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = prefs.contains("pin")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("pin", "${salt.toHex()}:${hash(salt, pin)}").apply()
    }

    fun checkPin(pin: String): Boolean {
        val stored = prefs.getString("pin", null) ?: return false
        val parts = stored.split(':')
        if (parts.size != 2) return false
        return hash(parts[0].fromHex(), pin) == parts[1]
    }

    private fun hash(salt: ByteArray, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
