package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_no_space
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool

internal class AndroidDownloadScheduler(val context: Context) {
    val store = AndroidDownloadStore(File(context.filesDir, "download-transfers"))
    val directory = File(context.filesDir, "downloads")
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val adoptedTransfers = Channel<AndroidDownloadTransfer>(Channel.UNLIMITED)
    private val waitingHosts = AtomicInteger(0)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(item: DownloadItem): AndroidDownloadTransfer {
        val previous = store.get(item.fileName)
        val transfer = store.begin(item)
        val existing = previous?.generation == transfer.generation
        if (existing && Build.VERSION.SDK_INT >= 34) return transfer
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val scheduled = runCatching {
                    jobScheduler().schedule(buildJob(transfer)) == JobScheduler.RESULT_SUCCESS
                }.getOrDefault(false)
                if (!scheduled && waitingHosts.get() > 0) {
                    // The app is hidden, so Android refuses a new job; a download job that is still
                    // running picks this transfer up and runs it next.
                    adoptedTransfers.trySend(transfer)
                    return transfer
                }
                check(scheduled) { "Android could not start the download. Open the app and try again." }
            } else {
                val work = OneTimeWorkRequestBuilder<DownloadsTransferWorker>()
                    .setInputData(workDataOf(FILE_NAME to item.fileName, GENERATION to transfer.generation))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(if (item.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                    workName(transfer), if (existing) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, work,
                )
                operation.result.addListener({
                    try {
                        operation.result.get()
                    } catch (error: Exception) {
                        fail(transfer, error)
                        notifyCurrent(item.fileName)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        } catch (error: Exception) {
            fail(transfer, error)
        }
        return store.get(item.fileName) ?: transfer
    }

    @RequiresApi(34)
    internal fun buildJob(transfer: AndroidDownloadTransfer): JobInfo =
        JobInfo.Builder(transfer.jobId, ComponentName(context, DownloadsTransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(
                if (transfer.item.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY,
            )
            .setPersisted(true)
            .setEstimatedNetworkBytes(transfer.item.totalBytes ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply {
                putString(FILE_NAME, transfer.item.fileName)
                putString(GENERATION, transfer.generation)
            })
            .build()

    @Volatile private var namespacedJobs: JobScheduler? = null

    // One namespaced scheduler for the whole class, so schedule, lookup and cancel all go through the
    // same object (Robolectric gives every forNamespace call its own empty job list).
    @RequiresApi(34)
    internal fun jobScheduler(): JobScheduler =
        namespacedJobs ?: context.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE)
            .also { namespacedJobs = it }

    fun restore(item: DownloadItem): DownloadItem {
        var transfer = store.get(item.fileName)?.takeIf { it.item.id == item.id }
            ?: return if (item.status == DownloadStatus.Downloading) {
                item.copy(status = DownloadStatus.Paused, errorMessage = null)
            } else item
        if (transfer.item.status == DownloadStatus.Downloading && Build.VERSION.SDK_INT >= 34 &&
            jobScheduler().getPendingJob(transfer.jobId) == null) {
            pause(item.fileName)
            transfer = store.get(item.fileName) ?: transfer
        }
        return transfer.item
    }

    fun pause(fileName: String) {
        val transfer = store.get(fileName) ?: return
        if (transfer.item.status != DownloadStatus.Downloading) return
        val paused = store.update(fileName, transfer.generation) {
            it.copy(item = it.item.copy(status = DownloadStatus.Paused, errorMessage = null))
        }
        cancelScheduled(transfer)
        paused?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    fun remove(fileName: String) {
        val transfer = store.get(fileName)
        val partialDocument = transfer
            ?.takeIf { it.item.status != DownloadStatus.Completed }
            ?.documentUri
        store.remove(fileName)
        partialDocument?.let { document ->
            cleanupScope.launch { DownloadDocuments.delete(context, document) }
        }
        transfer?.let(::cancelScheduled)
        transfer?.let { DownloadsLiveStatusPlatform.removeNotification(it.item.id) }
        cleanupScope.launch {
            lock(fileName).withLock {
                if (store.get(fileName) == null) {
                    File(directory, "$fileName.part").delete()
                    DownloadSubtitleStorage(File(directory, fileName).toURI().toString()).remove()
                }
            }
        }
    }

    private fun cancelScheduled(transfer: AndroidDownloadTransfer) {
        if (Build.VERSION.SDK_INT >= 34) jobScheduler().cancel(transfer.jobId)
        else WorkManager.getInstance(context).cancelUniqueWork(workName(transfer))
    }

    suspend fun execute(
        transfer: AndroidDownloadTransfer,
        network: Network? = null,
        onProgress: (AndroidDownloadTransfer) -> Unit,
    ): Boolean = lock(transfer.item.fileName).withLock {
        val fileName = transfer.item.fileName
        if (!isActive(transfer)) return@withLock false
        val destination = File(directory, fileName)
        val client = if (network != null) {
            downloadHttpClient.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { network.getAllByName(it).toList() }
                .connectionPool(ConnectionPool())
                .build()
        } else downloadHttpClient
        try {
            DownloadSubtitles.prepare(transfer.item, destination.toURI().toString())
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            if (transfer.item.targetFolderUri != null) {
                executeToFolder(transfer, destination, client, onProgress)
                return@withLock false
            }
            var lastProgressAt = 0L
            val partial = if (destination.isFile) destination else transferAndroidDownload(
                item = transfer.item,
                directory = directory,
                validator = transfer.validator,
                client = client,
                onHeaders = { total, validator ->
                    checkFreeSpace(transfer, total)
                    updateActive(transfer) { it.copy(validator = validator, item = it.item.copy(totalBytes = total)) }
                },
                onProgress = { bytes, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= 1_000L || bytes == total) {
                        lastProgressAt = now
                        updateActive(transfer) {
                            it.copy(item = it.item.copy(downloadedBytes = bytes, totalBytes = total))
                        }?.let(onProgress)
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            updateActive(transfer) { current ->
                if (partial != destination && !partial.renameTo(destination)) {
                    throw IOException("Could not finalize the downloaded file")
                }
                val bytes = destination.length()
                current.copy(item = current.item.copy(
                    status = DownloadStatus.Completed,
                    localFileUri = destination.toURI().toString(),
                    downloadedBytes = bytes,
                    totalBytes = bytes,
                    errorMessage = null,
                ))
            }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            val retry = shouldRetryAndroidDownload(error, transfer.retryCount)
            if (retry) updateActive(transfer) { it.copy(retryCount = it.retryCount + 1) }
            else fail(transfer, error)
            retry
        } finally {
            if (network != null) withContext(NonCancellable + Dispatchers.IO) { client.connectionPool.evictAll() }
        }
    }

    /**
     * Lets a running download job continue with the next season episode. [startNext] asks the
     * season queue to hand over its next episode; when Android refused a separate job for it, the
     * transfer arrives here and the caller runs it in the same job.
     */
    suspend fun takeChainedTransfer(startNext: suspend () -> Boolean): AndroidDownloadTransfer? {
        waitingHosts.incrementAndGet()
        try {
            while (true) {
                val stale = adoptedTransfers.tryReceive().getOrNull() ?: break
                if (isActive(stale)) return stale
            }
            if (!startNext()) return null
            return adoptedTransfers.tryReceive().getOrNull()?.takeIf(::isActive)
        } finally {
            waitingHosts.decrementAndGet()
        }
    }

    /**
     * Writes the download straight into the folder the user picked, resuming the same document
     * after a pause or a lost connection, then gives it its final name.
     */
    private suspend fun executeToFolder(
        transfer: AndroidDownloadTransfer,
        internalAnchor: File,
        client: okhttp3.OkHttpClient,
        onProgress: (AndroidDownloadTransfer) -> Unit,
    ) {
        val treeUri = checkNotNull(transfer.item.targetFolderUri)
        if (!DownloadDocuments.hasPermission(context, treeUri)) {
            throw IllegalStateException("The download folder is no longer accessible. Choose it again in Downloads.")
        }
        val current = store.get(transfer.item.fileName)?.takeIf { it.generation == transfer.generation } ?: transfer
        val resumable = current.documentUri?.takeIf { DownloadDocuments.exists(context, it) }
        val documentUri = resumable ?: run {
            val folder = DocumentDownloads.folderFor(context, transfer.item)
            val name = DocumentDownloads.uniqueName(context, transfer.item, folder, DocumentDownloads.displayName(transfer.item))
            val created = DownloadDocuments.createFile(context, folder, "$name.part", "application/octet-stream").toString()
            updateActive(transfer) { it.copy(documentUri = created, validator = null) }
            created
        }
        var lastProgressAt = 0L
        transferAndroidDownloadTo(
            item = transfer.item,
            sink = DocumentDownloadPartialSink(context, Uri.parse(documentUri)),
            validator = if (resumable != null) current.validator else null,
            client = client,
            onHeaders = { total, validator ->
                checkFreeSpace(transfer, total)
                updateActive(transfer) { it.copy(validator = validator, item = it.item.copy(totalBytes = total)) }
            },
            onProgress = { bytes, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 1_000L || bytes == total) {
                    lastProgressAt = now
                    updateActive(transfer) {
                        it.copy(item = it.item.copy(downloadedBytes = bytes, totalBytes = total))
                    }?.let(onProgress)
                }
            },
        )
        currentCoroutineContext().ensureActive()
        val partialName = DownloadDocuments.displayName(context, documentUri)
        val finalName = partialName?.removeSuffix(".part")?.takeIf { it.isNotBlank() }
            ?: DocumentDownloads.displayName(transfer.item)
        val finalUri = finishDocumentName(transfer, documentUri, finalName)
        DocumentDownloads.finishSubtitles(context, transfer.item, internalAnchor, finalUri, finalName)
        val bytes = DownloadDocuments.size(context, finalUri)
        updateActive(transfer) { record ->
            record.copy(
                documentUri = null,
                item = record.item.copy(
                    status = DownloadStatus.Completed,
                    localFileUri = finalUri,
                    downloadedBytes = bytes ?: record.item.downloadedBytes,
                    totalBytes = bytes ?: record.item.totalBytes,
                    errorMessage = null,
                ),
            )
        }
    }

    /**
     * Gives the finished document its final name and returns its URI. Some storage providers do not
     * rename documents; the bytes are then copied into a new document with the final name and
     * checked before the `.part` file is deleted. If even that fails, the `.part` document is kept:
     * it still plays in the app.
     */
    private fun finishDocumentName(transfer: AndroidDownloadTransfer, documentUri: String, finalName: String): String {
        val renamed = DownloadDocuments.rename(context, Uri.parse(documentUri), finalName).toString()
        val currentName = DownloadDocuments.displayName(context, renamed)
        if (currentName != null && !currentName.endsWith(".part")) return renamed
        val expectedSize = DownloadDocuments.size(context, documentUri) ?: return renamed
        var copy: Uri? = null
        return try {
            val folder = DocumentDownloads.folderFor(context, transfer.item)
            val created = DownloadDocuments.createFile(context, folder, finalName, "application/octet-stream")
            copy = created
            val resolver = context.contentResolver
            resolver.openInputStream(Uri.parse(documentUri)).use { input ->
                resolver.openOutputStream(created, "wt").use { output ->
                    checkNotNull(input).copyTo(checkNotNull(output), 256 * 1024)
                }
            }
            check(DownloadDocuments.size(context, created.toString()) == expectedSize) { "Copy size mismatch" }
            DownloadDocuments.delete(context, documentUri)
            created.toString()
        } catch (_: Exception) {
            copy?.let { DownloadDocuments.delete(context, it.toString()) }
            renamed
        }
    }

    /** Fails early, with a clear message, when the target storage cannot hold the rest of the file. */
    private fun checkFreeSpace(transfer: AndroidDownloadTransfer, totalBytes: Long?) {
        val total = totalBytes ?: return
        val written = store.get(transfer.item.fileName)?.item?.downloadedBytes ?: 0L
        val remaining = (total - written).coerceAtLeast(0L)
        val available = availableBytes(transfer.item) ?: return
        if (remaining + FREE_SPACE_MARGIN_BYTES > available) {
            throw IllegalStateException(
                runBlocking { getString(Res.string.downloads_error_no_space, formatGigabytes(remaining)) },
            )
        }
    }

    private fun availableBytes(item: DownloadItem): Long? = runCatching {
        val folder = item.targetFolderUri
        val path = if (folder == null) {
            directory.apply { mkdirs() }
        } else {
            val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(folder))
            val volume = documentId.substringBefore(':')
            if (volume.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory()
            } else {
                File("/storage/$volume")
            }
        }
        // A volume that reports no size at all (some providers, test environments) is unknown.
        StatFs(path.path).takeIf { it.totalBytes > 0L }?.availableBytes
    }.getOrNull()

    private fun formatGigabytes(bytes: Long): String {
        val gigabyte = 1024L * 1024L * 1024L
        val tenths = (bytes * 10 + gigabyte - 1) / gigabyte
        return "${tenths / 10}.${tenths % 10} GB"
    }

    fun isActive(transfer: AndroidDownloadTransfer): Boolean = store.get(transfer.item.fileName)?.let {
        it.generation == transfer.generation && it.item.status == DownloadStatus.Downloading
    } == true

    fun fail(transfer: AndroidDownloadTransfer, error: Exception) {
        updateActive(transfer) {
            it.copy(item = it.item.copy(status = DownloadStatus.Failed, errorMessage = error.message ?: "Download failed"))
        }
    }

    fun notifyCurrent(fileName: String) {
        store.get(fileName)?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    private fun updateActive(
        transfer: AndroidDownloadTransfer,
        update: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = store.update(transfer.item.fileName, transfer.generation) {
        if (it.item.status == DownloadStatus.Downloading) {
            update(it).let { changed -> changed.copy(item = changed.item.copy(updatedAtEpochMs = System.currentTimeMillis())) }
        } else it
    }

    private fun lock(fileName: String): Mutex = locks.getOrPut(fileName) { Mutex() }
    private fun workName(transfer: AndroidDownloadTransfer) = "nuvio-download-${transfer.jobId}"

    companion object {
        const val FILE_NAME = "download_file_name"
        const val GENERATION = "download_generation"
        const val JOB_NAMESPACE = "nuvio-downloads"
        private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L
    }
}

internal fun shouldRetryAndroidDownload(error: Exception, retries: Int): Boolean =
    retries < 4 && when (error) {
        is DownloadHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        is IOException -> true
        else -> false
    }
