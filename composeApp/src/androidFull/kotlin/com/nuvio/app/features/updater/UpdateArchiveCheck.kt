package com.nuvio.app.features.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Why a downloaded APK must not be handed to the installer. */
internal enum class UpdateArchiveProblem {
    Unreadable,
    OtherApp,
    NotNewer,
    OtherSigner,
}

/**
 * Checks a downloaded update before the system installer sees it: it must be this app, signed with
 * the same certificate and carry a higher version code. Otherwise Android would refuse it after
 * the user confirmed, or it would replace the app with something else.
 */
internal object UpdateArchiveCheck {
    fun evaluate(
        installedPackage: String,
        installedVersionCode: Long,
        installedSigners: Set<String>,
        archivePackage: String?,
        archiveVersionCode: Long?,
        archiveSigners: Set<String>,
    ): UpdateArchiveProblem? = when {
        archivePackage == null || archiveVersionCode == null || archiveSigners.isEmpty() -> UpdateArchiveProblem.Unreadable
        archivePackage != installedPackage -> UpdateArchiveProblem.OtherApp
        archiveVersionCode <= installedVersionCode -> UpdateArchiveProblem.NotNewer
        installedSigners.isNotEmpty() && archiveSigners != installedSigners -> UpdateArchiveProblem.OtherSigner
        else -> null
    }

    fun check(context: Context, apkPath: String): UpdateArchiveProblem? {
        val manager = context.packageManager
        val installed = runCatching { packageInfo(manager, null, context.packageName) }.getOrNull()
            ?: return null // Nothing to compare with (should not happen for a running app).
        val archive = runCatching { packageInfo(manager, apkPath, null) }.getOrNull()
        return evaluate(
            installedPackage = context.packageName,
            installedVersionCode = installed.versionCodeCompat(),
            installedSigners = installed.signerDigests(),
            archivePackage = archive?.packageName,
            archiveVersionCode = archive?.versionCodeCompat(),
            archiveSigners = archive?.signerDigests().orEmpty(),
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(manager: PackageManager, archivePath: String?, packageName: String?): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (archivePath != null) {
            manager.getPackageArchiveInfo(archivePath, flags)
        } else {
            manager.getPackageInfo(checkNotNull(packageName), flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            signingInfo?.let { info ->
                if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            }
        } else {
            this.signatures
        }
        return signatures.orEmpty().mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
