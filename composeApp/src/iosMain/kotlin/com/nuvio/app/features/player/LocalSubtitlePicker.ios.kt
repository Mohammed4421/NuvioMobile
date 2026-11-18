package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberLocalSubtitlePicker(
    onResult: (Result<PickedLocalSubtitle>) -> Unit,
): (() -> Unit)? = null
