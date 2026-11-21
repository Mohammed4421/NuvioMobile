package com.nuvio.app.features.downloads

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

internal val downloadHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal class DownloadHttpException(val statusCode: Int) : IOException("Download failed: HTTP $statusCode")

/** Where a transfer writes its bytes: a `.part` file in app storage or a document in a picked folder. */
internal interface DownloadPartialSink {
    /** Bytes already written (0 when nothing exists yet). */
    fun length(): Long

    /** Opens the target for writing; [append] keeps the bytes already there. */
    fun open(append: Boolean): DownloadPartialOutput
}

internal class DownloadPartialOutput(
    val stream: OutputStream,
    private val syncAction: () -> Unit,
    private val closeAction: () -> Unit = {},
) : Closeable {
    fun sync() = syncAction()

    override fun close() {
        try {
            stream.close()
        } finally {
            closeAction()
        }
    }
}

internal class FileDownloadPartialSink(private val partial: File) : DownloadPartialSink {
    override fun length(): Long = partial.takeIf(File::isFile)?.length() ?: 0L

    override fun open(append: Boolean): DownloadPartialOutput {
        val output = FileOutputStream(partial, append)
        return DownloadPartialOutput(output, syncAction = { output.fd.sync() })
    }
}

internal suspend fun transferAndroidDownload(
    item: DownloadItem,
    directory: File,
    validator: String?,
    client: OkHttpClient = downloadHttpClient,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File {
    require(File(item.fileName).name == item.fileName && item.fileName.isNotBlank())
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create downloads directory" }
    val partial = File(directory, "${item.fileName}.part")
    transferAndroidDownloadTo(
        item = item,
        sink = FileDownloadPartialSink(partial),
        validator = validator,
        client = client,
        onHeaders = onHeaders,
        onProgress = onProgress,
    )
    return partial
}

internal suspend fun transferAndroidDownloadTo(
    item: DownloadItem,
    sink: DownloadPartialSink,
    validator: String?,
    client: OkHttpClient = downloadHttpClient,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): Unit = coroutineScope {
    val activeCall = AtomicReference<Call?>()
    val transfer = async(Dispatchers.IO) {
        try {
            var offset = sink.length()
            var restarted = false

            while (true) {
                ensureActive()
                val request = Request.Builder().url(item.sourceUrl).apply {
                    item.sourceHeaders.forEach { (key, value) ->
                        if (!key.equals("Range", true) && !key.equals("If-Range", true) &&
                            !key.equals("Accept-Encoding", true)) header(key, value)
                    }
                    header("Accept-Encoding", "identity")
                    if (offset > 0L) {
                        header("Range", "bytes=$offset-")
                        validator?.let { header("If-Range", it) }
                    }
                }.build()
                downloadLog("request from byte $offset (partial file ${sink.length()} bytes)")
                val call = client.newCall(request)
                activeCall.set(call)
                ensureActive()
                var finished = false
                call.execute().use { response ->
                    if (response.code == 416 && offset > 0L && !restarted) {
                        offset = 0L
                        restarted = true
                        return@use
                    }
                    if (!response.isSuccessful) throw DownloadHttpException(response.code)
                    val range = response.header("Content-Range")?.let(::parseDownloadContentRange)
                    val resumed = response.code == 206
                    if (resumed && (range == null || range.start != offset)) {
                        throw IOException("Server returned an invalid download range")
                    }
                    val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified")
                    // Appending is safe only for the same file to its end (independent review, GEM-DL-01/02):
                    // a server that ignored If-Range and now serves another version, a range that stops
                    // before the end, or one of unknown total would leave a wrong or cut file. Start once
                    // more from zero (a full response), as for 416.
                    val sameVersion = validator == null || responseValidator == null || responseValidator == validator
                    val toTheEnd = range != null && range.total != null && range.end == range.total - 1L
                    if (resumed && offset > 0L && (!sameVersion || !toTheEnd)) {
                        if (!restarted) {
                            offset = 0L
                            restarted = true
                            return@use
                        }
                        throw IOException("Server cannot resume this download safely")
                    }
                    val startingBytes = if (resumed) offset else 0L
                    val body = response.body ?: throw IOException("Download response is empty")
                    val contentLength = body.contentLength().takeIf { it >= 0L }
                    if (resumed && range != null && contentLength != null && contentLength != range.length) {
                        throw IOException("Server returned a body that does not match its range")
                    }
                    val totalBytes = range?.total ?: contentLength?.let { startingBytes + it }
                    onHeaders(totalBytes, responseValidator)
                    var downloaded = startingBytes
                    onProgress(downloaded, totalBytes)
                    body.byteStream().use { input ->
                        sink.open(append = resumed && offset > 0L).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val size = input.read(buffer)
                                if (size == -1) break
                                output.stream.write(buffer, 0, size)
                                downloaded += size
                                onProgress(downloaded, totalBytes)
                            }
                            output.stream.flush()
                            output.sync()
                        }
                    }
                    ensureActive()
                    if (totalBytes != null && downloaded != totalBytes) {
                        throw IOException("Download ended before all bytes were received")
                    }
                    finished = true
                }
                if (finished) break
            }
        } catch (error: Exception) {
            // Closing a cancelled HTTP call throws IOException from its blocking read.
            ensureActive()
            throw error
        }
    }
    try {
        transfer.await()
    } finally {
        activeCall.get()?.cancel()
    }
}

/** A Content-Range header: bytes [start]-[end]/[total] ([total] null for "*"). */
internal data class DownloadContentRange(val start: Long, val end: Long, val total: Long?) {
    val length: Long get() = end - start + 1L
}

internal fun parseDownloadContentRange(value: String): DownloadContentRange? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull()
    if (end < start || (total != null && end >= total)) return null
    return DownloadContentRange(start, end, total)
}

/** Logcat tag of the download engine: one line per job start, stop and HTTP request (no URLs). */
internal const val DOWNLOAD_LOG_TAG = "NuvioDownload"

/** Host tests run without Android's Log; logging must never break a download. */
internal fun downloadLog(message: String) {
    runCatching { android.util.Log.i(DOWNLOAD_LOG_TAG, message) }
}
