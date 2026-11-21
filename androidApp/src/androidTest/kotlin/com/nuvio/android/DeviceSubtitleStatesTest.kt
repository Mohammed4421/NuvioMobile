package com.nuvio.android

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A subtitle loaded from the device (the owner's request): the player gets a copy in the app cache
 * as a file: URI, as the device picker gives it. Checked on both engines:
 *  1. shown at its times; turned off it goes; turned on again it comes back at the same times;
 *  2. a delay applies to it again after it was turned off and on (the player screen re-sends the
 *     delay it keeps; here the test does);
 *  3. the cached copy disappears (cache cleared, file removed) and the user picks it again: the
 *     video keeps playing without an error and without the subtitle.
 * Leaving the player forgets a device subtitle by design (it is not saved per title); that is a
 * screen-level behaviour, not checked here.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSubtitleStatesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun exoPlayerHandlesADeviceSubtitle() = exercise(AndroidPlaybackEngine.ExoPlayer)
    @Test fun libmpvHandlesADeviceSubtitle() = exercise(AndroidPlaybackEngine.Libmpv)

    private fun exercise(engine: AndroidPlaybackEngine) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cache = File(instrumentation.targetContext.cacheDir, "device-subtitles").apply { mkdirs() }
        val video = File(instrumentation.targetContext.cacheDir, "device-subtitle-video.mp4")
        instrumentation.context.assets.open("subtitles/video.mp4").use { input -> video.outputStream().use(input::copyTo) }
        val subtitle = File(cache, "picked-${engine.name}.srt")
        instrumentation.context.assets.open("subtitles/captions.srt").use { input -> subtitle.outputStream().use(input::copyTo) }
        val subtitleUri = Uri.fromFile(subtitle).toString()
        var controller: PlayerEngineController? = null
        var snapshot = PlayerPlaybackSnapshot()
        var error: String? = null
        val visible = mutableStateOf(true)
        compose.setContent {
            if (visible.value) NuvioTheme {
                PlatformPlayerSurface(
                    sourceUrl = Uri.fromFile(video).toString(),
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    playbackEngine = engine,
                    playWhenReady = false,
                    onControllerReady = { controller = it },
                    onSnapshot = { snapshot = it },
                    onError = { error = it },
                )
            }
        }
        try {
            compose.waitUntil(30_000) { compose.runOnIdle { controller != null && snapshot.durationMs > 15_000 } }

            // 1. Shown, off, on again.
            compose.runOnIdle { controller!!.setSubtitleUri(subtitleUri); controller!!.seekTo(2_500); controller!!.play() }
            awaitText("First caption")
            compose.runOnIdle { controller!!.pause(); controller!!.clearExternalSubtitle() }
            awaitGone("First caption")
            compose.runOnIdle { controller!!.setSubtitleUri(subtitleUri); controller!!.seekTo(2_500); controller!!.play() }
            awaitText("First caption")

            // 2. Delay after off/on: +5 s moves "First caption" (2-5 s) to 7-10 s.
            compose.runOnIdle { controller!!.pause(); controller!!.setSubtitleDelayMs(5_000) }
            compose.runOnIdle { controller!!.clearExternalSubtitle() }
            compose.runOnIdle { controller!!.setSubtitleUri(subtitleUri); controller!!.setSubtitleDelayMs(5_000); controller!!.seekTo(3_000); controller!!.play() }
            compose.waitUntil(10_000) { compose.runOnIdle { snapshot.positionMs in 3_300..6_000 } }
            check(compose.onAllNodesWithText("First caption").fetchSemanticsNodes().isEmpty()) {
                "${engine.name}: the delayed subtitle showed at its original time"
            }
            compose.runOnIdle { controller!!.seekTo(7_500) }
            awaitText("First caption")
            compose.runOnIdle { controller!!.pause(); controller!!.setSubtitleDelayMs(0); controller!!.clearExternalSubtitle() }

            // 3. The cached copy is gone; picking it again must not stop the video.
            check(subtitle.delete()) { "could not remove the cached subtitle" }
            compose.runOnIdle { controller!!.setSubtitleUri(subtitleUri); controller!!.seekTo(1_000); controller!!.play() }
            compose.waitUntil(20_000) { compose.runOnIdle { error != null || snapshot.positionMs >= 4_000 } }
            compose.runOnIdle {
                assertNull("${engine.name}: a missing device subtitle stopped playback", error)
                check(snapshot.isPlaying || snapshot.positionMs >= 4_000) { "${engine.name}: the video stopped (${snapshot})" }
            }
            check(compose.onAllNodesWithText("First caption").fetchSemanticsNodes().isEmpty())
        } finally {
            compose.runOnIdle { visible.value = false }
            subtitle.delete()
            video.delete()
        }
    }

    private fun awaitText(text: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitGone(text: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }
}
