package com.nuvio.app.features.addons

import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An add-on offered by a link (stremio://…, nuvio://…) from a web page or another app. It is only
 * installed after the user confirms it: an add-on sees what is watched and supplies the streams,
 * so no page may add one silently.
 */
internal class AddonLinkConfirmation {
    private val pending = MutableStateFlow<String?>(null)

    /** The manifest URL waiting for the user's answer, or null. */
    val manifestUrl: StateFlow<String?> = pending.asStateFlow()

    fun request(manifestUrl: String) {
        pending.value = manifestUrl
    }

    fun dismiss() {
        pending.value = null
    }

    /** Installs the waiting add-on with [install]; null when nothing was waiting. */
    suspend fun <T> confirm(install: suspend (String) -> T): T? {
        val url = pending.value ?: return null
        pending.value = null
        return install(url)
    }

    companion object {
        /** The address the user is asked about: host (and port) only, never path or credentials. */
        fun displayAddress(manifestUrl: String): String =
            runCatching { Url(manifestUrl) }.getOrNull()
                ?.let { url -> if (url.specifiedPort > 0 && url.specifiedPort != url.protocol.defaultPort) "${url.host}:${url.specifiedPort}" else url.host }
                ?.takeIf { it.isNotBlank() }
                ?: manifestUrl.take(80)
    }
}
