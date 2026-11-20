package com.nuvio.app.features.player

import com.nuvio.app.features.autosync.AutoSyncSubtitleCueParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Subtitles come from add-ons and are parsed while a download is prepared and in the player.
 * Malformed files (an unclosed tag, brace or paragraph repeated) made the parsers' regexes rescan
 * the rest of the file from every position: 60 KB of "<b " took 5 s, 900 KB minutes. Each case
 * here is 900 KB and must take well under a few seconds.
 */
class SubtitleParserWorstCaseTest {
    private val size = 300_000

    private val hostile = listOf(
        "unclosed tags (vtt)" to ("WEBVTT\n\n00:00.000 --> 00:01.000\n" + "<b ".repeat(size) to "a.vtt"),
        "unclosed paragraphs (ttml)" to ("<tt>" + "<p>".repeat(size) to "a.ttml"),
        "unclosed root tags (ttml)" to ("<tt " + "<tt ".repeat(size) to "a.ttml"),
        "unclosed override braces (ass)" to
            ("[Events]\nDialogue: 0,0:00:00.00,0:00:01.00,,,0,0,0,," + "{".repeat(size) to "a.ass"),
    )

    private fun assertQuick(label: String, parse: () -> Unit) {
        val mark = TimeSource.Monotonic.markNow()
        parse()
        val elapsed = mark.elapsedNow()
        assertTrue(elapsed < 5.seconds, "$label took $elapsed")
    }

    @Test
    fun malformedSubtitlesAreParsedQuicklyByThePlayerParser() {
        for ((label, input) in hostile) assertQuick("player: $label") { PlayerSubtitleCueParser.parse(input.first, input.second) }
    }

    @Test
    fun malformedSubtitlesAreParsedQuicklyByTheAutoSyncParser() {
        for ((label, input) in hostile) assertQuick("auto-sync: $label") { AutoSyncSubtitleCueParser.parse(input.first, input.second) }
    }

    @Test
    fun wellFormedFilesGiveTheSameCuesAsBefore() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:00:01.000" end="00:00:02.500">Hello<br/>world</p>
            <p begin="3s" end="4s"><span tts:color="red">Red</span> &amp; plain</p>
            </div></body></tt>"""
        assertEquals(
            listOf(SubtitleSyncCue(1_000, 2_500, "Hello\nworld"), SubtitleSyncCue(3_000, 4_000, "Red & plain")),
            PlayerSubtitleCueParser.parse(ttml, "a.ttml"),
        )
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\an8}{\\i1}Top{\\i0}\\Nline"
        assertEquals(listOf(SubtitleSyncCue(1_000, 2_000, "Top\nline")), PlayerSubtitleCueParser.parse(ass, "a.ass"))
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000\n<c.yellow><i>Hi</i></c> <00:00:01.500>there"
        assertEquals(listOf(SubtitleSyncCue(1_000, 2_000, "Hi there")), PlayerSubtitleCueParser.parse(vtt, "a.vtt"))
    }

    @Test
    fun anUnclosedTagNoLongerSwallowsTheTextAfterIt() {
        // Before, "<b " up to the next tag's ">" was removed, taking "Hello a" with it.
        val srt = "1\n00:00:01,000 --> 00:00:02,500\na<b>c<b Hello a<b>c\n"
        assertEquals("ac<b Hello ac", PlayerSubtitleCueParser.parse(srt, "a.srt").single().text)
    }
}
