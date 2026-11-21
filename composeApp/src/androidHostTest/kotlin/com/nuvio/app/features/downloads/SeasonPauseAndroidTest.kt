package com.nuvio.app.features.downloads

import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Android side of a season episode paused by the user (Build 15): the pause keeps the partial
 * file, a later resume continues the same transfer, and a download job that ran the paused episode in
 * its own time goes on with the next one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class SeasonPauseAndroidTest {
    @Before
    fun setUpWorkManager() {
        // Below Android 14 the scheduler pauses and resumes through WorkManager, which a test has to
        // set up itself. Work stays queued: the test driver never reports its constraints as met.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            RuntimeEnvironment.getApplication(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun aJobGoesOnAfterAPausedEpisodeAndStopsOnAFailure() {
        assertEquals(ChainStep.Finished, chainStepAfter(DownloadStatus.Completed))
        assertEquals(ChainStep.SkipPaused, chainStepAfter(DownloadStatus.Paused))
        assertEquals(ChainStep.Stop, chainStepAfter(DownloadStatus.Failed))
        assertEquals(ChainStep.Stop, chainStepAfter(null))
        assertEquals(ChainStep.Stop, chainStepAfter(DownloadStatus.Downloading))
    }

    @Test
    fun pausingKeepsThePartialFileAndResumingContinuesTheSameTransfer() {
        val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())
        val item = downloadItem(id = "e1").copy(fileName = "show-e1.mkv")
        val transfer = scheduler.store.begin(item)
        scheduler.store.update(item.fileName, transfer.generation) {
            it.copy(validator = "\"v1\"", documentUri = "content://tree/doc/1", item = it.item.copy(downloadedBytes = 40L))
        }
        scheduler.directory.mkdirs()
        val partial = File(scheduler.directory, "${item.fileName}.part").apply { writeText("first 40 bytes") }

        scheduler.pause(item.fileName)
        val paused = scheduler.store.get(item.fileName)!!
        assertEquals(DownloadStatus.Paused, paused.item.status)
        assertTrue(partial.isFile, "pausing removed the partial file")

        val resumed = scheduler.enqueue(paused.item)
        assertEquals(DownloadStatus.Downloading, resumed.item.status)
        assertNotEquals(transfer.generation, resumed.generation)
        assertEquals("\"v1\"", resumed.validator, "the resume lost the server validator")
        assertEquals("content://tree/doc/1", resumed.documentUri, "the resume lost the SAF document")
        assertEquals(40L, resumed.item.downloadedBytes)
        assertEquals(transfer.jobId, resumed.jobId)
        assertTrue(partial.isFile, "resuming removed the partial file")
    }

    @Test
    @Config(sdk = [34])
    fun pausingAnEpisodeThatRunsInsideAnotherJobLeavesThatJobScheduled() {
        val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())
        val host = scheduler.enqueue(downloadItem(id = "e1").copy(fileName = "host.mkv"))
        val chained = scheduler.store.begin(downloadItem(id = "e2").copy(fileName = "chained.mkv"))
        assertNotEquals(host.jobId, chained.jobId, "the two episodes share a job id")
        // The scheduler's own namespaced JobScheduler: the one it schedules on and cancels from.
        val jobs = scheduler.jobScheduler()
        val scheduledBefore = jobs.allPendingJobs.map { it.id }

        scheduler.pause(chained.item.fileName)

        assertEquals(DownloadStatus.Paused, scheduler.store.get("chained.mkv")?.item?.status)
        assertEquals(DownloadStatus.Downloading, scheduler.store.get("host.mkv")?.item?.status)
        assertTrue(host.jobId in scheduledBefore, "the host job was not scheduled: $scheduledBefore")
        assertEquals(scheduledBefore, jobs.allPendingJobs.map { it.id }, "pausing the chained episode cancelled the host job")

        // Control: this view does see a cancellation, so the check above is not vacuous.
        scheduler.pause(host.item.fileName)
        assertTrue(host.jobId !in jobs.allPendingJobs.map { it.id }, "pausing the host left its job scheduled")
    }
}
