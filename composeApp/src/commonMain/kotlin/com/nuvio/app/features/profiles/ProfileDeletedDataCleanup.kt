package com.nuvio.app.features.profiles

import co.touchlab.kermit.Logger
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsCodec
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.downloads.SeasonDownloadQueue
import com.nuvio.app.features.player.PlayerSettingsStorage

/**
 * Nuvio Plus: a new profile takes the smallest free index, so it can get the index of a deleted
 * profile. Without this, it would find that profile's download list, its waiting season episodes and
 * its subtitle appearance (journeys-profiles.md, finding F1; owner decision 2026-11-23: clear them
 * when the profile is deleted).
 *
 * Cleared: the stored download list of the index, the transfers and partial files of its unfinished
 * downloads, its season queue episodes and holds, its subtitle appearance. Kept: finished downloaded
 * files on disk (removing a user's files needs its own decision).
 */
internal object ProfileDeletedDataCleanup {
    private val log = Logger.withTag("ProfileDeletedDataCleanup")

    fun onProfileDeleted(profileIndex: Int) {
        if (profileIndex == ProfileRepository.activeProfileId) {
            // Still the active profile: its list is in memory and would be written back. Not cleared.
            log.w { "Profile $profileIndex is still active; its local Plus data is kept" }
            return
        }
        DownloadsStorage.loadPayload(profileIndex)?.let { payload ->
            DownloadsCodec.decodeItems(payload)
                .filter { it.status != DownloadStatus.Completed }
                .forEach { DownloadsPlatformDownloader.removePartialFile(it.fileName) }
            DownloadsStorage.removePayload(profileIndex)
        }
        SeasonDownloadQueue.forgetProfile(profileIndex)
        PlayerSettingsStorage.removeSubtitleAppearance(profileIndex)
    }
}
