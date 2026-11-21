package com.nuvio.app.features.downloads

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class DownloadStatus { Downloading, Paused, Completed, Failed }
enum class DownloadEnqueueResult { Started, Replaced, MissingUrl, UnsupportedFormat }

data class DownloadItem(val id: String, val parentMetaId: String, val seasonNumber: Int?, val episodeNumber: Int?, val status: DownloadStatus)
data class DownloadsUiState(val items: List<DownloadItem> = emptyList())

object DownloadsClock {
    private var t = 1_700_000_000_000L
    fun nowEpochMs(): Long = t++
}

/** Device storage, kept in a folder so it outlives a "process" (a class loader) like the app's files. */
object DownloadsStorage {
    private val dir = File(System.getProperty("harness.store")).also { it.mkdirs() }
    fun loadExtra(key: String): String? = File(dir, key).takeIf { it.exists() }?.readText()
    fun saveExtra(key: String, value: String) { File(dir, key).writeText(value) }
}

/**
 * Per-profile downloads, like the real repository: only the active profile's list is loaded, and
 * onProfileChanged loads the new one synchronously. Every enqueue is logged with the profile it went to.
 */
object DownloadsRepository {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState
    private var loadedFor: Int? = null
    internal val isLoaded: Boolean get() = loadedFor != null

    private fun file(profile: Int) = File(System.getProperty("harness.store"), "downloads_$profile")
    private fun read(profile: Int): List<DownloadItem> =
        file(profile).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.map {
            val p = it.split("|")
            DownloadItem(p[0], p[1], p[2].toInt(), p[3].toInt(), DownloadStatus.valueOf(p[4]))
        }.orEmpty()
    private fun write(profile: Int, items: List<DownloadItem>) =
        file(profile).writeText(items.joinToString("\n") { "${it.id}|${it.parentMetaId}|${it.seasonNumber}|${it.episodeNumber}|${it.status}" })

    fun ensureLoaded() {
        if (loadedFor != null) return
        loadFromDisk()
        SeasonDownloadQueue.ensureStarted()
    }
    fun onProfileChanged() = loadFromDisk()
    private fun loadFromDisk() {
        loadedFor = ProfileRepository.activeProfileId
        _uiState.value = DownloadsUiState(read(loadedFor!!))
    }

    fun findDownload(parentMetaId: String, seasonNumber: Int?, episodeNumber: Int?): DownloadItem? {
        ensureLoaded()
        return _uiState.value.items.firstOrNull { it.parentMetaId == parentMetaId && it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber }
    }

    val log = mutableListOf<String>()

    fun enqueueFromStream(
        contentType: String, videoId: String, parentMetaId: String, parentMetaType: String, title: String,
        logo: String?, poster: String?, background: String?, seasonNumber: Int?, episodeNumber: Int?,
        episodeTitle: String?, episodeThumbnail: String?, stream: StreamItem,
    ): DownloadEnqueueResult {
        ensureLoaded()
        val profile = loadedFor!!
        log += "P$profile:$parentMetaId:S${seasonNumber}E$episodeNumber"
        val current = _uiState.value.items
        val existing = current.firstOrNull { it.parentMetaId == parentMetaId && it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber }
        val item = DownloadItem("d${log.size}_P$profile", parentMetaId, seasonNumber, episodeNumber, DownloadStatus.Downloading)
        val updated = current.filterNot { it === existing } + item
        write(profile, updated)
        _uiState.value = DownloadsUiState(updated)
        return if (existing != null) DownloadEnqueueResult.Replaced else DownloadEnqueueResult.Started
    }

    /** The download finishes (test helper). */
    fun finish(downloadId: String) {
        val updated = _uiState.value.items.map { if (it.id == downloadId) it.copy(status = DownloadStatus.Completed) else it }
        write(loadedFor!!, updated)
        _uiState.value = DownloadsUiState(updated)
    }

    /** The user pauses or resumes a download (test helper). */
    fun setStatus(downloadId: String, status: DownloadStatus) {
        val updated = _uiState.value.items.map { if (it.id == downloadId) it.copy(status = status) else it }
        write(loadedFor!!, updated)
        _uiState.value = DownloadsUiState(updated)
    }

    /** Downloads stored for [profile], loaded or not. */
    fun storedFor(profile: Int): List<DownloadItem> = read(profile)
}
