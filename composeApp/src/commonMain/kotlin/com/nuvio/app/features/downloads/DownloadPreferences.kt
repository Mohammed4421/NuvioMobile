package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where new downloads are saved and on which network they run. Applies to downloads started afterwards. */
@Serializable
data class DownloadPreferencesState(
    /** Document tree URI of a folder the user picked; null saves inside app storage. */
    val folderUri: String? = null,
    /** Readable folder name for the settings row. */
    val folderLabel: String? = null,
    val wifiOnly: Boolean = false,
)

object DownloadPreferences {
    private const val storageKey = "download_preferences"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(DownloadPreferencesState())
    val state: StateFlow<DownloadPreferencesState> = _state.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _state.value = DownloadsStorage.loadExtra(storageKey)
            ?.let { runCatching { json.decodeFromString<DownloadPreferencesState>(it) }.getOrNull() }
            ?: DownloadPreferencesState()
    }

    fun current(): DownloadPreferencesState {
        ensureLoaded()
        return _state.value
    }

    fun setFolder(folderUri: String?, folderLabel: String?) {
        update { it.copy(folderUri = folderUri, folderLabel = folderLabel) }
    }

    fun setWifiOnly(enabled: Boolean) {
        update { it.copy(wifiOnly = enabled) }
    }

    private fun update(transform: (DownloadPreferencesState) -> DownloadPreferencesState) {
        ensureLoaded()
        val updated = transform(_state.value)
        _state.value = updated
        DownloadsStorage.saveExtra(storageKey, json.encodeToString(updated))
    }
}

/** A folder picked from the system picker, with permission kept across restarts. */
data class PickedDownloadFolder(
    val uri: String,
    val label: String,
)

/**
 * Returns a launcher for the system folder picker, or null when the platform cannot save
 * downloads outside app storage (the option is hidden then).
 */
@Composable
internal expect fun rememberDownloadFolderPicker(
    onResult: (Result<PickedDownloadFolder>) -> Unit,
): (() -> Unit)?
