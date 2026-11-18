package com.nuvio.app.features.updater

/**
 * Nuvio Plus builds follow the releases of their own fork ("custom-<build>" tags) instead of the
 * official app, so the updater offers the next Plus build and installs it over the current one.
 */
internal object PlusUpdates {
    private val tagPattern = Regex("^custom-(\\d+)$")

    val enabled: Boolean
        get() = PlusUpdateConfig.REPO.isNotBlank()

    val releasesUrl: String
        get() = "https://api.github.com/repos/${PlusUpdateConfig.REPO}/releases?per_page=30"

    fun buildNumber(tag: String?): Int? =
        tag?.trim()?.let(tagPattern::matchEntire)?.groupValues?.get(1)?.toIntOrNull()

    fun isNewer(tag: String?, currentBuild: Int = PlusUpdateConfig.BUILD): Boolean =
        (buildNumber(tag) ?: return false) > currentBuild

    /** The newest published Plus build; failed builds are pre-releases and are skipped. */
    fun latest(releases: List<GitHubReleaseDto>): GitHubReleaseDto? =
        releases
            .filterNot { it.draft || it.prerelease }
            .filter { buildNumber(it.tagName) != null }
            .maxByOrNull { buildNumber(it.tagName) ?: 0 }
}
