package com.nuvio.app.features.player

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.features.autosync.SubtitleCharsetDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File


@Composable
internal actual fun rememberLocalSubtitlePicker(
    onResult: (Result<PickedLocalSubtitle>) -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestResult = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?.takeIf { it.isNotBlank() }
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: "subtitle.srt"
                    val bytes = requireNotNull(resolver.openInputStream(uri)) { "Cannot open $uri" }.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MaxLocalSubtitleBytes) { "Subtitle file is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    require(bytes.isNotEmpty()) { "Subtitle file is empty" }
                    // Store as UTF-8 so every renderer reads Arabic and other legacy encodings
                    // (for example Windows-1256) correctly.
                    val text = SubtitleCharsetDetector.decode(
                        bytes,
                        languageHint = guessLanguageFromSubtitleFileName(displayName).ifBlank { null },
                    )
                    require(text.isNotBlank()) { "Subtitle file is empty" }
                    val extension = requireNotNull(localSubtitleFileExtension(displayName, text)) {
                        "This file is not a subtitle (SRT, VTT, ASS, SSA or TTML)"
                    }
                    val target = localSubtitleTargetFile(context.cacheDir, displayName, extension)
                    target.parentFile?.mkdirs()
                    target.writeText(text, Charsets.UTF_8)
                    PickedLocalSubtitle(url = Uri.fromFile(target).toString(), fileName = displayName)
                }
            }
            latestResult.value(result)
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/**
 * A stable, ASCII-only file name in the cache with the subtitle's extension (from
 * [localSubtitleFileExtension]), which the players use to pick the subtitle format.
 */
private fun localSubtitleTargetFile(cacheDir: File, displayName: String, extension: String): File {
    val stem = displayName.substringBeforeLast('.', missingDelimiterValue = displayName)
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(60)
        .ifBlank { "subtitle" }
    val unique = System.currentTimeMillis().toString(36)
    return File(File(cacheDir, "device-subtitles"), "$stem-$unique.$extension")
}
