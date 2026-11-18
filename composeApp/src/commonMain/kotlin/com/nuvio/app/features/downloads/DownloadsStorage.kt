package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)

    /** Device-wide download settings and the season queue (not tied to a profile). */
    fun loadExtra(key: String): String?

    fun saveExtra(key: String, value: String?)
}
