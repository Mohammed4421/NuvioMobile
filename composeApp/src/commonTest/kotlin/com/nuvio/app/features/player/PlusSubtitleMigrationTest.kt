package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** Subtitle settings saved by the official app or synced from an account must load in Nuvio Plus. */
class PlusSubtitleMigrationTest {

    @Test
    fun appearanceWithoutBrightnessLoadsAtFullBrightness() {
        val appearance = decodeSubtitleAppearance("""{"fontId":"default","backgroundRadius":8}""")
        assertEquals(SUBTITLE_BRIGHTNESS_MAX, appearance.brightness)
        assertEquals(8, appearance.backgroundRadius)
    }

    @Test
    fun outOfRangeBrightnessIsClamped() {
        assertEquals(SUBTITLE_BRIGHTNESS_MIN, decodeSubtitleAppearance("""{"brightness":0}""").brightness)
        assertEquals(SUBTITLE_BRIGHTNESS_MAX, decodeSubtitleAppearance("""{"brightness":400}""").brightness)
    }

    @Test
    fun brokenAppearanceFallsBackToDefaults() {
        assertEquals(SubtitleAppearance(), decodeSubtitleAppearance("{oops"))
        assertEquals(SubtitleAppearance(), decodeSubtitleAppearance(null))
    }

    @Test
    fun appearanceRoundTrips() {
        val appearance = SubtitleAppearance(brightness = 55, backgroundRadius = 12)
        assertEquals(appearance, decodeSubtitleAppearance(appearance.encode()))
    }

    @Test
    fun outlineWidthWrittenByTheEarlierModificationIsRepaired() {
        // That build stored brightness (up to 100) as the outline width; sync spread it to accounts.
        assertEquals(2, normalizeSubtitleOutlineWidth(70))
        assertEquals(3, normalizeSubtitleOutlineWidth(3))
    }

    @Test
    fun widerPositionRangeKeepsOldValues() {
        assertEquals(600, SUBTITLE_BOTTOM_OFFSET_MAX)
        assertEquals(150, 150.coerceIn(0, SUBTITLE_BOTTOM_OFFSET_MAX))
    }
}
