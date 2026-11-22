package com.nuvio.app.features.addons

import android.content.Context
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Build 15 journey "Add-ons", case P (persistence after restart): what the app stores when an add-on
 * is installed or disabled is what it reads back when it starts again (MainActivity calls
 * AddonStorage.initialize, then AddonRepository.initialize loads the profile's add-ons), per profile,
 * with the manifest address unchanged (its path may carry the user's token).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddonStoragePersistenceTest {
    private val withToken = "https://addon.example.com/secret-token/manifest.json"
    private val local = "http://10.0.0.5:7000/manifest.json"

    @Before
    fun clean() {
        RuntimeEnvironment.getApplication().getSharedPreferences("nuvio_addons", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun restart() {
        // A new process starts with a fresh object reading the same preferences file.
        AddonStorage.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun installedAddonsAndTheirStateAreReadBackAfterARestart() {
        restart()
        AddonStorage.saveInstalledAddonUrls(1, listOf(withToken, local))
        AddonStorage.saveAddonEnabledStates(1, mapOf(withToken to true, local to false))
        restart()
        assertEquals(listOf(withToken, local), AddonStorage.loadInstalledAddonUrls(1))
        assertEquals(mapOf(withToken to true, local to false), AddonStorage.loadAddonEnabledStates(1))
        val stored = RuntimeEnvironment.getApplication().getSharedPreferences("nuvio_addons", Context.MODE_PRIVATE)
        assertTrue(stored.getString("installed_manifest_urls_1", null).orEmpty().contains(withToken))
    }

    @Test
    fun anotherProfileStartsWithItsOwnAddonsOnly() {
        restart()
        AddonStorage.saveInstalledAddonUrls(1, listOf(withToken))
        restart()
        assertEquals(emptyList(), AddonStorage.loadInstalledAddonUrls(2))
        AddonStorage.saveInstalledAddonUrls(2, listOf(local))
        restart()
        assertEquals(listOf(withToken), AddonStorage.loadInstalledAddonUrls(1))
        assertEquals(listOf(local), AddonStorage.loadInstalledAddonUrls(2))
    }

    @Test
    fun removingTheLastAddonIsKeptAfterARestart() {
        restart()
        AddonStorage.saveInstalledAddonUrls(1, listOf(withToken))
        AddonStorage.saveInstalledAddonUrls(1, emptyList())
        restart()
        assertEquals(emptyList(), AddonStorage.loadInstalledAddonUrls(1))
    }
}
