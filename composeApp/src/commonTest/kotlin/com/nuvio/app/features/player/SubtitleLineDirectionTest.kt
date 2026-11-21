package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** Independent review GEM-SUB-01: an Arabic line that starts with a Latin word still reads right to left. */
class SubtitleLineDirectionTest {
    @Test
    fun arabicLinesStartRightToLeftEvenAfterALatinWord() {
        assertEquals("‏Netflix تقدم.", withRightToLeftLineMarks("Netflix تقدم."))
        assertEquals("‏مرحبا.", withRightToLeftLineMarks("مرحبا."))
    }

    @Test
    fun otherLinesAndMarkedLinesAreLeftAlone() {
        assertEquals("Hello world.\n‏مرحبا.", withRightToLeftLineMarks("Hello world.\nمرحبا."))
        assertEquals("‫مرحبا.‬", withRightToLeftLineMarks("‫مرحبا.‬"))
        assertEquals("10:30", withRightToLeftLineMarks("10:30"))
    }
}
