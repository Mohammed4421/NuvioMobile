package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.network.NetworkErrorInterceptor
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
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

    internal fun httpClientFor(
        headers: Map<String, String>,
        useLongReadTimeout: Boolean,
        url: HttpUrl? = null,
    ): OkHttpClient {
        val credentials = carriesCredentials(headers) || (url != null && urlCarriesCredentials(url))
        return when {
            credentials && useLongReadTimeout -> tokenSafeLoopbackPlaybackHttpClient
            credentials -> tokenSafePlaybackHttpClient
            useLongReadTimeout -> loopbackPlaybackHttpClient
            else -> playbackHttpClient
        }
    }

    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
    ): DataSource.Factory {
        val requestHeaders = sanitizeHeaders(defaultHeaders)
        return OkHttpDataSource.Factory(callFactoryFor(requestHeaders, useLongReadTimeout)).apply {
            setDefaultRequestProperties(requestHeaders)
            if (requestHeaders.headerValue("User-Agent") == null) {
                setUserAgent(DEFAULT_USER_AGENT)
            }
        }
    }

    /**
     * Picks the client per request, so a token in the URL (?token=, ?api_key=…) also gets a
     * verified certificate, and guards the stream's credentials on redirects (credentialGuard).
     */
    internal fun callFactoryFor(headers: Map<String, String>, useLongReadTimeout: Boolean): Call.Factory {
        val plain by lazy { httpClientFor(headers, useLongReadTimeout).guarded(headers) }
        val verifying by lazy { tokenSafeClient(useLongReadTimeout).guarded(headers) }
        return Call.Factory { request ->
            val credentials = carriesCredentials(headers) || urlCarriesCredentials(request.url)
            (if (credentials) verifying else plain).newCall(request)
        }
    }

    private fun tokenSafeClient(useLongReadTimeout: Boolean): OkHttpClient =
        if (useLongReadTimeout) tokenSafeLoopbackPlaybackHttpClient else tokenSafePlaybackHttpClient

    private fun OkHttpClient.guarded(headers: Map<String, String>): OkHttpClient =
        if (carriesCredentials(headers)) newBuilder().addNetworkInterceptor(credentialGuard(headers)).build() else this

    /**
     * Upstream re-adds the stream's Authorization after a redirect (credentialed HLS). That is kept
     * inside the same site (same host, or same registrable domain such as api.x.com -> cdn.x.com).
     * A redirect to another site or from https to http gets none of the stream's credential headers:
     * OkHttp already drops Authorization there, and Cookie or token headers are dropped here too.
     */
    internal fun credentialGuard(headers: Map<String, String>): Interceptor {
        val authorization = headers.headerValue("Authorization")
        val credentialNames = headers.keys.filter { isCredentialHeader(it) }
        return Interceptor { chain ->
            val request = chain.request()
            val origin = chain.call().request().url
            val builder = request.newBuilder()
            if (mayReceiveCredentials(origin, request.url)) {
                if (authorization != null && request.header("Authorization") == null) {
                    builder.header("Authorization", authorization)
                }
            } else {
                credentialNames.forEach { builder.removeHeader(it) }
            }
            chain.proceed(builder.build())
        }
    }

    /** The original request's site may receive its credentials; another site or an https->http downgrade may not. */
    internal fun mayReceiveCredentials(origin: HttpUrl, target: HttpUrl): Boolean {
        if (origin.isHttps && !target.isHttps) return false
        if (origin.host == target.host) return true
        val site = origin.topPrivateDomain() ?: return false
        return site == target.topPrivateDomain()
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
        val origin = url.toHttpUrlOrNull()
        val credentials = carriesCredentials(mergedHeaders) || (origin != null && urlCarriesCredentials(origin))
        if (!credentials || origin == null) {
            return connect(url, mergedHeaders, credentials, method, connectTimeoutMs, readTimeoutMs, range, followRedirects = true)
        }
        // With credentials, redirects are followed here so the same rule as the player applies.
        var current: HttpUrl = origin
        repeat(MAX_REDIRECTS + 1) {
            val sendHeaders = if (mayReceiveCredentials(origin, current)) {
                mergedHeaders
            } else {
                mergedHeaders.filterKeys { !isCredentialHeader(it) }
            }
            val verify = carriesCredentials(sendHeaders) || urlCarriesCredentials(current)
            val connection = connect(current.toString(), sendHeaders, verify, method, connectTimeoutMs, readTimeoutMs, range, followRedirects = false)
            val code = connection.responseCode
            val next = connection.getHeaderField("Location")?.let { current.resolve(it) }
            if (code !in 300..399 || next == null) return connection
            connection.disconnect()
            current = next
        }
        throw java.io.IOException("Too many redirects")
    }

    private const val MAX_REDIRECTS = 10

    private fun connect(
        url: String,
        headers: Map<String, String>,
        verifyCertificate: Boolean,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String?,
        followRedirects: Boolean,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                if (verifyCertificate) {
                    sslSocketFactory = tokenSafeSslContext.socketFactory
                    hostnameVerifier = tokenSafeHostnameVerifier
                } else {
                    sslSocketFactory = sslContext.socketFactory
                    hostnameVerifier = playbackHostnameVerifier
                }
            }
            instanceFollowRedirects = followRedirects
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", headers.headerValue("User-Agent") ?: DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
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

    private fun isCredentialHeader(key: String): Boolean {
        val name = key.trim().lowercase()
        return name in credentialHeaders || credentialHeaderParts.any { it in name }
    }

    /** True when a header carries something that identifies or authorizes the user. */
    internal fun carriesCredentials(headers: Map<String, String>): Boolean =
        headers.any { (key, value) -> value.isNotBlank() && isCredentialHeader(key) }

    private val credentialQueryParts = listOf("token", "auth", "password", "passwd", "apikey", "secret", "session", "signature", "jwt")
    private val credentialQueryNames = setOf("sig", "key", "pass", "pwd")

    /** True when the URL itself carries a secret: user:password@ or a query such as ?token=, ?api_key=, ?password=. */
    internal fun urlCarriesCredentials(url: HttpUrl): Boolean {
        if (url.password.isNotEmpty()) return true
        return url.queryParameterNames.any { raw ->
            val name = raw.lowercase().replace("-", "").replace("_", "").replace(".", "")
            name in credentialQueryNames || credentialQueryParts.any { it in name }
        }
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
