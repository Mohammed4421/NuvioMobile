package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.network.NetworkErrorInterceptor
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    internal const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val DEFAULT_STREAM_HEADERS = mapOf(
        "User-Agent" to DEFAULT_USER_AGENT,
    )

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    // Nuvio Plus (B15-SEC-04): a request that carries credentials (Authorization, cookies, API keys
    // or tokens in headers) is sent only to a server whose certificate and host name check out, as
    // libmpv already does for every stream (tls-verify=yes). Hosts on the device or the local
    // network keep the old leniency (home servers often use self-signed certificates). Requests
    // without credentials are unchanged, so no source that plays today stops playing for this.
    private val systemTrustManager: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    private val tokenSafeTrustManager: X509TrustManager by lazy { TokenSafeTrustManager(systemTrustManager) }

    private val tokenSafeSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tokenSafeTrustManager), SecureRandom())
        }
    }

    // OkHttp's own verifier (RFC 2818 host name matching), the one an OkHttpClient uses by default.
    private val standardHostnameVerifier: HostnameVerifier by lazy { OkHttpClient.Builder().build().hostnameVerifier }

    private val tokenSafeHostnameVerifier = HostnameVerifier { host, session ->
        isLocalNetworkHost(host) || standardHostnameVerifier.verify(host, session)
    }

    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(NetworkErrorInterceptor())
            .addInterceptor(SentryNetworkBreadcrumbInterceptor())
            .build()
    }

    private val loopbackPlaybackHttpClient: OkHttpClient by lazy {
        playbackHttpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val requestChain = if (isLoopbackHost(request.url.host)) {
                    chain.withReadTimeout(65, TimeUnit.SECONDS)
                } else {
                    chain
                }
                requestChain.proceed(request)
            }
            .build()
    }

    private val tokenSafePlaybackHttpClient: OkHttpClient by lazy { playbackHttpClient.tokenSafe() }

    private val tokenSafeLoopbackPlaybackHttpClient: OkHttpClient by lazy { loopbackPlaybackHttpClient.tokenSafe() }

    private fun OkHttpClient.tokenSafe(): OkHttpClient =
        newBuilder()
            .sslSocketFactory(tokenSafeSslContext.socketFactory, tokenSafeTrustManager)
            .hostnameVerifier(tokenSafeHostnameVerifier)
            .build()

    internal fun httpClientFor(headers: Map<String, String>, useLongReadTimeout: Boolean): OkHttpClient =
        when {
            carriesCredentials(headers) && useLongReadTimeout -> tokenSafeLoopbackPlaybackHttpClient
            carriesCredentials(headers) -> tokenSafePlaybackHttpClient
            useLongReadTimeout -> loopbackPlaybackHttpClient
            else -> playbackHttpClient
        }

    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
    ): DataSource.Factory {
        val requestHeaders = sanitizeHeaders(defaultHeaders)
        val baseClient = httpClientFor(requestHeaders, useLongReadTimeout)
        val client = requestHeaders.headerValue("Authorization")?.let { authorization ->
            baseClient.newBuilder()
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    if (request.header("Authorization") == null) {
                        chain.proceed(
                            request.newBuilder()
                                .header("Authorization", authorization)
                                .build()
                        )
                    } else {
                        chain.proceed(request)
                    }
                }
                .build()
        } ?: baseClient

        return OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(requestHeaders)
            if (requestHeaders.headerValue("User-Agent") == null) {
                setUserAgent(DEFAULT_USER_AGENT)
            }
        }
    }

    fun createDataSourceFactory(
        context: Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
    ): DataSource.Factory {
        return DefaultDataSource.Factory(
            context,
            createHttpDataSourceFactory(defaultHeaders, useLongReadTimeout),
        )
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null,
    ): HttpURLConnection {
        val mergedHeaders = withDefaultUserAgent(headers)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                if (carriesCredentials(mergedHeaders)) {
                    sslSocketFactory = tokenSafeSslContext.socketFactory
                    hostnameVerifier = tokenSafeHostnameVerifier
                } else {
                    sslSocketFactory = sslContext.socketFactory
                    hostnameVerifier = playbackHostnameVerifier
                }
            }
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", mergedHeaders.headerValue("User-Agent") ?: DEFAULT_USER_AGENT)
            mergedHeaders.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isBlank() || value.isBlank() || key.equals("Range", ignoreCase = true)) {
                null
            } else {
                key to value
            }
        }.toMap()

    private val credentialHeaders = setOf("authorization", "proxy-authorization", "cookie")
    private val credentialHeaderParts = listOf("token", "api-key", "apikey", "api_key", "x-api", "auth", "password", "secret", "session")

    /** True when a header carries something that identifies or authorizes the user. */
    internal fun carriesCredentials(headers: Map<String, String>): Boolean =
        headers.any { (key, value) ->
            val name = key.trim().lowercase()
            value.isNotBlank() && (name in credentialHeaders || credentialHeaderParts.any { it in name })
        }

    /** The device itself or the local network: localhost, private and link-local addresses, .local/.lan names. */
    internal fun isLocalNetworkHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (listOf(".local", ".lan", ".home.arpa", ".internal").any { host.endsWith(it) }) return true
        val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(host)
        if (ipv4 != null) {
            val (a, b) = ipv4.destructured.toList().take(2).map { it.toInt() }
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (':' in host) {
            return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
        }
        return false
    }

    /** Checks server certificates like the system, except for hosts on the local network. */
    internal class TokenSafeTrustManager(private val system: X509TrustManager) : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            system.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            system.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            system.checkServerTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            val host = (socket as? SSLSocket)?.handshakeSession?.peerHost ?: socket?.inetAddress?.hostAddress
            if (host != null && PlayerPlaybackNetworking.isLocalNetworkHost(host)) return
            val extended = system as? X509ExtendedTrustManager
            if (extended != null) extended.checkServerTrusted(chain, authType, socket) else system.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            checkServerTrustedForHost(chain, authType, engine?.peerHost)
        }

        internal fun checkServerTrustedForHost(chain: Array<out X509Certificate>?, authType: String?, host: String?) {
            if (host != null && PlayerPlaybackNetworking.isLocalNetworkHost(host)) return
            system.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    private fun isLoopbackHost(host: String): Boolean = when (host.lowercase()) {
        "127.0.0.1", "localhost", "::1" -> true
        else -> false
    }

    private fun withDefaultUserAgent(headers: Map<String, String>): Map<String, String> {
        val sanitized = sanitizeHeaders(headers)
        if (sanitized.headerValue("User-Agent") != null) return sanitized
        return DEFAULT_STREAM_HEADERS + sanitized
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}
