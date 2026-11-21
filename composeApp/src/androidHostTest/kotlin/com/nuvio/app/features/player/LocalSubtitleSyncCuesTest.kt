package com.nuvio.app.features.player

import android.app.Application
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Manual subtitle sync loads the cues of the selected subtitle with httpGetTextWithHeaders. A
 * subtitle picked from the device is a file: URL in the app cache; it must load from disk, with no
 * network, and give the same cues; an add-on subtitle still needs the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalSubtitleSyncCuesTest {
    private val srt = "1\n00:00:01,000 --> 00:00:02,500\nمرحبا\n\n2\n00:00:03,000 --> 00:00:04,000\nHello\n"

    @Test
    fun aDeviceSubtitleLoadsItsCuesFromDisk() {
        val file = File.createTempFile("device-subtitle", ".srt").apply { writeText(srt, Charsets.UTF_8); deleteOnExit() }
        for (url in listOf(file.toURI().toString(), "file://${file.absolutePath}")) {
            val body = runBlocking { httpGetTextWithHeaders(url, mapOf("User-Agent" to "x")) }
            assertEquals(srt, body, url)
            val cues = PlayerSubtitleCueParser.parse(body, url)
            assertEquals(listOf("مرحبا", "Hello"), cues.map { it.text }, url)
            assertFalse(subtitleCuesNeedNetwork(url), "$url must load offline")
        }
    }

    @Test
    fun addOnSubtitlesNeedTheNetwork() {
        assertTrue(subtitleCuesNeedNetwork("https://subs.example.com/a.srt"))
        assertTrue(subtitleCuesNeedNetwork("http://10.0.0.2:8080/a.vtt"))
        assertFalse(subtitleCuesNeedNetwork("FILE:///data/user/0/x/cache/device-subtitles/a.srt"))
    }
}
