package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberDownloadFolderPicker(
    onResult: (Result<PickedDownloadFolder>) -> Unit,
): (() -> Unit)? = null
