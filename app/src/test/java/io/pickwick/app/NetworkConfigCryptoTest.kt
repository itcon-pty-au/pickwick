package io.pickwick.app

import io.pickwick.app.data.NetworkConfigCrypto
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkConfigCryptoTest {
    @Test fun `large Unicode config round trips without plaintext credentials on the wire`() {
        val text = "{\"password\":\"test-secret\",\"name\":\"Pok\u00e9mon\",\"data\":\"${"x".repeat(10000)}\"}"
        val encrypted = NetworkConfigCrypto.seal(text, NetworkConfigCrypto.publicKey())
        assertFalse(encrypted.contains("test-secret"))
        assertEquals(text, NetworkConfigCrypto.open(encrypted))
        assertNotEquals(encrypted, NetworkConfigCrypto.seal(text, NetworkConfigCrypto.publicKey()))
    }

    @Test fun `tampered payload is rejected`() {
        val o = JSONObject(NetworkConfigCrypto.seal("secret", NetworkConfigCrypto.publicKey()))
        val bytes = java.util.Base64.getDecoder().decode(o.getString("data"))
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        o.put("data", java.util.Base64.getEncoder().encodeToString(bytes))
        assertThrows(Exception::class.java) { NetworkConfigCrypto.open(o.toString()) }
    }
}
