package com.nuvio.app.features.updater

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.nuvio.app.features.updater.UpdateArchiveCheck.signingIdentity
import java.io.File
import java.security.MessageDigest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The check against Android's PackageManager (Robolectric), on Android 8: certificates come from
 * PackageInfo.signatures there. Android 9+ reads SigningInfo, which Robolectric cannot build here;
 * that path is covered by the logic tests and the device test matrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class UpdateArchiveCheckPackageManagerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val certificate = Signature(byteArrayOf(1, 2, 3, 4))
    private val certificateDigest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun install(packageName: String, signatures: Array<Signature>?) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                versionCode = 1012
                this.signatures = signatures
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun read(packageName: String) =
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)

    @Test
    fun certificatesAreReadFromThePackageManager() {
        install("com.example.signed", arrayOf(certificate))
        val identity = read("com.example.signed").signingIdentity()
        assertEquals(SigningIdentity(setOf(certificateDigest), listOf(certificateDigest)), identity)
    }

    @Test
    fun aPackageWithoutCertificatesHasNoIdentity() {
        install("com.example.unsigned", null)
        assertNull(read("com.example.unsigned").signingIdentity())
        install("com.example.empty", emptyArray())
        assertNull(read("com.example.empty").signingIdentity())
    }

    @Test
    fun aDamagedApkIsRefused() {
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).signatures = arrayOf(certificate)
        val damaged = File.createTempFile("update", ".apk").apply {
            writeText("not an apk")
            deleteOnExit()
        }
        assertEquals(UpdateArchiveProblem.Unreadable, UpdateArchiveCheck.check(context, damaged.path))
    }

    @Test
    fun anAppWhoseCertificateCannotBeReadRefusesEveryUpdate() {
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).signatures = null
        val damaged = File.createTempFile("update", ".apk").apply { deleteOnExit() }
        assertEquals(UpdateArchiveProblem.InstalledUnreadable, UpdateArchiveCheck.check(context, damaged.path))
    }
}
