package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SubtitleAppearance(
    val fontId: String = "default",
    val backgroundRadius: Int = 0,
    val shadowEnabled: Boolean = false,
    val shadowOpacity: Int = 75,
    val shadowSize: Int = 2,
    /** Subtitle brightness in percent (10..100). 100 keeps the original colors. */
    val brightness: Int = SUBTITLE_BRIGHTNESS_MAX,
) {
    fun normalized(): SubtitleAppearance = copy(
        fontId = fontId.takeIf { it in BuiltInSubtitleFonts || it.matches(Regex("custom-[a-f0-9-]{36}")) } ?: "default",
        backgroundRadius = backgroundRadius.coerceIn(0, 24),
        shadowOpacity = shadowOpacity.coerceIn(0, 100),
        shadowSize = shadowSize.coerceIn(1, 8),
        brightness = brightness.coerceIn(SUBTITLE_BRIGHTNESS_MIN, SUBTITLE_BRIGHTNESS_MAX),
    )
}

const val SUBTITLE_BRIGHTNESS_MIN = 10
const val SUBTITLE_BRIGHTNESS_MAX = 100

/** Brightness as a 0.1..1 multiplier. */
internal val SubtitleStyleState.brightnessFactor: Float
    get() = appearance.brightness.coerceIn(SUBTITLE_BRIGHTNESS_MIN, SUBTITLE_BRIGHTNESS_MAX) / 100f

/** Scales the color channels by [factor] and keeps alpha, so dimmed text stays opaque. */
internal fun Color.dimmedBy(factor: Float): Color {
    if (factor >= 1f) return this
    val f = factor.coerceIn(0f, 1f)
    return Color(red = red * f, green = green * f, blue = blue * f, alpha = alpha)
}

internal val BuiltInSubtitleFonts = listOf("default", "sans", "serif", "mono")
private val subtitleAppearanceJson = Json { ignoreUnknownKeys = true }

internal fun decodeSubtitleAppearance(value: String?): SubtitleAppearance =
    value?.let { runCatching { subtitleAppearanceJson.decodeFromString<SubtitleAppearance>(it).normalized() }.getOrNull() }
        ?: SubtitleAppearance()

internal fun SubtitleAppearance.encode(): String = subtitleAppearanceJson.encodeToString(normalized())

internal val SubtitleStyleState.usesStyledOverlay: Boolean
    get() = appearance.fontId != "default" || backgroundColor.alpha > 0f || appearance.shadowEnabled

object SubtitleRendering {
    fun supportsTextOverlay(codec: String): Boolean = codec.lowercase() in setOf(
        "subrip", "srt", "webvtt", "text", "mov_text", "microdvd", "mpl2", "subviewer", "subviewer1", "sami", "realtext",
    )
}

internal fun SubtitleStyleState.resetAppearance(): SubtitleStyleState = SubtitleStyleState.DEFAULT.copy(
    stripSdh = stripSdh,
    useForcedSubtitles = useForcedSubtitles,
    showOnlyPreferredLanguages = showOnlyPreferredLanguages,
)
