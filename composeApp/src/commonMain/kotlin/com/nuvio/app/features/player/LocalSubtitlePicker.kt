package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

/** A subtitle file picked from the device, already copied to app storage as UTF-8. */
internal data class PickedLocalSubtitle(
    val url: String,
    val fileName: String,
)

/** Largest subtitle file accepted from the device. */
internal const val MaxLocalSubtitleBytes = 10 * 1024 * 1024

/**
 * Returns a launcher that opens the system file picker for a subtitle file, or null when the
 * platform does not support it (the button is hidden then).
 */
@Composable
internal expect fun rememberLocalSubtitlePicker(
    onResult: (Result<PickedLocalSubtitle>) -> Unit,
): (() -> Unit)?

internal fun PickedLocalSubtitle.toAddonSubtitle(sourceLabel: String): AddonSubtitle =
    AddonSubtitle(
        id = "device:$url",
        url = url,
        language = guessLanguageFromSubtitleFileName(fileName),
        display = fileName,
        addonName = sourceLabel,
    )

/**
 * Reads a language tag such as `movie.ar.srt` or `movie.eng.srt` from the file name. Returns an
 * empty string (shown as "Unknown") when the name carries none.
 */
internal fun guessLanguageFromSubtitleFileName(fileName: String): String {
    val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
    val lastToken = stem.split('.', '_', '-', ' ')
        .lastOrNull { it.isNotBlank() }
        ?.lowercase()
        ?: return ""
    if (lastToken.length !in 2..3 || !lastToken.all { it in 'a'..'z' }) return ""
    // Only accept tokens that map to a language the app can name, so "web" or "hd" stay unknown.
    return normalizeLanguageCode(lastToken)?.takeIf { languageLabelResForCode(it) != null } ?: ""
}
