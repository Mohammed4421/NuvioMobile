package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateArchiveCheckTest {
    private val pkg = "com.nuvio.app.plus"
    private val key = setOf("aa")

    private fun check(archivePackage: String? = pkg, code: Long? = 1013, signers: Set<String> = key) =
        UpdateArchiveCheck.evaluate(pkg, 1012, key, archivePackage, code, signers)

    @Test
    fun aNewerBuildOfThisAppWithTheSameKeyPasses() = assertNull(check())

    @Test
    fun anotherAppIsRefused() = assertEquals(UpdateArchiveProblem.OtherApp, check(archivePackage = "com.nuvio.app"))

    @Test
    fun theSameOrAnOlderVersionIsRefused() {
        assertEquals(UpdateArchiveProblem.NotNewer, check(code = 1012))
        assertEquals(UpdateArchiveProblem.NotNewer, check(code = 157))
    }

    @Test
    fun anotherSigningKeyIsRefused() = assertEquals(UpdateArchiveProblem.OtherSigner, check(signers = setOf("bb")))

    @Test
    fun anUnreadableFileIsRefused() {
        assertEquals(UpdateArchiveProblem.Unreadable, check(archivePackage = null))
        assertEquals(UpdateArchiveProblem.Unreadable, check(signers = emptySet()))
    }
}
