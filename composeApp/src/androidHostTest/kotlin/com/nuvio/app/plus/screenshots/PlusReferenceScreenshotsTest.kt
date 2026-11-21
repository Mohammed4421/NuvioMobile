package com.nuvio.app.plus.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.library.buildLibraryDownloadEntries
import com.nuvio.app.features.library.libraryDownloadsContent
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.features.settings.SubtitleAppearanceSettings
import com.nuvio.app.features.settings.playbackSettingsContent
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Build 15, P4-01: reference screenshots of Nuvio Plus screens before any visual change, in English
 * and Arabic (right to left), on a phone in portrait and a tablet in landscape. Each run writes
 * build/reports/plus-screenshots/<screen>-<device>.png (kept by CI as an artifact), so a later
 * change can be compared with these images. The checks below make sure each image really shows the
 * screen in the asked direction; they do not judge the look, which is reviewed by eye.
 *
 * One subclass per setup: Robolectric applies a class's @Config before the test activity starts, so
 * the language and direction are those of the device from the first frame (setting them inside a
 * test came too late: the activity had already started left to right).
 */
abstract class PlusReferenceScreenshots(private val device: String) {
    @get:Rule val compose = createComposeRule()
    private val savedLocale: Locale = Locale.getDefault()

    @BeforeTest fun setUp() {
        Locale.setDefault(if (device.startsWith("ar")) Locale.forLanguageTag("ar") else Locale.ENGLISH)
        PlayerSettingsStorage.initialize(RuntimeEnvironment.getApplication())
    }

    @After fun restoreLocale() { Locale.setDefault(savedLocale) }

    @Test fun subtitleAppearanceSettings() {
        shoot("subtitle-appearance") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SubtitleAppearanceSettings(SubtitleStyleState(), isTablet = device.endsWith("tablet"), onStyleChanged = {})
            }
        }
        // Colour codes stay left to right on Arabic screens ("#FFFFFFFF", not "FFFFFFFF#").
        val texts = shownTexts(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertTrue(texts.any { it == "\u2066#FFFFFFFF\u2069" }, "colour code not isolated left to right: $texts")
    }

    @Test fun libraryDownloads() = shoot("library-downloads") {
        val entries = buildLibraryDownloadEntries(
            items = listOf(
                item("film-running", null, DownloadStatus.Downloading, downloaded = 40, total = 100),
                item("film-paused", null, DownloadStatus.Paused, downloaded = 70, total = 100),
                item("show", 1, DownloadStatus.Completed),
                item("show", 2, DownloadStatus.Completed),
                item("film-done", null, DownloadStatus.Completed),
            ),
            queue = emptyList(),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            libraryDownloadsContent(
                entries = entries, activeCount = 2, queueCount = 0, columns = if (device.endsWith("tablet")) 5 else 3,
                onManageClick = {}, onEntryClick = {}, onEntryLongClick = {},
            )
        }
    }

    // The playback settings that shape watching (P4: screens most used around playback).
    @Test fun playbackSettings() = playbackPage("playback-settings", SettingsPage.Playback)
    @Test fun playbackPlayerSettings() = playbackPage("playback-player", SettingsPage.PlaybackPlayer)
    @Test fun playbackSubtitleSettings() = playbackPage("playback-subtitles", SettingsPage.PlaybackSubtitles)
    @Test fun playbackVideoSettings() = playbackPage("playback-video", SettingsPage.PlaybackVideo)
    @Test fun playbackAudioSettings() = playbackPage("playback-audio", SettingsPage.PlaybackAudio)

    private fun playbackPage(screen: String, page: SettingsPage) = shoot(screen) {
        LazyColumn(Modifier.fillMaxSize()) {
            playbackSettingsContent(isTablet = device.endsWith("tablet"), page = page, onPageChange = {})
        }
    }

    private fun shoot(screen: String, content: @Composable () -> Unit) {
        var direction: LayoutDirection? = null
        compose.setContent {
            direction = LocalLayoutDirection.current
            NuvioTheme { Surface(Modifier.fillMaxSize()) { content() } }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val expected = if (device.startsWith("ar")) LayoutDirection.Rtl else LayoutDirection.Ltr
        assertEquals(expected, direction, "$screen on $device is not laid out in the expected direction")
        assertTrue(distinctColours(bitmap) > 1, "$screen on $device rendered as a single colour")
        // Spec P4: the language shown, not only the device setting (P4-12). Arabic screens show
        // Arabic letters; English ones show none (a missing translation falls back to English).
        val texts = shownTexts(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        val arabic = texts.filter { text -> text.any { it in '\u0600'..'\u06FF' } }
        if (device.startsWith("ar")) {
            assertTrue(arabic.size * 2 >= texts.count { t -> t.any { it.isLetter() } },
                "$screen on $device: fewer than half of the labels are Arabic: $texts")
        } else {
            assertTrue(arabic.isEmpty(), "$screen on $device shows Arabic text: $arabic")
        }
        val folder = File("build/reports/plus-screenshots").apply { mkdirs() }
        File(folder, "$screen-$device.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shownTexts(node: SemanticsNode): List<String> =
        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.children.flatMap { shownTexts(it) }).filter { it.isNotBlank() }

    private fun distinctColours(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
            seen += bitmap.getPixel(x, y)
            if (seen.size > 1) return seen.size
        }
        return seen.size
    }

    private fun item(metaId: String, episode: Int?, status: DownloadStatus, downloaded: Long = 1, total: Long? = 1) = DownloadItem(
        id = "$metaId-${episode ?: 0}",
        contentType = if (episode == null) "movie" else "series",
        parentMetaId = metaId,
        parentMetaType = if (episode == null) "movie" else "series",
        videoId = "$metaId:${episode ?: 0}",
        title = metaId,
        seasonNumber = episode?.let { 1 },
        episodeNumber = episode,
        streamTitle = "stream",
        providerName = "addon",
        sourceUrl = "https://example.com/$metaId",
        localFileUri = if (status == DownloadStatus.Completed) "file:///tmp/$metaId" else null,
        fileName = "$metaId.mkv",
        status = status,
        downloadedBytes = downloaded,
        totalBytes = total,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-ldltr-w411dp-h891dp-port-xxhdpi")
class PlusReferenceScreenshotsEnglishPhoneTest : PlusReferenceScreenshots("en-phone")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "ar-ldrtl-w411dp-h891dp-port-xxhdpi")
class PlusReferenceScreenshotsArabicPhoneTest : PlusReferenceScreenshots("ar-phone")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-ldltr-w1280dp-h800dp-land-xhdpi")
class PlusReferenceScreenshotsEnglishTabletTest : PlusReferenceScreenshots("en-tablet")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "ar-ldrtl-w1280dp-h800dp-land-xhdpi")
class PlusReferenceScreenshotsArabicTabletTest : PlusReferenceScreenshots("ar-tablet")
