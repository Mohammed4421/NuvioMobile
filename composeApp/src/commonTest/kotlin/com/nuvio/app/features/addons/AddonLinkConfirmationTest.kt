package com.nuvio.app.features.addons

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonLinkConfirmationTest {
    private val manifest = "https://addon.example.com/secret-token/manifest.json"

    @Test
    fun aLinkAloneInstallsNothing() = runTest {
        val confirmation = AddonLinkConfirmation()
        val installed = mutableListOf<String>()
        confirmation.request(manifest)
        assertEquals(manifest, confirmation.manifestUrl.value)
        assertTrue(installed.isEmpty())
        confirmation.dismiss()
        assertNull(confirmation.confirm { installed += it })
        assertTrue(installed.isEmpty(), "a dismissed link must not install")
    }

    @Test
    fun confirmingInstallsTheLinkedAddonOnce() = runTest {
        val confirmation = AddonLinkConfirmation()
        val installed = mutableListOf<String>()
        confirmation.request(manifest)
        confirmation.confirm { installed += it }
        confirmation.confirm { installed += it }
        assertEquals(listOf(manifest), installed)
        assertNull(confirmation.manifestUrl.value)
    }

    @Test
    fun theUserIsShownTheHostNotThePathOrToken() {
        assertEquals("addon.example.com", AddonLinkConfirmation.displayAddress(manifest))
        assertEquals("10.0.0.5:7000", AddonLinkConfirmation.displayAddress("http://10.0.0.5:7000/manifest.json"))
        assertEquals("host.example", AddonLinkConfirmation.displayAddress("https://user:pw@host.example/manifest.json"))
    }
}
