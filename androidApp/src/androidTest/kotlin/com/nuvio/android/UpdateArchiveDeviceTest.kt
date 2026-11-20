package com.nuvio.android

import android.content.pm.PackageManager
import android.util.Log
import java.io.File
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
        Log.i("UpdateArchiveDeviceTest", "detail ${describeArchive(context.packageManager, apk)}")
        Log.i("UpdateArchiveDeviceTest", "verdict=$verdict expect=$expect apk=$apk")
        assertTrue("verdict $verdict, expected $expect", verdict in expect!!.split("|"))
    }

    /** What Android's parser reads from the file with each flag set, so a wrong verdict can be traced. */
    @Suppress("DEPRECATION")
    private fun describeArchive(manager: PackageManager, apk: String): String {
        val file = File(apk)
        fun read(name: String, flags: Int): String = runCatching {
            val info = manager.getPackageArchiveInfo(apk, flags) ?: return@runCatching "$name=null"
            val signers = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.let { "signingInfo(contents=${it.apkContentsSigners?.size}, history=${it.signingCertificateHistory?.size})" } ?: "signingInfo=null"
            } else {
                ""
            }
            "$name=${info.packageName}/${info.versionCode} signatures=${info.signatures?.size} $signers"
        }.getOrElse { "$name threw ${it.javaClass.simpleName}: ${it.message}" }
        val flags = mutableListOf("plain" to 0, "signatures" to PackageManager.GET_SIGNATURES)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            flags += "signing" to PackageManager.GET_SIGNING_CERTIFICATES
            flags += "both" to (PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
        }
        return "api=${android.os.Build.VERSION.SDK_INT} size=${file.length()} readable=${file.canRead()} " +
            flags.joinToString(" | ") { (name, value) -> read(name, value) }
    }
}
