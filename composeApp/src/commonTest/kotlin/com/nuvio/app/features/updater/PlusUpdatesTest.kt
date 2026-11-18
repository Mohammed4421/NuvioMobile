package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlusUpdatesTest {

    @Test
    fun readsBuildNumberFromTag() {
        assertEquals(12, PlusUpdates.buildNumber("custom-12"))
        assertNull(PlusUpdates.buildNumber("build-failed-12"))
        assertNull(PlusUpdates.buildNumber("1.1.0"))
    }

    @Test
    fun newerOnlyWhenTheBuildNumberIsHigher() {
        assertTrue(PlusUpdates.isNewer("custom-11", currentBuild = 10))
        assertFalse(PlusUpdates.isNewer("custom-10", currentBuild = 10))
        assertFalse(PlusUpdates.isNewer("custom-9", currentBuild = 10))
    }

    @Test
    fun latestSkipsFailedDraftAndForeignReleases() {
        val releases = listOf(
            GitHubReleaseDto(tagName = "custom-9"),
            GitHubReleaseDto(tagName = "build-failed-12", prerelease = true),
            GitHubReleaseDto(tagName = "custom-11"),
            GitHubReleaseDto(tagName = "custom-13", draft = true),
            GitHubReleaseDto(tagName = "1.2.0"),
        )
        assertEquals("custom-11", PlusUpdates.latest(releases)?.tagName)
    }
}

class PlusUpdateChannelsTest {
    private val releases = listOf(
        GitHubReleaseDto(tagName = "custom-10"),
        GitHubReleaseDto(tagName = "custom-12-beta", prerelease = true),
        GitHubReleaseDto(tagName = "custom-13", prerelease = true),
        GitHubReleaseDto(tagName = "build-failed-14", prerelease = true),
        GitHubReleaseDto(tagName = "sync-review-1.1.0", prerelease = true),
    )

    @Test
    fun stableTakesOnlyStableReleases() {
        assertEquals("custom-10", PlusUpdates.latest(releases, UpdateChannel.STABLE)?.tagName)
    }

    @Test
    fun betaAlsoTakesBetaAndPreReleases() {
        // custom-13 is marked as a pre-release: Beta takes it, Stable does not.
        assertEquals("custom-13", PlusUpdates.latest(releases, UpdateChannel.BETA)?.tagName)
        assertEquals("custom-12-beta", PlusUpdates.latest(releases.filterNot { it.tagName == "custom-13" }, UpdateChannel.BETA)?.tagName)
    }

    @Test
    fun betaTagsAreRecognised() {
        assertEquals(12, PlusUpdates.buildNumber("custom-12-beta"))
        assertTrue(PlusUpdates.isBetaTag("custom-12-beta"))
        assertFalse(PlusUpdates.isBetaTag("custom-12"))
    }

    @Test
    fun onlyAnApkForThisDeviceIsOffered() {
        val assets = listOf(
            GitHubAssetDto(name = "NuvioPlus-build12-x86_64.apk", browserDownloadUrl = "x"),
            GitHubAssetDto(name = "NuvioPlus-build12-arm64-v8a.apk", browserDownloadUrl = "a"),
        )
        assertEquals("a", AppUpdaterRepository.chooseCompatibleApkAsset(assets, listOf("arm64-v8a"))?.browserDownloadUrl)
        assertNull(AppUpdaterRepository.chooseCompatibleApkAsset(assets, listOf("armeabi-v7a")))
    }
}
