package com.nuvio.app.features.updater

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The update controller with Nuvio Plus build numbers ("custom-<build>" tags). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlusAppUpdaterControllerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: UpdatePreferences
    private val installedBuild = 20

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_updater", Context.MODE_PRIVATE).edit().clear().commit()
        context.applicationInfo.flags = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        AndroidAppUpdaterPlatform.initialize(context)
        preferences = UpdatePreferences("1.0.0")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun onlyAHigherBuildIsOffered() {
        listOf("custom-20", "custom-19", "custom-3").forEach { tag ->
            val controller = controller { Result.success(update(tag)) }
            controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
            assertFalse(controller.uiState.value.isUpdateAvailable, tag)
            assertNull(controller.uiState.value.update, tag)
        }
        val controller = controller { Result.success(update("custom-21")) }
        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
        assertTrue(controller.uiState.value.isUpdateAvailable)
        assertEquals("custom-21", controller.uiState.value.update?.tag)
    }

    @Test
    fun officialVersionTagsAreNeverOfferedToPlus() {
        val controller = controller { Result.success(update("99.0.0")) }
        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
        assertFalse(controller.uiState.value.isUpdateAvailable)
    }

    @Test
    fun ignoredBuildIsShownOnlyOnAManualCheck() {
        AppUpdaterPlatform.setIgnoredTag("custom-25")
        val controller = controller { Result.success(update("custom-25")) }
        controller.checkForUpdates(force = false, showNoUpdateFeedback = false)
        assertFalse(controller.uiState.value.showDialog)
        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
        assertTrue(controller.uiState.value.showDialog)
    }

    @Test
    fun channelChangeDropsThePendingUpdateAndIgnoresTheLateStableAnswer() {
        val lateStable = CompletableDeferred<Result<AppUpdate>>()
        val requested = mutableListOf<UpdateChannel>()
        val controller = controller { channel ->
            requested += channel
            if (channel == UpdateChannel.STABLE) {
                withContext(NonCancellable) { lateStable.await() }
            } else {
                Result.success(update("custom-30-beta"))
            }
        }
        controller.checkForUpdates(force = false, showNoUpdateFeedback = false)
        preferences.setChannel(UpdateChannel.BETA)
        lateStable.complete(Result.success(update("custom-40")))

        assertEquals(listOf(UpdateChannel.STABLE, UpdateChannel.BETA), requested)
        assertEquals(UpdateChannel.BETA, controller.uiState.value.updateChannel)
        assertEquals("custom-30-beta", controller.uiState.value.update?.tag)
        assertFalse(controller.uiState.value.isChecking)
    }

    @Test
    fun channelChangeCancelsAPendingDownload() {
        // The debug preview download runs without network; it stands in for a real download.
        val context = RuntimeEnvironment.getApplication()
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        val controller = controller { error("A debug channel change should not check for updates") }
        controller.showDebugTestUpdate()
        controller.downloadUpdate()
        assertTrue(controller.uiState.value.isDownloading)

        preferences.setChannel(UpdateChannel.BETA)

        assertEquals(AppUpdaterUiState(updateChannel = UpdateChannel.BETA), controller.uiState.value)
    }

    private fun controller(fetch: suspend (UpdateChannel) -> Result<AppUpdate>) =
        AppUpdaterController(scope, preferences, fetch, UpdateVersionPolicy.plus(installedBuild))

    private fun update(tag: String) = AppUpdate(
        tag = tag,
        title = tag,
        notes = "",
        releaseUrl = null,
        assetName = "NuvioPlus-arm64-v8a.apk",
        assetUrl = "https://example.com/$tag.apk",
        assetSizeBytes = 1L,
    )
}
