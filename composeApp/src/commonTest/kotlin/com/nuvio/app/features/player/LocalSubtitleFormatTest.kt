package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A picked file is stored under the extension of its real format; a file that is no subtitle is refused. */
class LocalSubtitleFormatTest {
    private val srt = "1\n00:00:01,000 --> 00:00:02,500\nمرحبا بك\n"
    private val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000\nHello\n"
    private val ass = "[Script Info]\nTitle: x\n\n[Events]\n" +
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
        "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\an8}Top\n"
    private val ttml = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>" +
        "<p begin=\"00:00:01.000\" end=\"00:00:02.000\">Hi</p></div></body></tt>"

    @Test
    fun aSupportedExtensionIsKept() {
        assertEquals("srt", localSubtitleFileExtension("movie.ar.srt", srt))
        assertEquals("ssa", localSubtitleFileExtension("movie.SSA", ass))
        assertEquals("dfxp", localSubtitleFileExtension("movie.dfxp", ttml))
    }

    @Test
    fun anotherExtensionTakesTheFormatOfTheText() {
        // Before Build 15 each of these was stored as .srt.
        assertEquals("ass", localSubtitleFileExtension("movie.txt", ass))
        assertEquals("vtt", localSubtitleFileExtension("movie.txt", vtt))
        assertEquals("ttml", localSubtitleFileExtension("movie.xml", ttml))
        assertEquals("srt", localSubtitleFileExtension("movie", srt))
        assertEquals("srt", localSubtitleFileExtension("arabic.sub.txt", srt))
    }

    @Test
    fun textThatIsNoSubtitleIsRefused() {
        assertNull(localSubtitleFileExtension("notes.txt", "Shopping list\nmilk\nbread\n"))
        assertNull(localSubtitleFileExtension("report.pdf", "%PDF-1.7 binary"))
        assertNull(localSubtitleFileExtension("broken.txt", "WEBVTT\n\nno timing here\n"))
    }
}
