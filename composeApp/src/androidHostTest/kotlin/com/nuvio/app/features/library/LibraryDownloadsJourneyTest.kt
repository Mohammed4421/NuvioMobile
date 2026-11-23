package com.nuvio.app.features.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.nuvio.app.AppScreenTab
import com.nuvio.app.MainAppContent
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsCodec
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.streams.BingeGroupCacheStorage
import com.nuvio.app.features.updater.AndroidAppUpdaterPlatform
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.DownloadShowRoute
import com.nuvio.app.navigation.DownloadsRoute
import com.nuvio.app.navigation.PlayerRoute
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Build 15, P3-04: journeys through Library › Downloads in the real app shell ([MainAppContent] with
 * the real [LibraryScreen], download screens and navigation). Downloads are seeded the way the app
 * keeps them (the stored payload in [DownloadsStorage]) and loaded by [DownloadsRepository] itself.
 *
 * Two ways of running the shell:
 * - recorded navigation (`onNavigate`): each route the app asks for is recorded instead of shown, so
 *   playback can be checked without starting a player in Robolectric;
 * - local navigation: the app's own back stack, so the next screen, Back and saved state are real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-ldltr-w411dp-h891dp-port-xxhdpi")
class LibraryDownloadsJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    private val navigated = mutableListOf<AppRoute>()

    @BeforeTest fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        PlayerSettingsStorage.initialize(context)
        AddonStorage.initialize(context)
        BingeGroupCacheStorage.initialize(context)
        AndroidAppUpdaterPlatform.initialize(context)
        DownloadsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
        AddonRepository.clearLocalState()
        DownloadsRepository.clearLocalState()
        PlayerLaunchStore.clear()
    }

    @AfterTest fun clearState() {
        DownloadsRepository.clearLocalState()
        PlayerSettingsRepository.clearLocalState()
        AddonRepository.clearLocalState()
        PlayerLaunchStore.clear()
    }

    // ---- Normal use ----

    @Test fun finishedMovieOpensPlaybackOfItsLocalFile() {
        val file = videoFile("film-one.mkv")
        seed(movie("film-one", "Film One", DownloadStatus.Completed, file), *series())
        showRecorded()
        openDownloadsTab()

        poster("Film One").performClick()

        compose.runOnIdle {
            val route = assertIs<PlayerRoute>(navigated.single(), "routes asked for: $navigated")
            val launch = assertNotNull(PlayerLaunchStore.get(route.launchId))
            assertEquals(file.toURI().toString(), launch.sourceUrl)
            assertEquals("film-one", launch.parentMetaId)
            assertEquals("Film One", launch.title)
        }
    }

    @Test fun downloadedSeriesOpensItsEpisodesNotPlayback() {
        seed(movie("film-one", "Film One", DownloadStatus.Completed, videoFile("film-one.mkv")), *series())
        showRecorded()
        openDownloadsTab()
        assertTrue("2 downloaded episode(s)" in posterDescription("Series One"), posterDescription("Series One"))

        poster("Series One").performClick()

        compose.runOnIdle {
            assertEquals(listOf<AppRoute>(DownloadShowRoute("series-one", "Series One")), navigated)
        }
    }

    @Test fun seriesShowsItsEpisodesAndBackReturnsToTheDownloadsTab() {
        seed(movie("film-one", "Film One", DownloadStatus.Completed, videoFile("film-one.mkv")), *series())
        showLocal()
        openDownloadsTab()

        poster("Series One").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Pilot").assertIsDisplayed()
        compose.onNodeWithText("Second Night").assertIsDisplayed()
        compose.onNodeWithText("Manage downloads").assertDoesNotExist()

        pressBack()

        compose.onNodeWithText("Pilot").assertDoesNotExist()
        // Back lands on the Downloads tab it came from, not on the default Saved tab.
        compose.onNodeWithText("Manage downloads").assertIsDisplayed()
        poster("Film One").assertIsDisplayed()
        poster("Series One").assertIsDisplayed()
    }

    // ---- Expected failures ----

    @Test fun pausedFailedAndMissingDownloadsShowTheirStateAndNeverOpenPlayback() {
        seed(
            movie("paused-film", "Paused Film", DownloadStatus.Paused, file = null, downloaded = 70, total = 100),
            movie("broken-film", "Broken Film", DownloadStatus.Failed, file = null)
                .copy(errorMessage = "Server closed the connection"),
            // Finished, but its file was removed outside the app.
            movie("lost-film", "Lost Film", DownloadStatus.Completed, File(temporary.root, "gone.mkv")),
        )
        showRecorded()
        openDownloadsTab()

        // The paused transfer counts as running on the tab's label.
        compose.onNode(hasText("Downloads · 1") and hasClickAction()).assertIsDisplayed()
        val paused = posterDescription("Paused Film")
        assertTrue("Paused" in paused && "70%" in paused, paused)
        assertTrue(posterDescription("Broken Film").endsWith("Failed"), posterDescription("Broken Film"))

        poster("Paused Film").performClick()
        compose.runOnIdle { assertEquals(listOf(DownloadsRoute::class), navigated.map { it::class }) }
        poster("Broken Film").performClick()
        compose.runOnIdle {
            assertEquals(listOf(DownloadsRoute::class, DownloadsRoute::class), navigated.map { it::class })
        }
        navigated.clear()
        poster("Lost Film").performClick()
        compose.runOnIdle {
            assertTrue(navigated.none { it is PlayerRoute }, "a download without its file started playback: $navigated")
        }
    }

    // ---- Cancel / interrupt ----

    @Test fun transferInterruptedByAppExitComesBackPausedAndDoesNotPlay() {
        // Stored as "downloading" when the app went away (no transfer is running any more).
        seed(
            movie("cut-film", "Cut Film", DownloadStatus.Downloading, file = null, downloaded = 40, total = 100),
            movie("film-one", "Film One", DownloadStatus.Completed, videoFile("film-one.mkv")),
        )
        showRecorded()
        openDownloadsTab()

        val cut = posterDescription("Cut Film")
        assertTrue("Paused" in cut && "40%" in cut, cut)
        assertEquals(DownloadStatus.Paused, storedItems().single { it.id == "cut-film" }.status)

        poster("Cut Film").performClick()
        compose.runOnIdle { assertEquals(listOf(DownloadsRoute::class), navigated.map { it::class }) }
    }

    // ---- Resume / restore ----

    @Test fun episodesScreenAndDownloadsTabSurviveActivityRecreation() {
        seed(movie("film-one", "Film One", DownloadStatus.Completed, videoFile("film-one.mkv")), *series())
        val restoration = showLocal(restorable = true)
        openDownloadsTab()
        poster("Series One").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Pilot").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Pilot").assertIsDisplayed()
        compose.onNodeWithText("Second Night").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("Manage downloads").assertIsDisplayed()
        poster("Film One").assertIsDisplayed()
    }

    @Test fun downloadsTabKeepsItsStateAndCatchesUpAfterTheAppReturnsFromBackground() {
        seed(
            movie("film-one", "Film One", DownloadStatus.Completed, videoFile("film-one.mkv")),
            movie("broken-film", "Broken Film", DownloadStatus.Failed, file = null),
        )
        showLocal()
        openDownloadsTab()
        poster("Broken Film").assertIsDisplayed()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // A change made while the app is away (here: the failed download is removed).
        DownloadsRepository.cancelDownload("broken-film")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        compose.onNodeWithText("Manage downloads").assertIsDisplayed()
        compose.onNodeWithContentDescription("Broken Film, ", substring = true).assertDoesNotExist()
        poster("Film One").assertIsDisplayed()
    }

    // ---- Persistence after restart ----

    @Test fun downloadsAndTheChosenTabComeBackFromStorageAfterARestart() {
        val file = videoFile("film-one.mkv")
        seed(
            movie("film-one", "Film One", DownloadStatus.Completed, file),
            movie("paused-film", "Paused Film", DownloadStatus.Paused, file = null, downloaded = 70, total = 100),
            *series(),
        )
        val restoration = showLocal(restorable = true)
        openDownloadsTab()

        // Restart: the in-memory downloads are gone; the screen state is restored from the saved
        // state and the downloads are read again from storage by the screens themselves.
        compose.runOnIdle {
            DownloadsRepository.clearLocalState()
            assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        }
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Manage downloads").assertIsDisplayed()
        assertEquals("Film One, Movie", posterDescription("Film One"))
        assertTrue("Paused" in posterDescription("Paused Film"), posterDescription("Paused Film"))
        assertTrue("2 downloaded episode(s)" in posterDescription("Series One"), posterDescription("Series One"))
        assertTrue(file.exists())
    }

    // ---- No damage to related features ----

    @Test fun deletingOneDownloadUpdatesTheTabAndLeavesTheOthersPlayable() {
        val one = videoFile("film-one.mkv")
        val two = videoFile("film-two.mkv")
        val episodes = series()
        seed(
            movie("film-one", "Film One", DownloadStatus.Completed, one),
            movie("film-two", "Film Two", DownloadStatus.Completed, two),
            *episodes,
        )
        showLocal()
        openDownloadsTab()
        poster("Film Two").assertIsDisplayed()

        compose.onNodeWithText("Manage downloads").performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Delete") and hasAnyAncestor(hasText("Film Two"))).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Yes").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Film Two").assertDoesNotExist()
        compose.onNodeWithText("Film One").assertIsDisplayed()

        pressBack()

        compose.onNodeWithText("Manage downloads").assertIsDisplayed()
        compose.onNodeWithContentDescription("Film Two, ", substring = true).assertDoesNotExist()
        poster("Film One").assertIsDisplayed()
        poster("Series One").assertIsDisplayed()
        assertFalse(two.exists(), "the deleted download's file is still on disk")
        assertTrue(one.exists())
        episodes.forEach { assertTrue(File(java.net.URI(assertNotNull(it.localFileUri))).exists()) }
        assertEquals(setOf("film-one", "series-one-1", "series-one-2"), storedItems().map { it.id }.toSet())

        // After a restart the deletion holds and the rest is still there.
        compose.runOnIdle {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Film Two, ", substring = true).assertDoesNotExist()
        assertEquals("Film One, Movie", posterDescription("Film One"))
        poster("Series One").assertIsDisplayed()
    }

    // ---- Helpers ----

    /** The app shell with recorded navigation: routes are recorded, not shown. */
    private fun showRecorded() {
        compose.setContent {
            NuvioTheme {
                MainAppContent(
                    initialTab = AppScreenTab.Library,
                    ownsAppRuntime = false,
                    showLaunchOverlay = false,
                    onNavigate = { route, _ -> navigated += route },
                    onGoBack = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /** The app shell with its own back stack. */
    private fun showLocal(restorable: Boolean = false): StateRestorationTester {
        val tester = StateRestorationTester(compose)
        val content: @Composable () -> Unit = {
            NuvioTheme {
                MainAppContent(
                    initialTab = AppScreenTab.Library,
                    ownsAppRuntime = false,
                    showLaunchOverlay = false,
                )
            }
        }
        if (restorable) tester.setContent(content) else compose.setContent(content)
        compose.waitForIdle()
        return tester
    }

    private fun openDownloadsTab() {
        compose.onNode(hasText("Downloads", substring = true) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Manage downloads").assertIsDisplayed()
        compose.runOnIdle { navigated.clear() }
    }

    private fun pressBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun poster(title: String) = compose.onNodeWithContentDescription("$title, ", substring = true)

    private fun posterDescription(title: String): String =
        poster(title).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private fun seed(vararg items: DownloadItem) {
        DownloadsRepository.clearLocalState()
        DownloadsStorage.savePayload(DownloadsCodec.encodeItems(items.toList()))
    }

    private fun storedItems(): List<DownloadItem> =
        DownloadsCodec.decodeItems(assertNotNull(DownloadsStorage.loadPayload()))

    private fun videoFile(name: String): File = temporary.newFile(name).apply { writeBytes(ByteArray(64) { it.toByte() }) }

    private fun movie(
        id: String,
        title: String,
        status: DownloadStatus,
        file: File?,
        downloaded: Long = 64,
        total: Long? = 64,
    ) = DownloadItem(
        id = id,
        contentType = "movie",
        parentMetaId = id,
        parentMetaType = "movie",
        videoId = id,
        title = title,
        streamTitle = "1080p",
        providerName = "Test add-on",
        sourceUrl = "https://example.com/$id.mkv",
        localFileUri = file?.toURI()?.toString(),
        fileName = file?.name ?: "$id.mkv",
        status = status,
        downloadedBytes = downloaded,
        totalBytes = total,
        createdAtEpochMs = 1_000,
        updatedAtEpochMs = 2_000,
    )

    private fun series(): Array<DownloadItem> = listOf("Pilot", "Second Night").mapIndexed { index, name ->
        val episode = index + 1
        val file = videoFile("series-one-$episode.mkv")
        DownloadItem(
            id = "series-one-$episode",
            contentType = "series",
            parentMetaId = "series-one",
            parentMetaType = "series",
            videoId = "series-one:1:$episode",
            title = "Series One",
            seasonNumber = 1,
            episodeNumber = episode,
            episodeTitle = name,
            streamTitle = "1080p",
            providerName = "Test add-on",
            sourceUrl = "https://example.com/series-one-$episode.mkv",
            localFileUri = file.toURI().toString(),
            fileName = file.name,
            status = DownloadStatus.Completed,
            downloadedBytes = 64,
            totalBytes = 64,
            createdAtEpochMs = 1_000,
            updatedAtEpochMs = 3_000,
        )
    }.toTypedArray()
}
