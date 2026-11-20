package com.nuvio.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.features.updater.UpdateArchiveDeviceCheck
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The in-app update check on a real Android system, driven by .github/plus/update-device-check.sh:
 * Nuvio Plus is installed (`installedPackage`), a downloaded update is at `apk`, and the check must
 * answer `expect` ("accepted", or the names of the refusals allowed, separated by "|").
 * Without these arguments (a plain run of all device tests) it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class UpdateArchiveDeviceTest {
    @Test
    fun theInAppUpdateCheckGivesTheExpectedVerdict() {
        val args = InstrumentationRegistry.getArguments()
        val apk = args.getString("apk")
        val expect = args.getString("expect")
        assumeTrue("run by update-device-check.sh only", apk != null && expect != null)
        val installed = args.getString("installedPackage") ?: "com.nuvio.app.plus"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val verdict = UpdateArchiveDeviceCheck.problem(context, installed, apk!!) ?: "accepted"
        Log.i("UpdateArchiveDeviceTest", "verdict=$verdict expect=$expect apk=$apk")
        assertTrue("verdict $verdict, expected $expect", verdict in expect!!.split("|"))
    }
}
