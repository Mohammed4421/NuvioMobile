package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)

    /** The stored download list of [profileIndex], whichever profile is active. */
    fun loadPayload(profileIndex: Int): String?

    /** Removes the stored download list of [profileIndex] (a deleted profile). */
    fun removePayload(profileIndex: Int)

    /** Device-wide download settings and the season queue (not tied to a profile). */
    fun loadExtra(key: String): String?

    fun saveExtra(key: String, value: String?)
}
