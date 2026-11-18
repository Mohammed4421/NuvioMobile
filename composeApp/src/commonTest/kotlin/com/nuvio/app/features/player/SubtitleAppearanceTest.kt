package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleAppearanceTest {
    @Test
    fun oldAndInvalidSettingsKeepExistingAppearance() {
        listOf(null, "", "{", "null").forEach { assertEquals(SubtitleAppearance(), decodeSubtitleAppearance(it)) }
        assertFalse(SubtitleStyleState.DEFAULT.usesStyledOverlay)
        assertEquals(SubtitleAppearance(shadowEnabled = true), decodeSubtitleAppearance("""{"shadowEnabled":true,"future":1}"""))
    }

    @Test
    fun syncedValuesAreBoundedAndCannotReferenceFiles() {
        val style = decodeSubtitleAppearance("""{"fontId":"../../font.ttf","backgroundRadius":999,"shadowOpacity":-1,"shadowSize":999}""")
        assertEquals("default", style.fontId)
        assertEquals(24, style.backgroundRadius)
        assertEquals(0, style.shadowOpacity)
        assertEquals(8, style.shadowSize)
    }

    @Test
    fun resetKeepsSubtitleSelectionPreferences() {
        val reset = SubtitleStyleState(
            stripSdh = true, useForcedSubtitles = true, showOnlyPreferredLanguages = true,
            backgroundColor = Color.Red, appearance = SubtitleAppearance(fontId = "serif", shadowEnabled = true),
        ).resetAppearance()
        assertTrue(reset.stripSdh && reset.useForcedSubtitles && reset.showOnlyPreferredLanguages)
        assertEquals(SubtitleStyleState.DEFAULT.backgroundColor, reset.backgroundColor)
        assertEquals(SubtitleAppearance(), reset.appearance)
    }

    @Test
    fun styledAndImageTracksStayWithTheirNativeRenderer() {
        listOf("ass", "ssa", "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "", "unknown").forEach {
            assertFalse(SubtitleRendering.supportsTextOverlay(it), it)
        }
        listOf("subrip", "webvtt", "mov_text", "SRT").forEach {
            assertTrue(SubtitleRendering.supportsTextOverlay(it), it)
        }
    }

    @Test
    fun fontImportRejectsTruncatedTablesAndUnsupportedContainers() {
        val truncated = byteArrayOf(0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        assertFalse(isSupportedSubtitleFont("font.ttf", truncated))
        assertFalse(isSupportedSubtitleFont("font.otf", "this is not a font".encodeToByteArray()))
        assertFalse(isSupportedSubtitleFont("font.woff", truncated))
        val overflowing = truncated + ByteArray(16) { -1 }
        assertFalse(isSupportedSubtitleFont("font.ttf", overflowing))
    }

    @Test
    fun brightnessIsBoundedAndDimsColorsWithoutTouchingAlpha() {
        assertEquals(SUBTITLE_BRIGHTNESS_MAX, SubtitleAppearance().brightness)
        assertEquals(SUBTITLE_BRIGHTNESS_MIN, decodeSubtitleAppearance("""{"brightness":3}""").brightness)
        assertEquals(SUBTITLE_BRIGHTNESS_MAX, decodeSubtitleAppearance("""{"brightness":500}""").brightness)
        val dimmed = Color(1f, 1f, 1f, 0.8f).dimmedBy(0.5f)
        assertEquals(0.5f, dimmed.red, 0.01f)
        assertEquals(0.8f, dimmed.alpha, 0.01f)
        assertEquals(Color.White, Color.White.dimmedBy(1f))
    }

    @Test
    fun deviceSubtitleFileNamesWithoutLanguageStayUnknown() {
        assertEquals("", guessLanguageFromSubtitleFileName("Movie.2020.1080p.WEB.srt"))
        assertEquals("", guessLanguageFromSubtitleFileName("subtitle.srt"))
        assertEquals("", guessLanguageFromSubtitleFileName("noextension"))
    }

    @Test
    fun outlineWidthOutsideRangeFallsBackToDefault() {
        assertEquals(2, normalizeSubtitleOutlineWidth(55))
        assertEquals(2, normalizeSubtitleOutlineWidth(-1))
        assertEquals(2, normalizeSubtitleOutlineWidth(null))
        assertEquals(4, normalizeSubtitleOutlineWidth(4))
    }
}
