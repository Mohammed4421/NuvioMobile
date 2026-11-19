package com.nuvio.app.features.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/** Why a downloaded APK must not be handed to the installer. */
internal enum class UpdateArchiveProblem {
    /** The downloaded file is not a readable APK (damaged, truncated, not an APK). */
    Unreadable,

    /** The APK carries no signing certificate that could be read. */
    Unsigned,

    /** This app's own package or signing certificate could not be read, so nothing can be compared. */
    InstalledUnreadable,
    OtherApp,
    NotNewer,
    OtherSigner,
}

/**
 * Signing certificates of an APK as SHA-256 digests (lowercase hex).
 *
 * [current] holds the certificates signing the APK now (more than one only for multi-signer APKs).
 * [lineage] is the APK Signature Scheme v3 rotation history, oldest first and ending with the
 * current certificate; without rotation it is just the current certificate. Android before 9 knows
 * no rotation, so there it equals [current].
 */
internal data class SigningIdentity(
    val current: Set<String>,
    val lineage: List<String>,
)

/** What the check needs to know about one package: the installed app or the downloaded APK. */
internal data class PackageSnapshot(
    val packageName: String?,
    val versionCode: Long?,
    val signing: SigningIdentity?,
)

/**
 * Checks a downloaded update before the system installer sees it: it must be this app, signed with
 * the same certificate and carry a higher version code. Otherwise Android would refuse it after the
 * user confirmed, or it would replace the app with something else.
 *
 * The check fails closed: when anything it needs cannot be read (the APK, its certificates, or this
 * app's own certificate) the update is refused with a reason, never waved through. Android's
 * installer still makes the final decision; this check only refuses earlier and more clearly.
 */
internal object UpdateArchiveCheck {
    /**
     * Certificates an announced signing-key rotation may move to (SHA-256, lowercase hex). An update
     * signed with a rotated key is accepted only when the installed certificate is in its v3 lineage
     * AND its new certificate is listed here, so a key rotation cannot be made up by whoever holds
     * the old key.
     *
     * The Nuvio Plus release key (docs/plus/signing-migration.md), which replaced the public key of
     * builds 8-12 (40335e31...f48a) through a v3 lineage.
     */
    val trustedRotationTargets: Set<String> = setOf(
        "99c0a98cc1700bd1186018e480eab670daaf55d4d8c5316d7cf03d788d431e35",
    )

    fun evaluate(
        installed: PackageSnapshot?,
        archive: PackageSnapshot?,
        trustedRotationTargets: Set<String> = this.trustedRotationTargets,
    ): UpdateArchiveProblem? {
        val installedPackage = installed?.packageName
        val installedVersion = installed?.versionCode
        val installedSigning = installed?.signing
        if (installedPackage == null || installedVersion == null || installedSigning == null ||
            installedSigning.current.isEmpty()
        ) {
            return UpdateArchiveProblem.InstalledUnreadable
        }
        val archivePackage = archive?.packageName
        val archiveVersion = archive?.versionCode
        if (archivePackage == null || archiveVersion == null) return UpdateArchiveProblem.Unreadable
        val archiveSigning = archive.signing
        if (archiveSigning == null || archiveSigning.current.isEmpty()) return UpdateArchiveProblem.Unsigned
        return when {
            archivePackage != installedPackage -> UpdateArchiveProblem.OtherApp
            archiveVersion <= installedVersion -> UpdateArchiveProblem.NotNewer
            !signerAccepted(installedSigning, archiveSigning, trustedRotationTargets) -> UpdateArchiveProblem.OtherSigner
            else -> null
        }
    }

    /**
     * The same certificates are always accepted. A different one only as a pinned key rotation:
     * one signer on each side, the installed certificate earlier in the update's lineage, and the
     * update's certificate listed in [trustedRotations].
     */
    internal fun signerAccepted(
        installed: SigningIdentity,
        archive: SigningIdentity,
        trustedRotations: Set<String>,
    ): Boolean {
        if (archive.current == installed.current) return true
        if (installed.current.size != 1 || archive.current.size != 1) return false
        val installedCertificate = installed.current.single()
        val newCertificate = archive.current.single()
        return newCertificate in trustedRotations &&
            archive.lineage.lastOrNull() == newCertificate &&
            installedCertificate in archive.lineage.dropLast(1)
    }

    fun check(context: Context, apkPath: String): UpdateArchiveProblem? = checkWith(
        readInstalled = { packageInfo(context.packageManager, null, context.packageName)?.snapshot() },
        readArchive = { packageInfo(context.packageManager, apkPath, null)?.snapshot() },
    )

    /** Runs the check; a reader that throws counts as unreadable, never as a pass. */
    internal fun checkWith(
        readInstalled: () -> PackageSnapshot?,
        readArchive: () -> PackageSnapshot?,
        trustedRotations: Set<String> = trustedRotationTargets,
    ): UpdateArchiveProblem? {
        val installed = runCatching(readInstalled).getOrNull()
            ?: return UpdateArchiveProblem.InstalledUnreadable
        val archive = runCatching(readArchive).getOrNull()
        return evaluate(installed, archive, trustedRotations)
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

    internal fun PackageInfo.snapshot(): PackageSnapshot = PackageSnapshot(
        packageName = packageName,
        versionCode = versionCodeCompat(),
        signing = signingIdentity(),
    )

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    /** Null when the certificates cannot be read at all. */
    @Suppress("DEPRECATION")
    internal fun PackageInfo.signingIdentity(): SigningIdentity? {
        if (Build.VERSION.SDK_INT >= 28) {
            val info = signingInfo ?: return null
            val current = info.apkContentsSigners.digests()
            if (current.isEmpty()) return null
            val lineage = if (info.hasMultipleSigners()) {
                current
            } else {
                info.signingCertificateHistory.digests().ifEmpty { current }
            }
            return SigningIdentity(current = current.toSet(), lineage = lineage)
        }
        val current = signatures.digests()
        if (current.isEmpty()) return null
        return SigningIdentity(current = current.toSet(), lineage = current)
    }

    private fun Array<Signature>?.digests(): List<String> = orEmpty().map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
