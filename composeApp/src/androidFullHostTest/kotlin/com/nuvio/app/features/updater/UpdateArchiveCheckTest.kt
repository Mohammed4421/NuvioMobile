package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun noKeyRotationIsTrustedInThisBuild() = assertTrue(UpdateArchiveCheck.trustedRotationTargets.isEmpty())

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
}
