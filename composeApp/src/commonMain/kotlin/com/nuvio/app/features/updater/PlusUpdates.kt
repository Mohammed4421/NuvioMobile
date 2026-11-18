package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig

/**
 * Nuvio Plus builds follow the releases of their own fork instead of the official app:
 * - stable releases are tagged `custom-<build>` (not marked as pre-release);
 * - beta releases are tagged `custom-<build>-beta` and marked as pre-release.
 * Build numbers are shared by both channels, so a higher number is always a newer build.
 */
internal object PlusUpdates {
    private val tagPattern = Regex("^custom-(\\d+)(-beta)?$")

    val enabled: Boolean
        get() = PlusUpdateConfig.REPO.isNotBlank()

    val releasesUrl: String
        get() = "https://api.github.com/repos/${PlusUpdateConfig.REPO}/releases?per_page=50"

    /** The channel this build was made for; the default until the user picks one. */
    val buildChannel: UpdateChannel
        get() = if (PlusUpdateConfig.CHANNEL == "beta") UpdateChannel.BETA else UpdateChannel.STABLE

    fun buildNumber(tag: String?): Int? =
        tag?.trim()?.let(tagPattern::matchEntire)?.groupValues?.get(1)?.toIntOrNull()

    fun isBetaTag(tag: String?): Boolean =
        tag?.trim()?.let(tagPattern::matchEntire)?.groupValues?.get(2)?.isNotEmpty() == true

    fun isNewer(tag: String?, currentBuild: Int = PlusUpdateConfig.BUILD): Boolean =
        (buildNumber(tag) ?: return false) > currentBuild

    /**
     * The newest release the channel accepts. Stable takes only stable releases; beta also takes
     * beta releases. Drafts, failed builds and sync reports never qualify.
     */
    fun latest(releases: List<GitHubReleaseDto>, channel: UpdateChannel = UpdateChannel.STABLE): GitHubReleaseDto? =
        releases
            .asSequence()
            .filterNot { it.draft }
            .filter { buildNumber(it.tagName) != null }
            .filter { release ->
                val beta = isBetaTag(release.tagName)
                when (channel) {
                    // A stable release must not be a pre-release, and a beta tag is never stable.
                    UpdateChannel.STABLE -> !beta && !release.prerelease
                    UpdateChannel.BETA -> !beta || release.prerelease
                }
            }
            .maxByOrNull { buildNumber(it.tagName) ?: 0 }
}

/** Decides whether a release tag is newer than the installed app. */
internal fun interface UpdateVersionPolicy {
    fun isNewer(tag: String): Boolean

    companion object {
        /** Official Nuvio: semantic versions against the app's version name. */
        val Official = UpdateVersionPolicy { tag -> VersionUtils.isRemoteNewer(tag, AppVersionConfig.VERSION_NAME) }

        /** Nuvio Plus: `custom-<build>` tags against this build's number. */
        fun plus(currentBuild: Int = PlusUpdateConfig.BUILD) = UpdateVersionPolicy { tag -> PlusUpdates.isNewer(tag, currentBuild) }

        fun forThisBuild(): UpdateVersionPolicy = if (PlusUpdates.enabled) plus() else Official
    }
}
