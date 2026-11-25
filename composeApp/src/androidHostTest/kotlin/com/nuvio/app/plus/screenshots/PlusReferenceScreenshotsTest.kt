package com.nuvio.app.plus.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadPreferencesState
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.SeasonKey
import com.nuvio.app.features.downloads.SeasonPauseScope
import com.nuvio.app.features.downloads.SeasonQueueEntry
import com.nuvio.app.features.downloads.SeasonQueueEntryState
import com.nuvio.app.features.downloads.SeasonQueueHolds
import com.nuvio.app.features.downloads.SeasonQueueReference
import com.nuvio.app.features.downloads.downloadsSettingsContent
import com.nuvio.app.features.downloads.seasonQueueContent
import com.nuvio.app.features.library.buildLibraryDownloadEntries
import com.nuvio.app.features.library.libraryDownloadsContent
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.AudioTrackModal
import com.nuvio.app.features.player.PlayerControlsShell
import com.nuvio.app.features.player.PlayerLayoutMetrics
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.SubtitleAutoSyncUiState
import com.nuvio.app.features.player.SubtitleModal
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.features.settings.SubtitleAppearanceSettings
import com.nuvio.app.features.settings.playbackSettingsContent
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

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

    // P4 batch (owner, 2026-11-21): states and long or mixed titles on the download screens.
    private fun longTitle() = text(
        "The Extraordinarily Long Title Of A Series That Keeps Going",
        "مسلسل The Long Night الطويل جدًا في عنوانه",
    )

    @Test fun libraryDownloadStates() = shoot("library-download-states") {
        val entries = buildLibraryDownloadEntries(
            items = listOf(
                item("film-paused-unknown", null, DownloadStatus.Paused, downloaded = 70, total = null).copy(title = longTitle()),
                item("film-failed", null, DownloadStatus.Failed).copy(title = text("Failed film", "فيلم فشل")),
                item("show-mixed", 1, DownloadStatus.Completed).copy(title = text("Show", "عرض Mixed")),
            ),
            queue = emptyList(),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            libraryDownloadsContent(
                entries = entries, activeCount = 1, queueCount = 0, columns = if (device.endsWith("tablet")) 5 else 3,
                onManageClick = {}, onEntryClick = {}, onEntryLongClick = {},
            )
        }
    }

    @Test fun libraryDownloadsEmpty() = shoot("library-downloads-empty") {
        LazyColumn(Modifier.fillMaxSize()) {
            libraryDownloadsContent(
                entries = emptyList(), activeCount = 0, queueCount = 0, columns = 3,
                onManageClick = {}, onEntryClick = {}, onEntryLongClick = {},
            )
        }
    }

    @Test fun downloadsQueueAndSettings() = shoot("downloads-queue") {
        val reference = SeasonQueueReference(addonId = "a", addonName = "Add-on", text = "1080p")
        fun episode(n: Int, state: SeasonQueueEntryState, show: String, title: String, pausedBy: SeasonPauseScope = SeasonPauseScope.Episode) =
            SeasonQueueEntry(
                id = "$show-$n", contentType = "series", parentMetaId = show, parentMetaType = "series",
                title = title, videoId = "$show:1:$n", seasonNumber = 1, episodeNumber = n,
                episodeTitle = text("Episode $n with a long name that does not fit", "الحلقة $n: The Return"),
                reference = reference, state = state, pausedBy = pausedBy, profileId = 1,
            )
        val entries = listOf(
            episode(1, SeasonQueueEntryState.Searching, "show-a", longTitle()),
            episode(2, SeasonQueueEntryState.Paused, "show-a", longTitle(), SeasonPauseScope.Season),
            episode(3, SeasonQueueEntryState.Waiting, "show-a", longTitle()),
            episode(1, SeasonQueueEntryState.NeedsChoice, "show-b", text("Short", "قصير")),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            seasonQueueContent(
                entries = entries,
                holds = SeasonQueueHolds(pausedSeasons = setOf(SeasonKey(1, "show-a", 1))),
                showPauseAll = true,
            )
            downloadsSettingsContent(preferences = DownloadPreferencesState(wifiOnly = true), onChooseFolder = null)
        }
    }

    // The playback settings that shape watching (P4: screens most used around playback).
    @Test fun playbackSettings() = playbackPage("playback-settings", SettingsPage.Playback)
    @Test fun playbackPlayerSettings() = playbackPage("playback-player", SettingsPage.PlaybackPlayer)
    @Test fun playbackSubtitleSettings() = playbackPage("playback-subtitles", SettingsPage.PlaybackSubtitles)
    @Test fun playbackVideoSettings() = playbackPage("playback-video", SettingsPage.PlaybackVideo)
    @Test fun playbackAudioSettings() = playbackPage("playback-audio", SettingsPage.PlaybackAudio)

    // The player itself (P4: player, its panels). The player is landscape only, so these run on the
    // landscape setups; a portrait phone reports them as skipped, never as passed.
    private val arabic get() = device.startsWith("ar")
    private fun text(en: String, ar: String) = if (arabic) ar else en

    @Test fun playerControls() = player("player-controls") {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerControlsShell(
                title = text("Test series", "مسلسل تجريبي"),
                streamTitle = text("Test stream", "بث تجريبي"),
                providerName = text("Test add-on", "إضافة تجريبية"),
                seasonNumber = 1, episodeNumber = 3, episodeTitle = text("The return", "العودة"),
                playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, durationMs = 2_700_000,
                    positionMs = 1_250_000, bufferedPositionMs = 1_600_000),
                displayedPositionMs = 1_250_000,
                metrics = PlayerLayoutMetrics.fromWidth(maxWidth),
                resizeMode = PlayerResizeMode.Fit, isLocked = false,
                onLockToggle = {}, onBack = {}, onTogglePlayback = {}, onSeekBack = {}, onSeekForward = {},
                onResizeModeClick = {}, onSpeedClick = {}, onSubtitleClick = {}, onAudioClick = {},
                onSourcesClick = {}, onEpisodesClick = {}, onScrubChange = {}, onScrubFinished = {},
                horizontalSafePadding = 0.dp,
            )
        }
    }

    @Test fun playerSubtitles() = player("player-subtitles") {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SubtitleModal(
                visible = true,
                subtitleTracks = listOf(
                    SubtitleTrack(0, "0", text("English", "الإنجليزية"), "en", isSelected = true),
                    SubtitleTrack(1, "1", text("Arabic", "العربية"), "ar"),
                ),
                selectedSubtitleIndex = 0,
                addonSubtitles = listOf(AddonSubtitle("a1", "https://example.com/a.srt", "ar", text("Arabic subtitles", "ترجمة عربية"))),
                selectedAddonSubtitleId = null, isLoadingAddonSubtitles = false,
                preferredSubtitleLanguage = if (arabic) "ar" else "en", secondaryPreferredSubtitleLanguage = null,
                subtitleStyle = SubtitleStyleState(), subtitleDelayMs = 0, selectedAddonSubtitle = null,
                subtitleAutoSyncState = SubtitleAutoSyncUiState(),
                onBuiltInTrackSelected = {}, onAddonSubtitleSelected = {}, onFetchAddonSubtitles = {}, onStyleChanged = {},
                onSubtitleDelayChanged = {}, onSubtitleDelayReset = {}, onAutoSyncCapture = {}, onAutoSyncCueSelected = {},
                onAutoSyncReload = {}, onDismiss = {},
            )
        }
    }

    @Test fun playerAudio() = player("player-audio") {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AudioTrackModal(
                visible = true,
                audioTracks = listOf(
                    AudioTrack(0, "0", text("English 5.1", "الإنجليزية ٥٫١"), "en", isSelected = true),
                    AudioTrack(1, "1", text("Arabic stereo", "العربية ستيريو"), "ar"),
                ),
                selectedIndex = 0, onTrackSelected = {}, onDismiss = {},
                showAudioDelay = true, showAudioAmplification = true,
            )
        }
    }

    /**
     * P4-03: the confirmation dialogs Plus shows, drawn by Nuvio's DialogSurface like every other
     * dialog of the app: the add-on link confirmation (P6-02) and deleting a download.
     */
    @Test fun addonLinkDialog() = shoot("dialog-addon-link") {
        NuvioStatusModal(
            title = stringResource(Res.string.addons_link_confirm_title),
            message = stringResource(Res.string.addons_link_confirm_message, "addons.example.com"),
            isVisible = true,
            confirmText = stringResource(Res.string.addons_link_confirm_install),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {},
            onDismiss = {},
        )
    }

    @Test fun downloadDeleteDialog() = shoot("dialog-download-delete") {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {},
            onDismiss = {},
        )
    }

    private fun player(screen: String, content: @Composable () -> Unit) {
        assumeTrue("the player is landscape only; $device is portrait", !device.endsWith("phone"))
        shoot(screen, content)
    }

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
        // A dialog is a window of its own: its root comes after the screen's, and that is what is
        // shown and read (the screen behind it is only the dimmed background).
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        val rootCount = roots.fetchSemanticsNodes().size
        val root = roots[rootCount - 1]
        val bitmap = if (rootCount > 1) dialogBitmap() else root.captureToImage().asAndroidBitmap()
        val expected = if (device.startsWith("ar")) LayoutDirection.Rtl else LayoutDirection.Ltr
        assertEquals(expected, direction, "$screen on $device is not laid out in the expected direction")
        assertTrue(distinctColours(bitmap) > 1, "$screen on $device rendered as a single colour")
        // Spec P4: the language shown, not only the device setting (P4-12). Arabic screens show
        // Arabic letters; English ones show none (a missing translation falls back to English).
        val texts = shownTexts(root.fetchSemanticsNode())
        val arabic = texts.filter { text -> text.any { it in '\u0600'..'\u06FF' } }
        if (device.startsWith("ar")) {
            assertTrue(arabic.size * 2 >= texts.count { t -> t.any { it.isLetter() } },
                "$screen on $device: fewer than half of the labels are Arabic: $texts")
        } else {
            assertTrue(arabic.isEmpty(), "$screen on $device shows Arabic text: $arabic")
        }
        val folder = File("build/reports/plus-screenshots").apply { mkdirs() }
        File(folder, "$screen-$device.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compareWithBaseline("$screen-$device", bitmap)
        // P4-09: every control a finger or a screen reader reaches, printed for review.
        val findings = accessibilityFindings()
        val report = File("build/reports/plus-a11y").apply { mkdirs() }
        File(report, "$screen-$device.txt").writeText(findings.joinToString("") { "$screen-$device: $it\n" })
        // On screens Plus draws itself, every control has something a screen reader can say. The
        // settings pages are left out: their switches come from Nuvio's SettingsSwitchRow (B15-UI-07).
        if (screen in PLUS_OWNED_SCREENS) {
            val unlabelled = findings.filter { "no label" in it }
            assertTrue(unlabelled.isEmpty(), "$screen on $device has controls without a label: $unlabelled")
            // P4-09: every control a finger reaches on Plus's own screens has a 48 dp touch area.
            val small = findings.filter { TOUCH_FINDING in it }
            assertTrue(small.isEmpty(), "$screen on $device has touch areas under 48 dp: $small")
        }
        // P4-09: text contrast measured on the image (WCAG 2 ratio of the text's colour to the colour
        // behind it). Below 4.5:1 is printed for review, with each screen's lowest value; on Plus's own
        // screens enabled text must reach 3:1 (WCAG's floor for large text and controls). Disabled
        // text is exempt (WCAG).
        val measured = contrastMeasurements(bitmap, window = rootCount > 1)
        val contrast = measured.filter { it.first < 4.5 }
        val lowest = measured.minByOrNull { it.first }
        File(report, "$screen-$device.txt").appendText(
            contrast.joinToString("") { "$screen-$device: ${it.second}\n" } +
                "$screen-$device: contrast measured on ${measured.size} texts" +
                (lowest?.let { ", lowest ${it.second}" } ?: "") + "\n",
        )
        assertTrue(measured.isNotEmpty(), "$screen on $device: no text could be measured for contrast")
        if (screen in PLUS_OWNED_SCREENS) {
            val low = contrast.filter { it.first < 3.0 }.map { it.second }
            assertTrue(low.isEmpty(), "$screen on $device has text below 3:1 contrast: $low")
        }
    }

    /**
     * The dialog window drawn into a bitmap. Under Robolectric, captureToImage() of a dialog's root
     * gives an empty image (only the activity window is rendered), so the dialog's own decor view is
     * drawn here, as Android draws it into the window.
     */
    private fun dialogBitmap(): Bitmap {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no dialog window is shown" }
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = requireNotNull(dialog.window).decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    /**
     * P4-11: a 240 px wide thumbnail of the screen against the approved one in
     * docs/plus/build15/screenshot-baselines. Report only, for human review: the share of pixels
     * that changed visibly (a channel off by more than 24 of 255) and the mean change. A missing
     * baseline reports NEW. The thumbnail is kept so an approved change can become the baseline.
     */
    private fun compareWithBaseline(name: String, bitmap: Bitmap) {
        val width = 240
        val thumb = Bitmap.createScaledBitmap(bitmap, width, (bitmap.height * width / bitmap.width).coerceAtLeast(1), true)
        File("build/reports/plus-thumbs").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            thumb.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val baselineFile = File("../docs/plus/build15/screenshot-baselines/$name.png")
        val line = if (!baselineFile.exists()) {
            "$name NEW (no baseline)"
        } else {
            val baseline = BitmapFactory.decodeFile(baselineFile.path)
            if (baseline == null || baseline.width != thumb.width || baseline.height != thumb.height) {
                "$name SIZE ${baseline?.width}x${baseline?.height} -> ${thumb.width}x${thumb.height}"
            } else {
                var changed = 0
                var total = 0L
                for (y in 0 until thumb.height) for (x in 0 until thumb.width) {
                    val a = thumb.getPixel(x, y)
                    val b = baseline.getPixel(x, y)
                    val d = maxOf(
                        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
                        abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
                        abs((a and 0xFF) - (b and 0xFF)),
                    )
                    total += d
                    if (d > 24) changed++
                }
                val pixels = thumb.width * thumb.height
                val percent = changed * 1000 / pixels / 10.0
                val verdict = if (changed * 100 >= pixels) "CHANGED" else if (changed > 0) "minor" else "same"
                "$name $verdict ${percent}% of pixels, mean ${total / pixels}"
            }
        }
        File("build/reports/plus-compare").apply { mkdirs() }.resolve("$name.txt").writeText(line + "\n")
    }

    /**
     * Tappable or toggleable nodes on screen with nothing a screen reader can say, and those whose
     * touch area is under 48 dp (P4-09, NVP15-RISK-0013). The touch area is the node's touch bounds,
     * which include the widening Compose gives a smaller control (minimumInteractiveComponentSize),
     * so a 40 dp icon button that a finger can hit over 48 dp passes; the drawn size is printed
     * beside it for review. Nodes scrolled out of view (0 x 0) are skipped.
     */
    private fun accessibilityFindings(): List<String> {
        val dp = compose.density.density
        val nodes = compose.onAllNodes(hasClickAction() or isToggleable()).fetchSemanticsNodes()
        return nodes.mapNotNull { node ->
            val bounds = node.boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
            val label = (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(node.config.getOrNull(SemanticsProperties.StateDescription)))
                .joinToString(" ").trim()
            val widthDp = (bounds.width / dp).toInt()
            val heightDp = (bounds.height / dp).toInt()
            val touch = node.touchBoundsInRoot
            // Rounded, not truncated: 47.99 dp from pixel rounding is a 48 dp target.
            val touchWidthDp = Math.round(touch.width / dp)
            val touchHeightDp = Math.round(touch.height / dp)
            val problems = buildList {
                if (touchWidthDp < 48 || touchHeightDp < 48) {
                    add("$TOUCH_FINDING ${touchWidthDp}x${touchHeightDp} dp (drawn ${widthDp}x${heightDp} dp)")
                }
                if (label.isEmpty()) add("no label")
            }
            if (problems.isEmpty()) null
            else "${problems.joinToString(", ")} at (${(bounds.left / dp).toInt()}, ${(bounds.top / dp).toInt()}) dp \"${label.take(48)}\""
        }
    }

    /** Text below 4.5:1 against its background, as (ratio, finding); disabled text is skipped. */
    /**
     * Every enabled text's contrast as (ratio, description). The unmerged tree gives each text its
     * own bounds, so a poster or an icon beside it in the same card is not taken for its colour.
     */
    private fun contrastMeasurements(bitmap: Bitmap, window: Boolean): List<Pair<Double, String>> {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
        return nodes.mapNotNull { node ->
            if (generateSequence(node) { it.parent }.any { it.config.contains(SemanticsProperties.Disabled) }) {
                return@mapNotNull null
            }
            val bounds = if (window) node.boundsInWindow else node.boundsInRoot
            val left = max(0, bounds.left.toInt()); val top = max(0, bounds.top.toInt())
            val right = min(bitmap.width, bounds.right.toInt()); val bottom = min(bitmap.height, bounds.bottom.toInt())
            if (right - left < 4 || bottom - top < 4) return@mapNotNull null
            val counts = HashMap<Int, Int>()
            for (y in top until bottom) for (x in left until right) {
                val pixel = bitmap.getPixel(x, y) or (0xFF shl 24)
                counts[pixel] = (counts[pixel] ?: 0) + 1
            }
            val background = counts.maxByOrNull { it.value }!!.key
            // The text colour: the pixel colour furthest in contrast from the background (glyph cores).
            val ratio = counts.keys.maxOf { contrastRatio(it, background) }
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
            ratio to "contrast ${"%.1f".format(ratio)}:1 at (${(bounds.left / compose.density.density).toInt()}, " +
                "${(bounds.top / compose.density.density).toInt()}) dp \"${text.take(48)}\""
        }
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        fun channel(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        fun luminance(p: Int) = 0.2126 * channel((p shr 16) and 0xFF) + 0.7152 * channel((p shr 8) and 0xFF) +
            0.0722 * channel(p and 0xFF)
        val (x, y) = luminance(a) to luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
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

private const val TOUCH_FINDING = "touch area"

/** Reference screens whose controls are all labelled (Plus code, and the Nuvio parts it shows). */
private val PLUS_OWNED_SCREENS = setOf(
    "library-downloads", "library-download-states", "library-downloads-empty", "downloads-queue",
    "player-subtitles", "player-audio", "dialog-addon-link", "dialog-download-delete",
)

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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-ldltr-w891dp-h411dp-land-xxhdpi")
class PlusReferenceScreenshotsEnglishPhoneLandscapeTest : PlusReferenceScreenshots("en-phone-land")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "ar-ldrtl-w891dp-h411dp-land-xxhdpi")
class PlusReferenceScreenshotsArabicPhoneLandscapeTest : PlusReferenceScreenshots("ar-phone-land")
