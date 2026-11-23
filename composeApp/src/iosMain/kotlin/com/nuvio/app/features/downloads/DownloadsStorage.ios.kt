package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    actual fun loadPayload(profileIndex: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey, profileIndex))

    actual fun removePayload(profileIndex: Int) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(payloadKey, profileIndex))
    }

    actual fun loadExtra(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("downloads_extra_$key")

    actual fun saveExtra(key: String, value: String?) {
        if (value == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey("downloads_extra_$key")
        } else {
            NSUserDefaults.standardUserDefaults.setObject(value, forKey = "downloads_extra_$key")
        }
    }
}
