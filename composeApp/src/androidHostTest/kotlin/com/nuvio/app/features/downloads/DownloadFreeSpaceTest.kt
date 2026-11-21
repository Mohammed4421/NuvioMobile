package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The free-space check counts what is on storage, not the saved progress (Build 15 review): when the
 * partial file is gone, the whole file is needed again and a full storage is reported up front.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadFreeSpaceTest {
    private val server = MockWebServer()
    private val mb = 1024L * 1024L
    private val size = 8 * mb

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun runWithSavedProgressButNoPartialFile(availableBytes: Long): AndroidDownloadTransfer {
        val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())
        scheduler.directory.mkdirs()
        ShadowStatFs.registerStats(scheduler.directory, 1_000_000, (availableBytes / 4096).toInt(), (availableBytes / 4096).toInt())
        val item = downloadItem(server.url("/video").toString())
        val transfer = scheduler.store.begin(item)
        // Saved progress says 7 of 8 MB, but the partial file was removed (storage cleared).
        scheduler.store.update(item.fileName, transfer.generation) {
            it.copy(item = it.item.copy(downloadedBytes = 7 * mb, totalBytes = size))
        }
        assertFalse(File(scheduler.directory, "${item.fileName}.part").exists())
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(size.toInt()))))
        runBlocking { scheduler.execute(scheduler.store.get(item.fileName)!!) {} }
        return scheduler.store.get(item.fileName)!!
    }

    @Test
    fun aMissingPartialFileNeedsTheWholeFileAgain() {
        // 70 MB free: enough for the 1 MB the saved progress implies (+64 MB margin), not for 8 MB.
        val result = runWithSavedProgressButNoPartialFile(availableBytes = 70 * mb)
        assertEquals(DownloadStatus.Failed, result.item.status)
        assertTrue(result.item.errorMessage.orEmpty().contains("0.1 GB"), "message: ${result.item.errorMessage}")
    }

    @Test
    fun withEnoughSpaceTheDownloadStartsOverAndCompletes() {
        val result = runWithSavedProgressButNoPartialFile(availableBytes = 200 * mb)
        assertEquals(DownloadStatus.Completed, result.item.status, "error: ${result.item.errorMessage}")
        assertEquals(size, result.item.downloadedBytes)
    }
}
