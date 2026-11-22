package com.nuvio.android

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.util.Collections

/**
 * Build 15, P5-05/06/08: playback stability, dropped frames and CPU on both engines, measured the
 * same way on every emulator. Plays the bundled 20 s clip from the start for 12 s of wall time.
 *
 * Checked (a fault fails the test): playback starts, keeps up with the clock (at least 85 % of the
 * wall time once it starts) and never goes back to loading on a local file, and no error.
 * Measured only (printed as "MEASURE <engine> <metric> <value>"; an emulator renders video in
 * software, so these are a baseline to compare builds on the same emulator, not a device verdict):
 * dropped video frames, UI frames and the share of them slower than 16.7 ms, the 90th percentile
 * UI frame time, and the app's CPU time as a share of one core.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSmoothnessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun exoPlayerPlaysSmoothly() = exercise(AndroidPlaybackEngine.ExoPlayer)
    @Test fun libmpvPlaysSmoothly() = exercise(AndroidPlaybackEngine.Libmpv)

    private fun exercise(engine: AndroidPlaybackEngine) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val video = File(instrumentation.targetContext.cacheDir, "smoothness-${engine.name}.mp4")
        instrumentation.context.assets.open("subtitles/video.mp4").use { input -> video.outputStream().use(input::copyTo) }
        var controller: PlayerEngineController? = null
        var snapshot = PlayerPlaybackSnapshot()
        var error: String? = null
        var stalls = 0
        var started = false
        val visible = mutableStateOf(true)
        compose.setContent {
            if (visible.value) NuvioTheme {
                PlatformPlayerSurface(
                    sourceUrl = Uri.fromFile(video).toString(),
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    playbackEngine = engine,
                    playWhenReady = false,
                    onControllerReady = { controller = it },
                    onSnapshot = {
                        if (started && it.isLoading && !snapshot.isLoading) stalls++
                        snapshot = it
                    },
                    onError = { error = it },
                )
            }
        }
        val frameNanos = Collections.synchronizedList(mutableListOf<Long>())
        val frames = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frameNanos += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        }
        try {
            compose.waitUntil(30_000) { compose.runOnIdle { controller != null && snapshot.durationMs > 15_000 } }
            compose.runOnIdle { controller!!.play() }
            compose.waitUntil(15_000) { compose.runOnIdle { error != null || (snapshot.isPlaying && snapshot.positionMs > 0) } }
            val startPosition = compose.runOnIdle { started = true; snapshot.positionMs }
            val startDropped = compose.runOnIdle { snapshot.droppedVideoFrames }
            val window = compose.runOnIdle { compose.activity.window }
            compose.runOnIdle { window.addOnFrameMetricsAvailableListener(frames, Handler(Looper.getMainLooper())) }
            val startCpu = cpuMs()
            val startWall = SystemClock.elapsedRealtime()
            SystemClock.sleep(12_000)
            val wall = SystemClock.elapsedRealtime() - startWall
            val cpu = cpuMs() - startCpu
            compose.runOnIdle { window.removeOnFrameMetricsAvailableListener(frames) }
            val (played, dropped, stalled) = compose.runOnIdle {
                Triple(snapshot.positionMs - startPosition, snapshot.droppedVideoFrames - startDropped, stalls)
            }
            compose.runOnIdle { assertNull("${engine.name}: playback error", error) }
            val uiFrames = synchronized(frameNanos) { frameNanos.sorted() }
            val slow = uiFrames.count { it > 16_700_000L }
            measure(engine, "played_ms_per_wall_s", played * 1000 / wall)
            measure(engine, "dropped_video_frames", dropped)
            measure(engine, "ui_frames", uiFrames.size.toLong())
            measure(engine, "ui_slow_frames_percent", if (uiFrames.isEmpty()) 0 else slow * 100L / uiFrames.size)
            measure(engine, "ui_frame_p90_ms", uiFrames.getOrNull((uiFrames.size * 9) / 10)?.div(1_000_000L) ?: 0)
            measure(engine, "cpu_percent_of_one_core", cpu * 100 / wall)
            measure(engine, "stalls", stalled.toLong())
            check(played >= wall * 85 / 100) { "${engine.name}: played ${played} ms in ${wall} ms of wall time" }
            check(stalled == 0) { "${engine.name}: a local file went back to loading ${stalled} times" }
        } finally {
            compose.runOnIdle { visible.value = false }
            video.delete()
        }
    }

    private fun measure(engine: AndroidPlaybackEngine, metric: String, value: Long) {
        Log.i("NuvioMeasure", "MEASURE ${engine.name} $metric $value")
    }

    /** This process's user plus system CPU time, from /proc/self/stat (clock ticks of 10 ms). */
    private fun cpuMs(): Long {
        val fields = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
        // After the command name: state is field 3, utime 14 and stime 15 (1-based in proc(5)).
        return (fields[11].toLong() + fields[12].toLong()) * 10
    }
}
