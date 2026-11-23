package com.nuvio.app.features.updater

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test
    fun interruptedApkDownloadKeepsThePreviousFileAndRetriesFromTheServer() = runBlocking {
        val cache = File(RuntimeEnvironment.getApplication().cacheDir, "updates").apply { mkdirs() }
        val destination = File(cache, "retry-fixture.apk")
        val previous = byteArrayOf(1, 2, 3)
        val complete = ByteArray(24 * 1024) { (it % 251).toByte() }
        val priorParts = cache.listFiles().orEmpty().filter { it.extension == "part" }.toSet()
        destination.writeBytes(previous)
        try {
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                val responses = async(Dispatchers.IO) {
                    for (bytesToSend in listOf(complete.size / 2, complete.size)) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val request = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            check(request.readLine() == "GET /asset.apk HTTP/1.1")
                            while (!request.readLine().isNullOrEmpty()) { /* consume headers */ }
                            val out = socket.getOutputStream()
                            out.write(("HTTP/1.1 200 OK\r\nContent-Length: ${complete.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            out.write(complete, 0, bytesToSend)
                            out.flush()
                        }
                    }
                }
                val asset = update("custom-21").copy(
                    assetName = destination.name,
                    assetUrl = "http://127.0.0.1:${server.localPort}/asset.apk",
                    assetSizeBytes = complete.size.toLong(),
                )
                val controller = controller { Result.success(asset) }
                controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
                controller.downloadUpdate()
                val failed = withTimeout(15_000) {
                    controller.uiState.first { !it.isDownloading && it.errorMessage != null }
                }
                assertNull(failed.downloadedApkPath)
                assertTrue(failed.showDialog)
                assertTrue(previous.contentEquals(destination.readBytes()))
                assertEquals(priorParts, cache.listFiles().orEmpty().filter { it.extension == "part" }.toSet())

                scope.cancel()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                preferences = UpdatePreferences("1.0.0")
                val restarted = this@PlusAppUpdaterControllerTest.controller { Result.success(asset) }
                assertNull(restarted.uiState.value.downloadedApkPath)
                restarted.checkForUpdates(force = true, showNoUpdateFeedback = false)
                restarted.downloadUpdate()
                val finished = withTimeout(15_000) {
                    restarted.uiState.first { it.downloadedApkPath == destination.absolutePath }
                }
                assertFalse(finished.isDownloading)
                assertTrue(complete.contentEquals(destination.readBytes()))
                responses.await()
            }
        } finally {
            destination.delete()
        }
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
