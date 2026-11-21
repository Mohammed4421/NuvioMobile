package com.nuvio.app.features.downloads

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDownloadTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun resumesPartialFileWithRangeAndValidator(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11")
                .setHeader("ETag", "\"version-1\"").setBody(" world"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("hello")

            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                "\"version-1\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertEquals("hello world", output.readText())
            val request = server.takeRequest()
            assertEquals("bytes=5-", request.getHeader("Range"))
            assertEquals("\"version-1\"", request.getHeader("If-Range"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertEquals("Bearer test", request.getHeader("Authorization"))
        }
    }

    @Test
    fun ignoredRangeRestartsInsteadOfAppending(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("new file"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("old prefix")
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertEquals("new file", output.readText())
        }
    }

    @Test
    fun unsatisfiedRangeRetriesFromZero(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setBody("replacement"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("old prefix")
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertEquals("replacement", output.readText())
            assertEquals("bytes=10-", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun mismatchedRangeDoesNotCorruptThePartialFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/5").setBody("bad"))
            val directory = temporary.newFolder()
            val partial = File(directory, "video.mkv.part").apply { writeText("hello") }
            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertEquals("hello", partial.readText())
        }
    }

    @Test
    fun truncatedResponseNeverBecomesCompletedFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val directory = temporary.newFolder()
            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertFalse(File(directory, "video.mkv").exists())
            assertTrue(File(directory, "video.mkv.part").length() < 10)
        }
    }

    @Test
    fun cancellationClosesBlockedSocketPromptlyAndKeepsPartialBytes(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").throttleBody(1, 10, TimeUnit.SECONDS))
            val directory = temporary.newFolder()
            val task = async(Dispatchers.Default) {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            withTimeout(5_000) {
                while (File(directory, "video.mkv.part").length() == 0L) delay(10)
            }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertEquals(1L, File(directory, "video.mkv.part").length())
            assertFalse(File(directory, "video.mkv").exists())
        }
    }

    // Independent review (GEM-DL-01/02): a resume is appended only for the same version, to the end.
    private fun resumeAgainst(first: MockResponse): Pair<String, List<String?>> = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(first)
            server.enqueue(MockResponse().setHeader("ETag", "\"version-2\"").setBody("brand new file"))
            val directory = temporary.newFolder()
            File(directory, "video.mkv.part").writeText("hello")
            val output = transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                "\"version-1\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            output.readText() to List(server.requestCount) { server.takeRequest().getHeader("Range") }
        }
    }

    @Test
    fun aServerIgnoringIfRangeWithAnotherVersionStartsOverInsteadOfAppending() {
        val (content, ranges) = resumeAgainst(MockResponse().setResponseCode(206).setHeader("ETag", "\"version-2\"")
            .setHeader("Content-Range", "bytes 5-10/11").setBody(" world"))
        assertEquals("brand new file", content)
        assertEquals(listOf("bytes=5-", null), ranges)
    }

    @Test
    fun aRangeOfUnknownTotalStartsOverInsteadOfEndingEarly() {
        val (content, _) = resumeAgainst(MockResponse().setResponseCode(206).setHeader("ETag", "\"version-1\"")
            .setHeader("Content-Range", "bytes 5-7/*").setBody(" wo"))
        assertEquals("brand new file", content)
    }

    @Test
    fun aRangeStoppingBeforeTheEndStartsOver() {
        val (content, _) = resumeAgainst(MockResponse().setResponseCode(206).setHeader("ETag", "\"version-1\"")
            .setHeader("Content-Range", "bytes 5-7/11").setBody(" wo"))
        assertEquals("brand new file", content)
    }

    @Test
    fun aBodyLongerOrShorterThanItsRangeIsRefusedAndThePartialKept(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"version-1\"")
                .setHeader("Content-Range", "bytes 5-10/11").setBody(" world and more"))
            val directory = temporary.newFolder()
            val partial = File(directory, "video.mkv.part").apply { writeText("hello") }
            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), directory,
                    "\"version-1\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertEquals("hello", partial.readText())
        }
    }

    @Test
    fun contentRangeKeepsItsEnd() {
        assertEquals(DownloadContentRange(100, 199, null), parseDownloadContentRange("bytes 100-199/*"))
        assertEquals(100L, parseDownloadContentRange("bytes 100-199/1000")?.length)
        assertNull(parseDownloadContentRange("bytes 5-4/10"))
    }

    @Test
    fun retryPolicyDistinguishesTransientAndPermanentFailures() {
        assertTrue(shouldRetryAndroidDownload(IOException("connection lost"), 0))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(503), 0))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(429), 0))
        assertFalse(shouldRetryAndroidDownload(DownloadHttpException(403), 0))
        assertFalse(shouldRetryAndroidDownload(IOException("connection lost"), 4))
    }
}

internal fun downloadItem(url: String = "https://example.com/video.mkv", id: String = "test-download") = DownloadItem(
    id = id,
    contentType = "movie",
    parentMetaId = "test-movie",
    parentMetaType = "movie",
    videoId = "test-movie",
    title = "Download test",
    streamTitle = "Test video",
    providerName = "Test",
    sourceUrl = url,
    sourceHeaders = mapOf("Authorization" to "Bearer test"),
    fileName = "video.mkv",
    status = DownloadStatus.Downloading,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
)
