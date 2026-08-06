package io.pickwick.app

import io.pickwick.app.data.AiScreener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app allows cleartext traffic app-wide (pairing needs it), so this check
 * is the only thing standing between an `http://` base URL and an API key on
 * the wire. A local model is the one case that legitimately needs cleartext.
 */
class EndpointSafetyTest {

    @Test
    fun `https is always fine`() {
        assertTrue(AiScreener.isEndpointSafe("https://openrouter.ai/api/v1"))
        assertTrue(AiScreener.isEndpointSafe("https://api.openai.com/v1"))
    }

    @Test
    fun `cleartext to a public host is refused`() {
        assertFalse(AiScreener.isEndpointSafe("http://api.openai.com/v1"))
        assertFalse(AiScreener.isEndpointSafe("http://example.com:8080/v1"))
    }

    @Test
    fun `cleartext to a model on the home network is allowed`() {
        assertTrue(AiScreener.isEndpointSafe("http://localhost:11434/v1"))
        assertTrue(AiScreener.isEndpointSafe("http://127.0.0.1:11434/v1"))
        assertTrue(AiScreener.isEndpointSafe("http://192.168.1.50:11434/v1"))
        assertTrue(AiScreener.isEndpointSafe("http://10.0.0.5:1234/v1"))
        assertTrue(AiScreener.isEndpointSafe("http://172.16.0.9:1234/v1"))
        assertTrue(AiScreener.isEndpointSafe("http://nas.local:11434/v1"))
    }

    @Test
    fun `a public hostname that merely looks private is refused`() {
        // Prefix matching on "10." or "192.168." would wave these through.
        assertFalse(AiScreener.isEndpointSafe("http://10.example.com/v1"))
        assertFalse(AiScreener.isEndpointSafe("http://192.168.evil.net/v1"))
        // 172.32 is outside the RFC 1918 block; 172.16–172.31 is the range.
        assertFalse(AiScreener.isEndpointSafe("http://172.32.0.1/v1"))
        assertFalse(AiScreener.isEndpointSafe("http://999.1.1.1/v1"))
        // Hostnames that share a prefix with the IPv6 unique-local block —
        // "fc"/"fd" tests must only ever fire on an IPv6 literal.
        assertFalse(AiScreener.isEndpointSafe("http://fdic.gov/v1"))
        assertFalse(AiScreener.isEndpointSafe("http://fcbarcelona.com/v1"))
    }

    @Test
    fun `IPv6 literals split private from public`() {
        assertTrue(AiScreener.isEndpointSafe("http://[::1]:11434/v1"))
        assertTrue(AiScreener.isEndpointSafe("http://[fd12:3456::1]:11434/v1"))
        // Global IPv6 is as public as any internet host.
        assertFalse(AiScreener.isEndpointSafe("http://[2001:db8::1]:11434/v1"))
    }

    @Test
    fun `junk and non-http schemes are refused`() {
        assertFalse(AiScreener.isEndpointSafe(""))
        assertFalse(AiScreener.isEndpointSafe("not a url"))
        assertFalse(AiScreener.isEndpointSafe("ftp://example.com/v1"))
        assertFalse(AiScreener.isEndpointSafe("file:///etc/passwd"))
    }
}
