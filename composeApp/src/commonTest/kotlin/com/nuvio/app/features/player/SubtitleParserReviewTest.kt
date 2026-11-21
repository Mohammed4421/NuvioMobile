package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** Independent review GEM-SUB-02: long ASS headers, vector drawings and numeric entities. */
class SubtitleParserReviewTest {
    @Test
    fun anAssFileWithALongHeaderIsStillAss() {
        val styles = (1..200).joinToString("\n") { "Style: S$it,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1" }
        val ass = "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n$styles\n\n[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,S1,,0,0,0,,Hello"
        assertEquals("ass", PlayerSubtitleCueParser.fileExtension(ass, sourceUrl = "https://addon.example/subtitle/42"))
        assertEquals(listOf("Hello"), PlayerSubtitleCueParser.parse(ass, "subtitle.ass").map { it.text })
    }

    @Test
    fun vectorDrawingsAreNotShownAsText() {
        val ass = "[Script Info]\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\p1}m 0 0 l 100 0 100 100{\\p0}Sign text"
        assertEquals(listOf("Sign text"), PlayerSubtitleCueParser.parse(ass, "subtitle.ass").map { it.text })
    }

    @Test
    fun numericEntitiesAreDecoded() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\ndon&#39;t &apos;stop&apos; &#x2014; caf&#233;\n"
        assertEquals(listOf("don't 'stop' — café"), PlayerSubtitleCueParser.parse(srt, "subtitle.srt").map { it.text })
    }
}
