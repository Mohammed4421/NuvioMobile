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
