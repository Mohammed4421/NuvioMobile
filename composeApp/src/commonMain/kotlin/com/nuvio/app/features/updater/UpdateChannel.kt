package com.nuvio.app.features.updater

enum class UpdateChannel(val storedValue: String) {
    STABLE("stable"),
    BETA("beta");

    companion object {
        fun fromStoredValue(value: String?): UpdateChannel? = entries.firstOrNull {
            it.storedValue.equals(value, ignoreCase = true)
        }

        fun defaultForVersion(versionName: String): UpdateChannel =
            if (VersionUtils.isPrerelease(versionName)) BETA else STABLE

        /** Nuvio Plus builds start on the channel they were made for. */
        fun defaultForBuild(versionName: String): UpdateChannel =
            if (PlusUpdates.enabled) PlusUpdates.buildChannel else defaultForVersion(versionName)
    }
}
