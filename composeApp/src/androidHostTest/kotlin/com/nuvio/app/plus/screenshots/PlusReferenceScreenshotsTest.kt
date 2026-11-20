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
import com.nuvio.app.features.settings.SubtitleAppearanceSettings
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
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
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PlusReferenceScreenshotsTest(private val device: String, private val qualifiers: String) {
    @get:Rule val compose = createComposeRule()
    private val savedLocale: Locale = Locale.getDefault()

    @BeforeTest fun setUp() {
        RuntimeEnvironment.setQualifiers(qualifiers)
        Locale.setDefault(if (device.startsWith("ar")) Locale.forLanguageTag("ar") else Locale.ENGLISH)
        PlayerSettingsStorage.initialize(RuntimeEnvironment.getApplication())
    }

    @After fun restoreLocale() { Locale.setDefault(savedLocale) }

    @Test fun subtitleAppearanceSettings() = shoot("subtitle-appearance") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SubtitleAppearanceSettings(SubtitleStyleState(), isTablet = device.endsWith("tablet"), onStyleChanged = {})
        }
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
        val folder = File("build/reports/plus-screenshots").apply { mkdirs() }
        File(folder, "$screen-$device.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

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

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun devices(): List<Array<String>> = listOf(
            arrayOf("en-phone", "en-rUS-ldltr-w411dp-h891dp-port-xxhdpi"),
            arrayOf("ar-phone", "ar-ldrtl-w411dp-h891dp-port-xxhdpi"),
            arrayOf("en-tablet", "en-rUS-ldltr-w1280dp-h800dp-land-xhdpi"),
            arrayOf("ar-tablet", "ar-ldrtl-w1280dp-h800dp-land-xhdpi"),
        )
    }
}
