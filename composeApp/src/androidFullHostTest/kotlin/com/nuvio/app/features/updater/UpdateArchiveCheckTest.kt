package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UpdateArchiveCheckTest {
    private val pkg = "com.nuvio.app.plus"
    private val key = "aa"
    private val newKey = "bb"

    private fun identity(vararg lineage: String) = SigningIdentity(current = setOf(lineage.last()), lineage = lineage.toList())

    private val installed = PackageSnapshot(pkg, 1012, identity(key))

    private fun archive(packageName: String? = pkg, code: Long? = 1013, signing: SigningIdentity? = identity(key)) =
        PackageSnapshot(packageName, code, signing)

    private fun check(
        archive: PackageSnapshot? = archive(),
        installed: PackageSnapshot? = this.installed,
        pinned: Set<String> = emptySet(),
    ) = UpdateArchiveCheck.evaluate(installed, archive, pinned)

    @Test
    fun aNewerBuildOfThisAppWithTheSameKeyPasses() = assertNull(check())

    @Test
    fun anotherAppIsRefused() = assertEquals(UpdateArchiveProblem.OtherApp, check(archive(packageName = "com.nuvio.app")))

    @Test
    fun theSameOrAnOlderVersionIsRefused() {
        assertEquals(UpdateArchiveProblem.NotNewer, check(archive(code = 1012)))
        assertEquals(UpdateArchiveProblem.NotNewer, check(archive(code = 157)))
    }

    @Test
    fun anotherSigningKeyIsRefused() =
        assertEquals(UpdateArchiveProblem.OtherSigner, check(archive(signing = identity(newKey))))

    @Test
    fun aDamagedFileIsRefused() {
        assertEquals(UpdateArchiveProblem.Unreadable, check(archive = null))
        assertEquals(UpdateArchiveProblem.Unreadable, check(archive(packageName = null)))
        assertEquals(UpdateArchiveProblem.Unreadable, check(archive(code = null)))
    }

    @Test
    fun anApkWithoutReadableCertificatesIsRefused() {
        assertEquals(UpdateArchiveProblem.Unsigned, check(archive(signing = null)))
        assertEquals(UpdateArchiveProblem.Unsigned, check(archive(signing = SigningIdentity(emptySet(), emptyList()))))
    }

    /** Before this check failed closed, these cases let any APK through to the installer. */
    @Test
    fun anUnreadableInstalledAppRefusesInsteadOfSkippingTheCheck() {
        val stranger = archive(signing = identity(newKey))
        assertEquals(UpdateArchiveProblem.InstalledUnreadable, check(stranger, installed = null))
        assertEquals(UpdateArchiveProblem.InstalledUnreadable, check(stranger, installed = installed.copy(signing = null)))
        assertEquals(
            UpdateArchiveProblem.InstalledUnreadable,
            check(stranger, installed = installed.copy(signing = SigningIdentity(emptySet(), emptyList()))),
        )
        assertEquals(UpdateArchiveProblem.InstalledUnreadable, check(stranger, installed = installed.copy(versionCode = null)))
        assertEquals(UpdateArchiveProblem.InstalledUnreadable, check(stranger, installed = installed.copy(packageName = null)))
    }

    @Test
    fun aReaderThatThrowsCountsAsUnreadable() {
        assertEquals(
            UpdateArchiveProblem.InstalledUnreadable,
            UpdateArchiveCheck.checkWith(readInstalled = { error("no package") }, readArchive = { archive() }),
        )
        assertEquals(
            UpdateArchiveProblem.Unreadable,
            UpdateArchiveCheck.checkWith(readInstalled = { installed }, readArchive = { throw IllegalStateException("bad zip") }),
        )
        assertNull(UpdateArchiveCheck.checkWith(readInstalled = { installed }, readArchive = { archive() }))
    }

    private val publicKey = "40335e3135578dd858dc434717b9344015b62872dcb861d52474c38a1f99f48a"
    private val releaseKey = "99c0a98cc1700bd1186018e480eab670daaf55d4d8c5316d7cf03d788d431e35"

    @Test
    fun onlyTheReleaseKeyIsTrustedAsARotation() =
        assertEquals(setOf(releaseKey), UpdateArchiveCheck.trustedRotationTargets)

    /** The real move: build 12 (public key) -> build 13 (release key, lineage) -> later builds. */
    @Test
    fun theMoveToTheReleaseKeyAndTheBuildsAfterIt() {
        val pinned = UpdateArchiveCheck.trustedRotationTargets
        val build12 = PackageSnapshot(pkg, 1012, identity(publicKey))
        val build13 = PackageSnapshot(pkg, 1013, identity(publicKey, releaseKey))
        assertNull(UpdateArchiveCheck.evaluate(build12, build13, pinned))
        assertNull(UpdateArchiveCheck.evaluate(build13, PackageSnapshot(pkg, 1014, identity(publicKey, releaseKey)), pinned))
        // What the holder of the public key can make, offered to a moved app: refused.
        assertEquals(
            UpdateArchiveProblem.OtherSigner,
            UpdateArchiveCheck.evaluate(build13, PackageSnapshot(pkg, 1014, identity(publicKey)), pinned),
        )
        assertEquals(
            UpdateArchiveProblem.OtherSigner,
            UpdateArchiveCheck.evaluate(build13, PackageSnapshot(pkg, 1014, identity(publicKey, "cc")), pinned),
        )
        // And offered to build 12 with a made-up lineage to another key: refused, that key is not pinned.
        assertEquals(
            UpdateArchiveProblem.OtherSigner,
            UpdateArchiveCheck.evaluate(build12, PackageSnapshot(pkg, 1013, identity(publicKey, "cc")), pinned),
        )
    }

    @Test
    fun aRotatedKeyIsRefusedUnlessItsNewCertificateIsPinned() {
        val rotated = archive(signing = identity(key, newKey))
        assertEquals(UpdateArchiveProblem.OtherSigner, check(rotated))
        assertNull(check(rotated, pinned = setOf(newKey)))
    }

    @Test
    fun aPinnedCertificateWithoutTheInstalledKeyInItsLineageIsRefused() {
        assertEquals(UpdateArchiveProblem.OtherSigner, check(archive(signing = identity(newKey)), pinned = setOf(newKey)))
        assertEquals(UpdateArchiveProblem.OtherSigner, check(archive(signing = identity("cc", newKey)), pinned = setOf(newKey)))
    }

    @Test
    fun aLineageThatDoesNotEndWithTheSigningCertificateIsRefused() {
        val forged = SigningIdentity(current = setOf(newKey), lineage = listOf(newKey, key))
        assertEquals(UpdateArchiveProblem.OtherSigner, check(archive(signing = forged), pinned = setOf(newKey)))
    }

    @Test
    fun afterARotationTheNewKeyKeepsWorkingAndTheOldOneIsRefused() {
        val rotatedInstall = installed.copy(signing = identity(key, newKey))
        assertNull(check(archive(signing = identity(key, newKey)), installed = rotatedInstall))
        assertEquals(UpdateArchiveProblem.OtherSigner, check(archive(signing = identity(key)), installed = rotatedInstall, pinned = setOf(key)))
    }

    @Test
    fun multipleSignersMustMatchExactly() {
        val both = SigningIdentity(setOf(key, newKey), listOf(key, newKey))
        assertNull(check(archive(signing = both), installed = installed.copy(signing = both)))
        assertEquals(
            UpdateArchiveProblem.OtherSigner,
            check(archive(signing = SigningIdentity(setOf(key, "cc"), listOf(key, "cc"))), installed = installed.copy(signing = both)),
        )
        assertFalse(UpdateArchiveCheck.signerAccepted(identity(key), both, setOf(newKey)))
    }

    @Suppress("DEPRECATION")
    @Test
    fun android9AndLaterAskForBothKindsOfCertificates() {
        // Android 9 reads an archive's certificates only with GET_SIGNATURES; without it every
        // update was "Unsigned" there.
        for (sdk in listOf(28, 29, 34, 36)) {
            val flags = UpdateArchiveCheck.packageInfoFlags(sdk)
            kotlin.test.assertTrue(flags and android.content.pm.PackageManager.GET_SIGNATURES != 0, "sdk $sdk")
            kotlin.test.assertTrue(flags and android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES != 0, "sdk $sdk")
        }
        kotlin.test.assertEquals(android.content.pm.PackageManager.GET_SIGNATURES, UpdateArchiveCheck.packageInfoFlags(26))
    }
}
