package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Nuvio Plus (download-location, D-08): a Wi-Fi only download waits for an unmetered network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class PlusWifiOnlySchedulingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun wifiOnlyDownloadsWaitForAnUnmeteredNetwork() {
        val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())
        val wifiOnly = scheduler.buildJob(AndroidDownloadStore(temporary.newFolder()).begin(downloadItem().copy(wifiOnly = true)))
        val anyNetwork = scheduler.buildJob(AndroidDownloadStore(temporary.newFolder()).begin(downloadItem().copy(wifiOnly = false)))

        @Suppress("DEPRECATION")
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, wifiOnly.networkType)
        @Suppress("DEPRECATION")
        assertEquals(JobInfo.NETWORK_TYPE_ANY, anyNetwork.networkType)
    }
}
