package com.nuvio.app.features.downloads

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@RequiresApi(34)
class DownloadsTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = mutableMapOf<String, Job>()

    override fun onStartJob(params: JobParameters): Boolean = startTransfer(params)

    override fun onNetworkChanged(params: JobParameters) {
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return
        if (runs[generation]?.isActive == true) startTransfer(params)
    }

    private fun startTransfer(params: JobParameters): Boolean {
        val scheduler = DownloadsPlatformDownloader.scheduler(this)
        val fileName = params.extras.getString(AndroidDownloadScheduler.FILE_NAME) ?: return false
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return false
        val transfer = scheduler.store.get(fileName)?.takeIf { it.generation == generation } ?: return false
        if (!scheduler.isActive(transfer)) return false
        DownloadsLiveStatusPlatform.initialize(this)
        setNotification(params, DownloadsLiveStatusPlatform.notificationId(transfer.item.id),
            DownloadsLiveStatusPlatform.buildNotification(this, transfer.item), JOB_END_NOTIFICATION_POLICY_REMOVE)
        val run = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val retry = scheduler.execute(transfer, params.network) { updated ->
                    updated.item.totalBytes?.let { updateEstimatedNetworkBytes(params, it, 0L) }
                    updateTransferredNetworkBytes(params, updated.item.downloadedBytes, 0L)
                    DownloadsLiveStatusPlatform.notifyTransfer(updated.item)
                }
                scheduler.notifyCurrent(fileName)
                if (!retry && scheduler.store.get(fileName)?.item?.status == DownloadStatus.Completed) {
                    runChainedSeasonEpisodes(scheduler, params, finishedId = transfer.item.id)
                }
                jobFinished(params, retry)
            } finally {
                if (runs[generation] === currentCoroutineContext()[Job]) runs.remove(generation)
            }
        }
        runs.put(generation, run)?.cancel()
        run.start()
        return true
    }

    /**
     * Android does not allow starting a new download job while the app is hidden, so the next
     * episodes of a season queue run here, one after another, while this job is still active.
     */
    private suspend fun runChainedSeasonEpisodes(
        scheduler: AndroidDownloadScheduler,
        params: JobParameters,
        finishedId: String,
    ) {
        var finished: String? = finishedId
        var paused: String? = null
        while (true) {
            val next = scheduler.takeChainedTransfer {
                SeasonDownloadQueue.continueInBackground(finished, pausedDownloadId = paused)
            } ?: return
            DownloadsLiveStatusPlatform.notifyTransfer(next.item)
            var retry = true
            while (retry) {
                retry = executeChained(scheduler, next, params)
                if (retry) delay(30_000L)
            }
            scheduler.notifyCurrent(next.item.fileName)
            when (chainStepAfter(scheduler.store.get(next.item.fileName)?.item?.status)) {
                ChainStep.Finished -> { finished = next.item.id; paused = null }
                ChainStep.SkipPaused -> { finished = null; paused = next.item.id }
                ChainStep.Stop -> return
            }
        }
    }

    /**
     * Runs a chained transfer and stops it as soon as it is paused or removed (from the
     * notification or the app), since it has no job of its own for Android to stop.
     */
    private suspend fun executeChained(
        scheduler: AndroidDownloadScheduler,
        transfer: AndroidDownloadTransfer,
        params: JobParameters,
    ): Boolean = coroutineScope {
        val run = async {
            scheduler.execute(transfer, params.network) { updated ->
                updateTransferredNetworkBytes(params, updated.item.downloadedBytes, 0L)
                DownloadsLiveStatusPlatform.notifyTransfer(updated.item)
            }
        }
        val watcher = launch {
            scheduler.store.transfers.first { !scheduler.isActive(transfer) }
            run.cancel()
        }
        run.join()
        watcher.cancel()
        if (run.isCancelled) false else run.await()
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val scheduler = DownloadsPlatformDownloader.scheduler(this)
        val fileName = params.extras.getString(AndroidDownloadScheduler.FILE_NAME) ?: return false
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return false
        runs.remove(generation)?.cancel()
        val transfer = scheduler.store.get(fileName)?.takeIf { it.generation == generation } ?: return false
        if (params.stopReason == JobParameters.STOP_REASON_USER) {
            scheduler.pause(fileName)
            return false
        }
        return scheduler.isActive(transfer)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

internal enum class ChainStep { Finished, SkipPaused, Stop }

/**
 * What a download job does after a season episode it ran in its own time ends. Finished: the next
 * episode. Paused by the user (the notification or the app): it keeps its partial file and its place,
 * and the queue goes on with the next episode in this same job, which Android still allows. Failed,
 * deleted or anything else: the job stops; the queue goes on when the app is opened.
 */
internal fun chainStepAfter(status: DownloadStatus?): ChainStep = when (status) {
    DownloadStatus.Completed -> ChainStep.Finished
    DownloadStatus.Paused -> ChainStep.SkipPaused
    else -> ChainStep.Stop
}
