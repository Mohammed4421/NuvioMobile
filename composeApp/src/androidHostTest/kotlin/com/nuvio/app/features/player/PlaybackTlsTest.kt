package com.nuvio.app.features.player

import java.net.InetAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** B15-SEC-04: credentials go only to verified servers; everything else plays as before. */
class PlaybackTlsTest {
    private class FakeSystem(private val accept: Boolean) : X509TrustManager {
        var calls = 0
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            calls += 1
            if (!accept) throw CertificateException("untrusted")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun credentialHeadersAreRecognised() {
        listOf("Authorization", "cookie", "X-Api-Key", "x-auth-token", "Proxy-Authorization", "apikey", "X-Session-Id")
            .forEach { assertTrue(it, PlayerPlaybackNetworking.carriesCredentials(mapOf(it to "v"))) }
        listOf("User-Agent", "Referer", "Origin", "Accept", "Range")
            .forEach { assertFalse(it, PlayerPlaybackNetworking.carriesCredentials(mapOf(it to "v"))) }
        assertFalse(PlayerPlaybackNetworking.carriesCredentials(mapOf("Authorization" to " ")))
    }

    @Test
    fun localNetworkHostsAreRecognised() {
        listOf("localhost", "127.0.0.1", "10.0.0.5", "172.16.1.1", "172.31.255.1", "192.168.1.20", "169.254.3.3",
            "100.64.0.1", "nas.local", "media.lan", "box.home.arpa", "[::1]", "fd12::1", "fe80::1")
            .forEach { assertTrue(it, PlayerPlaybackNetworking.isLocalNetworkHost(it)) }
        listOf("real-debrid.com", "8.8.8.8", "172.32.0.1", "192.169.0.1", "cdn.example.org", "2001:db8::1", "local.example.com")
            .forEach { assertFalse(it, PlayerPlaybackNetworking.isLocalNetworkHost(it)) }
    }

    @Test
    fun anUntrustedCertificateIsRefusedForAPublicHostAndAcceptedOnTheLocalNetwork() {
        val system = FakeSystem(accept = false)
        val manager = PlayerPlaybackNetworking.TokenSafeTrustManager(system)
        assertThrows(CertificateException::class.java) { manager.checkServerTrustedForHost(emptyArray(), "RSA", "stream.example.com") }
        manager.checkServerTrustedForHost(emptyArray(), "RSA", "192.168.1.20")
        assertEquals(1, system.calls)
    }

    @Test
    fun aTrustedCertificateIsAccepted() {
        PlayerPlaybackNetworking.TokenSafeTrustManager(FakeSystem(accept = true))
            .checkServerTrustedForHost(emptyArray(), "RSA", "stream.example.com")
    }

    @Test
    fun onlyRequestsWithCredentialsUseTheVerifyingClient() {
        val plain = PlayerPlaybackNetworking.httpClientFor(mapOf("User-Agent" to "x"), useLongReadTimeout = false)
        val withToken = PlayerPlaybackNetworking.httpClientFor(mapOf("Authorization" to "Bearer t"), useLongReadTimeout = false)
        assertSame(plain, PlayerPlaybackNetworking.httpClientFor(emptyMap(), useLongReadTimeout = false))
        assertNotSame(plain, withToken)
        assertNotSame(plain.x509TrustManager, withToken.x509TrustManager)
        assertTrue(withToken.x509TrustManager is PlayerPlaybackNetworking.TokenSafeTrustManager)
        assertNotSame(withToken, PlayerPlaybackNetworking.httpClientFor(mapOf("Cookie" to "s=1"), useLongReadTimeout = true))
    }

    /**
     * Real servers (badssl.com): a self-signed certificate still plays without credentials, is
     * refused with them, and a valid certificate works with them. Skipped (reported) without network.
     */
    @Test
    fun realServersBehaveAsIntended() {
        assumeTrue("no network", runCatching { InetAddress.getByName("badssl.com") }.isSuccess)
        fun get(url: String, headers: Map<String, String>): Int =
            PlayerPlaybackNetworking.httpClientFor(headers, useLongReadTimeout = false)
                .newCall(Request.Builder().url(url).build()).execute().use { it.code }
        val token = mapOf("Authorization" to "Bearer test")
        assertEquals(200, get("https://self-signed.badssl.com/", emptyMap()))
        assertThrows(SSLHandshakeException::class.java) { get("https://self-signed.badssl.com/", token) }
        assertThrows(Exception::class.java) { get("https://wrong.host.badssl.com/", token) }
        assertEquals(200, get("https://badssl.com/", token))
    }
}
