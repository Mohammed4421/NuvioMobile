package com.nuvio.app.features.player

import com.nuvio.app.features.autosync.AutoSyncPlayerController
import com.nuvio.app.features.autosync.AutoSyncSubtitleCandidate

internal fun PlayerEngineController.setAutoSyncSubtitleCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
) {
    (this as? AutoSyncPlayerController)?.setAutoSyncSubtitleCandidates(candidates)
}

internal fun PlayerEngineController.setSubtitleUriWithAutoSync(url: String) {
    // Local files (picked from the device or downloaded) are attached as they are: AutoSync reads over HTTP.
    val autoSync = (this as? AutoSyncPlayerController)?.takeUnless { url.isLocalSubtitleUri() }
    if (autoSync != null) {
        autoSync.setSubtitleUriWithAutoSync(url)
    } else {
        setSubtitleUri(url)
    }
}

internal fun PlayerEngineController.setSubtitleUriWithSelectedAutoSync(url: String) {
    val autoSync = (this as? AutoSyncPlayerController)?.takeUnless { url.isLocalSubtitleUri() }
    if (autoSync != null) {
        autoSync.setSubtitleUriWithSelectedAutoSync(url)
    } else {
        setSubtitleUri(url)
    }
}

internal fun PlayerEngineController.setAutoSyncAppliedListener(
    listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
) {
    (this as? AutoSyncPlayerController)?.setAutoSyncAppliedListener(listener)
}
