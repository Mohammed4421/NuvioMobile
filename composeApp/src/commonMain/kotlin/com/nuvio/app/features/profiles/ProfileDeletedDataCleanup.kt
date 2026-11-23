package com.nuvio.app.features.profiles

import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsCodec
import com.nuvio.app.features.downloads.DownloadsRepository
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
    fun clearConfirmedSlot(profileIndex: Int) {
        val stored = DownloadsStorage.loadPayload(profileIndex)?.let(DownloadsCodec::decodeItems).orEmpty()
        val owned = DownloadsRepository.detachDeletedDownloads(stored, active = profileIndex == ProfileRepository.activeProfileId)
        owned.filter { it.status != DownloadStatus.Completed }
            .forEach(DownloadsPlatformDownloader::removeDeletedProfileTransfer)
        DownloadsStorage.removePayload(profileIndex)
        SeasonDownloadQueue.forgetProfile(profileIndex)
        PlayerSettingsStorage.removeSubtitleAppearance(profileIndex)
    }
}
