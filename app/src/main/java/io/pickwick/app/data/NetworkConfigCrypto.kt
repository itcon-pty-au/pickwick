package io.pickwick.app.data

import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts network credentials over the existing paired LAN transport.
 * Pair authorization still belongs to LanServer; keys are process-local. */
object NetworkConfigCrypto {
    private val keys by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(s: String) = Base64.getDecoder().decode(s)
    fun publicKey(): String = encode(keys.public.encoded)
    fun seal(text: String, publicKey: String): String {
        require(publicKey.length < 2048)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decode(publicKey)))
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aes)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, key)
        return JSONObject().put("key", encode(rsa.doFinal(aes.encoded)))
            .put("iv", encode(cipher.iv)).put("data", encode(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))).toString()
    }
    fun open(text: String): String {
        val o = JSONObject(text)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        rsa.init(Cipher.DECRYPT_MODE, keys.private)
        val aes = SecretKeySpec(rsa.doFinal(decode(o.getString("key"))), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, decode(o.getString("iv"))))
        return String(cipher.doFinal(decode(o.getString("data"))), Charsets.UTF_8)
    }
}
