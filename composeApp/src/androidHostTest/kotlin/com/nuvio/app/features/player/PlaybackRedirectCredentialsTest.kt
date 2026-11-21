package com.nuvio.app.features.player

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * B15-SEC-04 follow-up: the stream's credentials never follow a redirect to another site.
 * Two local test servers stand for "the source" and "another site"; the tokens are dummies.
 */
class PlaybackRedirectCredentialsTest {
    private val source = MockWebServer()
    private val otherSite = MockWebServer()

    private val streamHeaders = mapOf(
        "Authorization" to "Bearer dummy-token",
        "Cookie" to "session=dummy",
        "X-Api-Key" to "dummy-key",
        "Referer" to "https://addon.example/",
        "User-Agent" to "NuvioTest",
    )

    @Before
    fun start() {
        source.start()
        otherSite.start()
    }

    @After
    fun stop() {
        source.shutdown()
        otherSite.shutdown()
    }

    // 127.0.0.1 and "localhost" are different hosts with no shared site, like cdn.a.com and b.net.
    private fun sourceUrl(path: String) = source.url(path).newBuilder().host("127.0.0.1").build()
    private fun otherSiteUrl(path: String) = otherSite.url(path).newBuilder().host("localhost").build()

    private fun redirect(to: String) = MockResponse().setResponseCode(302).setHeader("Location", to)

    private fun playerGet(url: okhttp3.HttpUrl): Int {
        val request = Request.Builder().url(url).apply { streamHeaders.forEach { (k, v) -> header(k, v) } }.build()
        return PlayerPlaybackNetworking.callFactoryFor(streamHeaders, useLongReadTimeout = false)
            .newCall(request).execute().use { it.code }
    }

    private fun RecordedRequest.assertNoCredentials() {
        assertNull(getHeader("Authorization"))
        assertNull(getHeader("Cookie"))
        assertNull(getHeader("X-Api-Key"))
        assertEquals("https://addon.example/", getHeader("Referer"))
        assertEquals("NuvioTest", getHeader("User-Agent"))
    }

    private fun RecordedRequest.assertAllCredentials() {
        assertEquals("Bearer dummy-token", getHeader("Authorization"))
        assertEquals("session=dummy", getHeader("Cookie"))
        assertEquals("dummy-key", getHeader("X-Api-Key"))
    }

    @Test
    fun thePlayerSendsNoCredentialsToAnotherSiteAfterARedirect() {
        source.enqueue(redirect(otherSiteUrl("/video.mkv").toString()))
        otherSite.enqueue(MockResponse().setBody("video"))

        assertEquals(200, playerGet(sourceUrl("/play")))

        source.takeRequest(5, TimeUnit.SECONDS)!!.assertAllCredentials()
        otherSite.takeRequest(5, TimeUnit.SECONDS)!!.assertNoCredentials()
    }

    @Test
    fun thePlayerKeepsCredentialsOnARedirectWithinTheSameHost() {
        source.enqueue(redirect(sourceUrl("/segment.ts").toString()))
        source.enqueue(MockResponse().setBody("video"))

        assertEquals(200, playerGet(sourceUrl("/play")))

        source.takeRequest(5, TimeUnit.SECONDS)!!.assertAllCredentials()
        source.takeRequest(5, TimeUnit.SECONDS)!!.assertAllCredentials()
    }

    @Test
    fun theSubtitleHasherSendsNoCredentialsToAnotherSiteAfterARedirect() {
        source.enqueue(redirect(otherSiteUrl("/video.mkv").toString()))
        otherSite.enqueue(MockResponse().setResponseCode(206).setBody("12345678"))

        val connection = PlayerPlaybackNetworking.openConnection(
            sourceUrl("/play").toString(), streamHeaders, "GET", 5_000, 5_000, range = "bytes=0-7",
        )
        assertEquals(206, connection.responseCode)
        connection.disconnect()

        source.takeRequest(5, TimeUnit.SECONDS)!!.assertAllCredentials()
        otherSite.takeRequest(5, TimeUnit.SECONDS)!!.let {
            it.assertNoCredentials()
            assertEquals("bytes=0-7", it.getHeader("Range"))
        }
    }

    @Test
    fun theSubtitleHasherKeepsCredentialsWithinTheSameHost() {
        source.enqueue(redirect("/video.mkv"))
        source.enqueue(MockResponse().setResponseCode(206).setBody("12345678"))

        val connection = PlayerPlaybackNetworking.openConnection(
            sourceUrl("/play").toString(), streamHeaders, "GET", 5_000, 5_000, range = "bytes=0-7",
        )
        assertEquals(206, connection.responseCode)
        connection.disconnect()

        source.takeRequest(5, TimeUnit.SECONDS)!!.assertAllCredentials()
        source.takeRequest(5, TimeUnit.SECONDS)!!.assertAllCredentials()
    }

    @Test
    fun onlyTheSameSiteMayReceiveCredentials() {
        fun may(from: String, to: String) = PlayerPlaybackNetworking.mayReceiveCredentials(from.toHttpUrl(), to.toHttpUrl())
        assertTrue(may("https://api.provider.com/a", "https://api.provider.com/b"))
        // Same site, other host: allowed when OkHttp's public suffix list loads (on the device it is in
        // the app's assets); without it the stricter same-host rule applies. Both outcomes are checked.
        val suffixListLoads = runCatching { "https://api.provider.com/".toHttpUrl().topPrivateDomain() }.isSuccess
        println("public suffix list available here: $suffixListLoads")
        assertEquals(suffixListLoads, may("https://api.provider.com/a", "https://cdn.provider.com/b"))
        assertTrue(may("http://iptv.example.net/a", "http://iptv.example.net:8080/b"))
        assertFalse(may("https://api.provider.com/a", "https://provider.com.evil.net/b"))
        assertFalse(may("https://api.provider.com/a", "https://cdn.other.net/b"))
        assertFalse(may("https://api.provider.com/a", "http://api.provider.com/b"))
        assertFalse(may("https://alice.github.io/a", "https://bob.github.io/b"))
        assertFalse(may("http://10.0.0.2/a", "http://10.0.0.3/b"))
    }

    @Test
    fun tokensInTheUrlAreRecognised() {
        listOf(
            "https://h/v.m3u8?token=x", "https://h/v?api_key=x", "https://h/v?apiKey=x", "https://h/get.php?username=u&password=p",
            "https://h/v?auth=x", "https://h/v?sig=x", "https://h/v?X-Amz-Signature=x", "https://u:p@h/v", "https://h/v?access_token=x",
        ).forEach { assertTrue(it, PlayerPlaybackNetworking.urlCarriesCredentials(it.toHttpUrl())) }
        listOf("https://h/v.mkv", "https://h/v?quality=1080", "https://h/v?t=120", "https://h/v?lang=ar&id=5")
            .forEach { assertFalse(it, PlayerPlaybackNetworking.urlCarriesCredentials(it.toHttpUrl())) }
    }

    @Test
    fun aTokenInTheUrlSelectsTheVerifyingClient() {
        val withToken = PlayerPlaybackNetworking.httpClientFor(emptyMap(), false, "https://h/v?token=x".toHttpUrl())
        val plain = PlayerPlaybackNetworking.httpClientFor(emptyMap(), false, "https://h/v.mkv".toHttpUrl())
        assertTrue(withToken.x509TrustManager is PlayerPlaybackNetworking.TokenSafeTrustManager)
        assertFalse(plain.x509TrustManager is PlayerPlaybackNetworking.TokenSafeTrustManager)
    }
}
