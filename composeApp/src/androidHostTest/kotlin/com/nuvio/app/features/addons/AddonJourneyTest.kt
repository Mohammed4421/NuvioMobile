package com.nuvio.app.features.addons

import android.app.Application
import android.content.Context
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Build 15 journey "Add-ons / networking" (P3-05) against the real [AddonRepository], the real
 * manifest parser, the real OkHttp client (60 s timeouts) and the real SharedPreferences storage.
 * Add-on servers are a local MockWebServer. A "restart" drops the repository's memory
 * ([AddonRepository.clearLocalState], which also cancels the pending server push) and reopens the
 * same preferences file, the way MainActivity starts the app again.
 *
 * Every add-on path carries a secret token, as configured add-ons do; error messages shown to the
 * user must never repeat it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddonJourneyTest {
    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val context get() = RuntimeEnvironment.getApplication()
    private val profile get() = ProfileRepository.activeProfileId

    @Before
    fun setUp() {
        context.getSharedPreferences("nuvio_addons", Context.MODE_PRIVATE).edit().clear().commit()
        AddonStorage.initialize(context)
        AddonRepository.clearLocalState()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?').orEmpty()
                hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                return routes[path]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        AddonRepository.clearLocalState()
        server.shutdown()
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun pathOf(name: String) = "/$name/$SECRET/manifest.json"

    /** The address a user would paste: without the manifest.json suffix. */
    private fun rawUrl(name: String) = server.url("/$name/$SECRET").toString()

    private fun manifestUrl(name: String) = server.url(pathOf(name)).toString()

    private fun manifestJson(name: String) =
        "{\"id\":\"org.test.$name\",\"name\":\"Addon $name\",\"version\":\"1.0.0\"," +
            "\"resources\":[\"stream\"],\"types\":[\"movie\"]," +
            "\"catalogs\":[{\"type\":\"movie\",\"id\":\"$name-top\"}]}"

    private fun serve(name: String, body: String = manifestJson(name)) {
        routes[pathOf(name)] = { MockResponse().setHeader("Content-Type", "application/json").setBody(body) }
    }

    private fun hitsOf(name: String) = hits[pathOf(name)]?.get() ?: 0

    private fun restart() {
        AddonRepository.clearLocalState()
        AddonStorage.initialize(context)
    }

    private suspend fun awaitIdle() {
        withTimeout(15_000) { AddonRepository.uiState.first { state -> state.addons.none { it.isRefreshing } } }
    }

    private fun addons() = AddonRepository.uiState.value.addons

    private fun urls() = addons().map { it.manifestUrl }

    /** Installs [names] the way a returning user has them: stored, then loaded at start. */
    private suspend fun seedInstalled(vararg names: String) {
        names.forEach { serve(it) }
        AddonStorage.saveInstalledAddonUrls(profile, names.map(::manifestUrl))
        AddonRepository.initialize()
        awaitIdle()
        assertTrue(addons().all { it.manifest != null }, "seeded add-ons must load: ${addons()}")
    }

    private fun assertNoSecret(message: String) {
        assertTrue(message.isNotBlank(), "the user must be told what went wrong")
        assertFalse(message.contains(SECRET), "error shown to the user repeats the add-on token: $message")
        assertTrue(message.length < 2_000, "error shown to the user is ${message.length} characters long")
    }

    // ---- P: persistence after restart ----------------------------------------------------------

    @Test
    fun installedAddonsComeBackAfterARestartWithOrderAndEnabledState(): Unit = runBlocking {
        listOf("a", "b", "c").forEach { serve(it) }
        AddonRepository.initialize()
        listOf("a", "b", "c").forEach { name ->
            val result = AddonRepository.addAddon(rawUrl(name))
            assertIs<AddAddonResult.Success>(result)
            assertEquals("Addon $name", result.manifest.name)
        }
        AddonRepository.setAddonEnabled(manifestUrl("b"), false)
        AddonRepository.moveAddon(fromIndex = 2, toIndex = 0)
        assertEquals(listOf(manifestUrl("c"), manifestUrl("a"), manifestUrl("b")), urls())
        val fetchesOfDisabled = hitsOf("b")

        restart()
        assertTrue(addons().isEmpty(), "the restart must start from storage, not from memory")
        AddonRepository.initialize()
        awaitIdle()

        assertEquals(listOf(manifestUrl("c"), manifestUrl("a"), manifestUrl("b")), urls())
        assertEquals(listOf(true, true, false), addons().map { it.enabled })
        assertEquals(listOf("Addon c", "Addon a"), addons().filter { it.enabled }.map { it.manifest?.name })
        assertEquals(fetchesOfDisabled, hitsOf("b"), "a disabled add-on must not be contacted at start")
        // The stored address is the exact manifest address, token included.
        assertEquals(urls(), AddonStorage.loadInstalledAddonUrls(profile))
    }

    @Test
    fun installsAndRemovalsStayInTheCurrentProfile(): Unit = runBlocking {
        val other = profile + 1
        val othersAddon = "https://other.example/$SECRET/manifest.json"
        AddonStorage.saveInstalledAddonUrls(other, listOf(othersAddon))
        AddonStorage.saveAddonEnabledStates(other, mapOf(othersAddon to false))
        serve("a")
        AddonRepository.initialize()

        assertIs<AddAddonResult.Success>(AddonRepository.addAddon(rawUrl("a")))
        restart()
        assertEquals(listOf(manifestUrl("a")), AddonStorage.loadInstalledAddonUrls(profile))
        assertEquals(listOf(othersAddon), AddonStorage.loadInstalledAddonUrls(other))
        assertEquals(mapOf(othersAddon to false), AddonStorage.loadAddonEnabledStates(other))

        AddonRepository.initialize()
        awaitIdle()
        assertEquals(listOf(manifestUrl("a")), urls(), "another profile's add-on must not appear")
        AddonRepository.removeAddon(manifestUrl("a"))
        restart()
        assertEquals(emptyList<String>(), AddonStorage.loadInstalledAddonUrls(profile))
        assertEquals(listOf(othersAddon), AddonStorage.loadInstalledAddonUrls(other))
    }

    // ---- D: removing one add-on removes only it ------------------------------------------------

    @Test
    fun removingAnAddonRemovesOnlyItAndSurvivesARestart(): Unit = runBlocking {
        seedInstalled("a", "b", "c")
        AddonRepository.setAddonEnabled(manifestUrl("c"), false)
        val manifestsBefore = addons().associate { it.manifestUrl to it.manifest }
        val fetchesBefore = listOf("a", "b", "c").associateWith(::hitsOf)

        AddonRepository.removeAddon(manifestUrl("b"))

        assertEquals(listOf(manifestUrl("a"), manifestUrl("c")), urls())
        assertEquals(listOf(true, false), addons().map { it.enabled })
        addons().forEach { assertEquals(manifestsBefore[it.manifestUrl], it.manifest) }
        assertEquals(fetchesBefore, listOf("a", "b", "c").associateWith(::hitsOf), "removal must not refetch")
        assertEquals(listOf(manifestUrl("a"), manifestUrl("c")), AddonStorage.loadInstalledAddonUrls(profile))
        assertEquals(
            mapOf(manifestUrl("a") to true, manifestUrl("c") to false),
            AddonStorage.loadAddonEnabledStates(profile),
        )

        // Removing something that is not installed changes nothing.
        AddonRepository.removeAddon(manifestUrl("b"))
        AddonRepository.removeAddon("https://never.example/manifest.json")
        assertEquals(listOf(manifestUrl("a"), manifestUrl("c")), urls())

        restart()
        AddonRepository.initialize()
        awaitIdle()
        assertEquals(listOf(manifestUrl("a"), manifestUrl("c")), urls())
        assertEquals(listOf(true, false), addons().map { it.enabled })
    }

    // ---- F: malformed manifests ----------------------------------------------------------------

    @Test
    fun malformedManifestsAreRejectedWithoutTouchingInstalledAddons(): Unit = runBlocking {
        seedInstalled("good")
        val installed = addons()
        val stored = AddonStorage.loadInstalledAddonUrls(profile)
        val bad = linkedMapOf(
            "not-json" to "this is not json",
            "html" to "<!doctype html><html><body>Login required</body></html>",
            "root-array" to "[]",
            "root-string" to "\"manifest\"",
            "truncated" to manifestJson("x").dropLast(20),
            "missing-version" to """{"id":"x","name":"X","resources":[]}""",
            "missing-id" to """{"name":"X","version":"1","resources":[]}""",
            "blank-name" to """{"id":"x","name":"   ","version":"1"}""",
            "id-object" to """{"id":{"value":"x"},"name":"X","version":"1"}""",
            "name-array" to """{"id":"x","name":["X"],"version":"1"}""",
            "catalog-number" to """{"id":"x","name":"X","version":"1","catalogs":[42]}""",
            "catalog-no-id" to """{"id":"x","name":"X","version":"1","catalogs":[{"type":"movie"}]}""",
            "resource-no-name" to """{"id":"x","name":"X","version":"1","resources":[{"types":["movie"]}]}""",
            "hints-string" to """{"id":"x","name":"X","version":"1","behaviorHints":"yes"}""",
            // About 6 MB that never closes its string: read in full, then refused.
            "huge" to "{\"id\":\"x\",\"name\":\"" + "a".repeat(6 * 1024 * 1024),
        )
        bad.forEach { (name, body) -> serve(name, body) }
        routes[pathOf("http-500")] = { MockResponse().setResponseCode(500).setBody(manifestJson("http-500")) }
        routes[pathOf("http-404")] = { MockResponse().setResponseCode(404).setBody("Not found") }
        routes[pathOf("empty")] = { MockResponse().setBody("") }

        (bad.keys + listOf("http-500", "http-404", "empty")).forEach { name ->
            val result = AddonRepository.addAddon(rawUrl(name))
            assertIs<AddAddonResult.Error>(result, "\"$name\" must be refused")
            assertNoSecret(result.message)
            assertEquals(1, hitsOf(name), "\"$name\" must have been fetched once")
            assertEquals(installed, addons(), "\"$name\" changed the installed add-ons")
            assertEquals(stored, AddonStorage.loadInstalledAddonUrls(profile), "\"$name\" changed storage")
        }

        restart()
        AddonRepository.initialize()
        awaitIdle()
        assertEquals(listOf(manifestUrl("good")), urls())
        assertEquals("Addon good", addons().single().manifest?.name)
    }

    @Test
    fun aManifestThatBreaksLaterKeepsTheAddonInstalledAndRecovers(): Unit = runBlocking {
        seedInstalled("a", "b")
        val lastGood = addons().first().manifest

        // While running: a refresh gets garbage; the last good manifest stays in use.
        serve("a", "{\"id\":")
        AddonRepository.refreshAll()
        awaitIdle()
        val broken = addons().first()
        assertEquals(lastGood, broken.manifest, "a failed refresh must keep the last good manifest")
        assertNoSecret(assertNotNull(broken.errorMessage))
        assertNotNull(addons()[1].manifest)
        assertNull(addons()[1].errorMessage, "the other add-on must not be affected")

        // After a restart nothing is cached: the add-on stays installed with an error, the other loads.
        restart()
        AddonRepository.initialize()
        awaitIdle()
        assertEquals(listOf(manifestUrl("a"), manifestUrl("b")), urls())
        assertNull(addons()[0].manifest)
        assertNoSecret(assertNotNull(addons()[0].errorMessage))
        assertEquals("Addon b", addons()[1].manifest?.name)
        assertEquals(urls(), AddonStorage.loadInstalledAddonUrls(profile))

        // The server is fixed: a refresh restores it without reinstalling.
        serve("a")
        AddonRepository.refreshAddon(manifestUrl("a"), forceRefresh = true)
        awaitIdle()
        assertEquals("Addon a", addons()[0].manifest?.name)
        assertNull(addons()[0].errorMessage)
    }

    // ---- F + R: connection failure, timeout, retry ---------------------------------------------

    @Test
    fun aDroppedConnectionDuringInstallChangesNothingAndARetrySucceeds(): Unit = runBlocking {
        seedInstalled("good")
        val installed = addons()
        routes[pathOf("flaky")] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }

        val failed = AddonRepository.addAddon(rawUrl("flaky"))

        assertIs<AddAddonResult.Error>(failed)
        assertNoSecret(failed.message)
        assertTrue(hitsOf("flaky") >= 1)
        assertEquals(installed, addons())
        assertEquals(listOf(manifestUrl("good")), AddonStorage.loadInstalledAddonUrls(profile))

        serve("flaky")
        val retried = AddonRepository.addAddon(rawUrl("flaky"))
        assertIs<AddAddonResult.Success>(retried)
        assertEquals(listOf(manifestUrl("good"), manifestUrl("flaky")), urls())
        restart()
        assertEquals(listOf(manifestUrl("good"), manifestUrl("flaky")), AddonStorage.loadInstalledAddonUrls(profile))
    }

    /** Waits for the app's real 60 s read timeout: the server accepts the request and never answers. */
    @Test
    fun aServerThatNeverAnswersTimesOutChangesNothingAndARetrySucceeds(): Unit = runBlocking {
        seedInstalled("good")
        val installed = addons()
        routes[pathOf("silent")] = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }

        val startedAt = System.nanoTime()
        val timedOut = withTimeout(150_000) { AddonRepository.addAddon(rawUrl("silent")) }
        val waitedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)

        assertIs<AddAddonResult.Error>(timedOut)
        assertNoSecret(timedOut.message)
        assertTrue(waitedSeconds in 50..140, "install gave up after $waitedSeconds s, expected the 60 s timeout")
        assertEquals(installed, addons())
        assertEquals(listOf(manifestUrl("good")), AddonStorage.loadInstalledAddonUrls(profile))

        serve("silent")
        assertIs<AddAddonResult.Success>(AddonRepository.addAddon(rawUrl("silent")))
        assertEquals(listOf(manifestUrl("good"), manifestUrl("silent")), urls())
        restart()
        assertEquals(listOf(manifestUrl("good"), manifestUrl("silent")), AddonStorage.loadInstalledAddonUrls(profile))
    }

    // ---- I: cancel / interrupt -----------------------------------------------------------------

    @Test
    fun cancellingAnInstallInFlightInstallsNothingEvenIfTheServerAnswersLater(): Unit = runBlocking {
        seedInstalled("good")
        val installed = addons()
        routes[pathOf("slow")] = {
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody(manifestJson("slow")).setBodyDelay(2, TimeUnit.SECONDS)
        }

        val install = launch { AddonRepository.addAddon(rawUrl("slow")) }
        withTimeout(5_000) { while (hitsOf("slow") == 0) delay(20) }
        install.cancel()
        withTimeout(30_000) { install.join() }
        // Give a late answer every chance to be applied, then check nothing was.
        delay(500)

        assertTrue(install.isCancelled)
        assertEquals(installed, addons())
        assertEquals(listOf(manifestUrl("good")), AddonStorage.loadInstalledAddonUrls(profile))

        // The user tries again and it works.
        assertIs<AddAddonResult.Success>(AddonRepository.addAddon(rawUrl("slow")))
        assertEquals(listOf(manifestUrl("good"), manifestUrl("slow")), urls())
    }

    @Test
    fun aDismissedAddonLinkNeverContactsTheAddonOrInstallsIt(): Unit = runBlocking {
        seedInstalled("good")
        val installed = addons()
        serve("linked")
        val confirmation = AddonLinkConfirmation()

        confirmation.request(manifestUrl("linked"))
        assertEquals(0, hitsOf("linked"), "a link alone must not contact the add-on")
        confirmation.dismiss()
        assertNull(confirmation.confirm { AddonRepository.addAddon(it) })

        assertEquals(0, hitsOf("linked"), "a dismissed link must not contact the add-on")
        assertEquals(installed, addons())
        assertEquals(listOf(manifestUrl("good")), AddonStorage.loadInstalledAddonUrls(profile))

        // Confirming a new link installs it once, and it is kept after a restart.
        confirmation.request(manifestUrl("linked"))
        assertIs<AddAddonResult.Success>(confirmation.confirm { AddonRepository.addAddon(it) })
        assertNull(confirmation.confirm { AddonRepository.addAddon(it) })
        assertEquals(1, hitsOf("linked"))
        restart()
        assertEquals(listOf(manifestUrl("good"), manifestUrl("linked")), AddonStorage.loadInstalledAddonUrls(profile))
    }

    private companion object {
        const val SECRET = "tok-3f9c2a7e51d8"
    }
}
