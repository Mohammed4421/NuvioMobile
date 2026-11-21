package com.nuvio.android

import android.net.Uri
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
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A stream whose connection breaks in the middle (a Wi-Fi blip, a CDN dropping the socket): the
 * player must reconnect from where it stopped and play to the end, not stop, fail or jump to the
 * end of the file. Both engines, over a real HTTP connection to this device's own network address
 * (not loopback, which the player treats as a local proxy). The server sends the header at once and
 * the media slowly, so the player cannot have the whole file before the drop.
 */
@RunWith(AndroidJUnit4::class)
class NetworkDropPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun exoPlayerContinuesAfterTheConnectionDrops() = exercise(AndroidPlaybackEngine.ExoPlayer)
    @Test fun libmpvContinuesAfterTheConnectionDrops() = exercise(AndroidPlaybackEngine.Libmpv)

    private fun exercise(engine: AndroidPlaybackEngine) {
        val video = InstrumentationRegistry.getInstrumentation().context.assets.open("subtitles/video.mp4").use { it.readBytes() }
        val mediaStart = indexOf(video, "mdat".toByteArray()).also { check(it > 0) } - 4
        val dropAt = mediaStart + (video.size - mediaStart) / 3
        val dropped = AtomicBoolean(false)
        val droppedAt = java.util.concurrent.atomic.AtomicInteger(-1)
        val rangeStarts = Collections.synchronizedList(mutableListOf<Long>())
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    while (!server.isClosed) {
                        val socket = server.accept()
                        thread(isDaemon = true) { serve(socket, video, mediaStart, dropAt, dropped, droppedAt, rangeStarts) }
                    }
                }
            }
            val url = "http://${ownAddress()}:${server.localPort}/video.mp4"
            var snapshot = PlayerPlaybackSnapshot()
            var error: String? = null
            var furthestMs = 0L
            val visible = mutableStateOf(true)
            compose.setContent {
                if (visible.value) NuvioTheme {
                    PlatformPlayerSurface(
                        sourceUrl = url,
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        playbackEngine = engine,
                        playWhenReady = true,
                        onControllerReady = {},
                        onSnapshot = { snapshot = it; furthestMs = maxOf(furthestMs, it.positionMs) },
                        onError = { error = it },
                    )
                }
            }
            try {
                compose.waitUntil(120_000) {
                    compose.runOnIdle { error != null || furthestMs >= 17_000 }
                }
            } catch (timeout: Throwable) {
                throw AssertionError(
                    "${engine.name}: did not play past 17 s after the connection dropped " +
                        "(dropped=${dropped.get()}, furthest ${furthestMs} ms, ended=${snapshot.isEnded}, " +
                        "range requests from ${rangeStarts.toList()})",
                    timeout,
                )
            } finally {
                compose.runOnIdle { visible.value = false }
            }
            assertNull("${engine.name}: playback error after the drop", error)
            assertTrue("${engine.name}: the server never dropped the connection (test not valid)", dropped.get())
            assertTrue(
                "${engine.name}: no request continued from the dropped byte ${droppedAt.get()} (or before): ${rangeStarts.toList()}",
                rangeStarts.any { it in (mediaStart + 1)..droppedAt.get().toLong() },
            )
        }
    }

    /** Range support; the media part at about 1.3 KB/s; the first connection that reaches [dropAt] is cut there. */
    private fun serve(
        socket: Socket, body: ByteArray, mediaStart: Int, dropAt: Int, dropped: AtomicBoolean,
        droppedAt: java.util.concurrent.atomic.AtomicInteger, rangeStarts: MutableList<Long>,
    ) {
        socket.use {
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            var start = 0L
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    start = line.substringAfter("bytes=").substringBefore('-').trim().toLongOrNull() ?: 0L
                }
            }
            if (start > 0) rangeStarts += start
            val out = socket.getOutputStream()
            val partial = start > 0
            val length = body.size - start
            out.write(
                ("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n" +
                    "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n" +
                    (if (partial) "Content-Range: bytes $start-${body.size - 1}/${body.size}\r\n" else "") +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            if (requestLine.startsWith("HEAD")) return
            var position = start.toInt()
            while (position < body.size) {
                if (position >= dropAt && dropped.compareAndSet(false, true)) {
                    droppedAt.set(position)
                    socket.setSoLinger(true, 0) // reset, as a broken network does
                    return
                }
                val chunk = if (position < mediaStart) mediaStart - position else minOf(512, body.size - position)
                out.write(body, position, chunk)
                out.flush()
                position += chunk
                if (position > mediaStart) Thread.sleep(400)
            }
        }
    }

    private fun ownAddress(): String =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address }
            .hostAddress!!

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int =
        (0..data.size - pattern.size).first { i -> pattern.indices.all { data[i + it] == pattern[it] } }
}
