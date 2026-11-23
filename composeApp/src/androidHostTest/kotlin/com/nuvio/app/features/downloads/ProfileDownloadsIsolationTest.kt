package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.nuvio.app.features.library.buildLibraryDownloadEntries
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.streams.StreamItem
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One cached profile, in the form Nuvio stores the profile list on the device. */
private fun hostProfile(index: Int): String = """{"profile_index":$index,"name":"Profile $index"}"""

/**
 * Makes [active] the active profile from the profile list cached on the device, as a cold start does
 * (ProfileRepository.ensureLoaded): this sets ProfileRepository.activeProfileId, which every
 * per-profile store reads.
 */
private fun useHostProfiles(active: Int, profiles: List<String> = listOf(hostProfile(1), hostProfile(2))) {
    ProfileStorage.savePayload("""{"userId":"host-test-user","activeProfileIndex":$active,"profiles":[${profiles.joinToString(",")}]}""")
    ProfileRepository.clearInMemory()
    ProfileRepository.ensureLoaded("host-test-user")
    check(ProfileRepository.activeProfileId == active) { "profile $active is not active: ${ProfileRepository.activeProfileId}" }
}

/**
 * P3-03: Plus downloads with two profiles, on the real DownloadsRepository, the real Android
 * DownloadsStorage (SharedPreferences) and the real Android transfer store. The download list is
 * stored per profile (`downloads_payload_<profile>`); the transfers and the download preferences
 * are device-wide. A profile switch is applied as ProfileRepository.selectProfile applies it to the
 * downloads (the active profile id, then DownloadsRepository.onProfileChanged).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileDownloadsIsolationTest {
    private lateinit var downloads: SharedPreferences
    private lateinit var scheduler: AndroidDownloadScheduler
    /** File names and ids unique to this test: the transfer store is shared by the whole test run. */
    private val tag = UUID.randomUUID().toString().take(8)

    @BeforeTest
    fun setUp() {
        DownloadsRepository.clearLocalState()
        val context = RuntimeEnvironment.getApplication()
        downloads = context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE)
        downloads.edit().clear().commit()
        context.getSharedPreferences("nuvio_profile_cache", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsStorage.initialize(context)
        ProfileStorage.initialize(context)
        scheduler = DownloadsPlatformDownloader.scheduler(context)
    }

    @AfterTest
    fun tearDown() {
        DownloadsRepository.clearLocalState()
        DownloadPreferences.setFolder(null, null)
        DownloadPreferences.setWifiOnly(false)
        ProfileRepository.clearInMemory()
    }

    private fun switchTo(profile: Int, profiles: List<Int> = listOf(1, 2)) {
        useHostProfiles(profile, profiles.map { hostProfile(it) })
        DownloadsRepository.onProfileChanged()
        idleMain()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun item(name: String, status: DownloadStatus) = downloadItem(id = "$tag-$name").copy(
        parentMetaId = "tt-$tag-$name",
        videoId = "tt-$tag-$name",
        title = "Title $name",
        fileName = "$tag-$name.mkv",
        status = status,
    )

    private fun seed(profile: Int, items: List<DownloadItem>) {
        downloads.edit().putString("downloads_payload_$profile", DownloadsCodec.encodeItems(items)).commit()
    }

    private fun raw(profile: Int): String? = downloads.getString("downloads_payload_$profile", null)

    private fun stored(profile: Int): List<DownloadItem> = raw(profile)?.let(DownloadsCodec::decodeItems).orEmpty()

    private fun shown(): List<DownloadItem> = DownloadsRepository.uiState.value.items

    /** A running transfer with its system job, as one started earlier in this profile. */
    private fun running(item: DownloadItem): AndroidDownloadTransfer =
        scheduler.enqueue(item).also { assertEquals(DownloadStatus.Downloading, it.item.status, "the transfer did not start") }

    /** The download job finishes the transfer (what the job service writes when the file is complete). */
    private fun finish(item: DownloadItem, generation: String) {
        scheduler.directory.mkdirs()
        val file = File(scheduler.directory, item.fileName).apply { writeText("video") }
        assertNotNull(
            scheduler.store.update(item.fileName, generation) {
                it.copy(item = it.item.copy(status = DownloadStatus.Completed, localFileUri = file.toURI().toString(), downloadedBytes = 5L, totalBytes = 5L))
            },
            "the transfer was replaced by another one",
        )
        idleMain()
    }

    // N + I: switching during an active download.

    @Test
    fun switchingDuringAnActiveDownloadKeepsEachProfilesItemsApart() {
        val a = item("a", DownloadStatus.Downloading)
        val b = item("b", DownloadStatus.Completed)
        val transfer = running(a)
        seed(1, listOf(a))
        seed(2, listOf(b))

        switchTo(1)
        assertEquals(listOf(a.id), shown().map { it.id })
        assertEquals(DownloadStatus.Downloading, shown().single().status)

        switchTo(2)
        assertEquals(listOf(b.id), shown().map { it.id })
        // The library's downloads tab is built from these items: profile 2 sees only its own title.
        assertEquals(listOf(b.parentMetaId), buildLibraryDownloadEntries(shown(), emptyList()).map { it.metaId })
        // The switch does not stop profile 1's transfer.
        assertEquals(DownloadStatus.Downloading, scheduler.store.get(a.fileName)?.item?.status)

        // Profile 1's download finishes while profile 2 is active: nothing of it reaches profile 2.
        finish(a, transfer.generation)
        assertEquals(listOf(b.id), shown().map { it.id })
        assertEquals(listOf(b), stored(2))
        assertEquals(listOf(a.id), stored(1).map { it.id })

        // Back in profile 1 the finished download is there, and only there.
        switchTo(1)
        assertEquals(listOf(a.id), shown().map { it.id })
        assertEquals(DownloadStatus.Completed, shown().single().status)
        assertEquals(DownloadStatus.Completed, stored(1).single().status)
        assertEquals(listOf(a.parentMetaId), buildLibraryDownloadEntries(shown(), emptyList()).map { it.metaId })
        assertEquals(listOf(b), stored(2))
    }

    @Test
    fun comingBackWhileItStillRunsContinuesTheSameTransferInItsOwnProfile() {
        val a = item("a", DownloadStatus.Downloading)
        val transfer = running(a)
        seed(1, listOf(a))

        switchTo(1)
        switchTo(2)
        assertTrue(shown().isEmpty())
        switchTo(1)

        // Not started again: the same transfer (and its partial file) goes on.
        assertEquals(transfer.generation, scheduler.store.get(a.fileName)?.generation)
        assertEquals(listOf(a.id), shown().map { it.id })
        finish(a, transfer.generation)
        assertEquals(DownloadStatus.Completed, shown().single().status)
        assertEquals(DownloadStatus.Completed, stored(1).single().status)
        assertNull(raw(2))
    }

    @Test
    fun pausingOrCancellingInOneProfileLeavesTheOtherProfilesDownloadRunning() {
        val a = item("a", DownloadStatus.Downloading)
        val b = item("b", DownloadStatus.Downloading)
        val c = item("c", DownloadStatus.Paused)
        val transferA = running(a)
        running(b)
        seed(1, listOf(a))
        seed(2, listOf(b, c))

        switchTo(1)
        switchTo(2)
        DownloadsRepository.pauseActiveDownloads()
        DownloadsRepository.cancelDownload(c.id)
        idleMain()

        assertEquals(DownloadStatus.Paused, scheduler.store.get(b.fileName)?.item?.status)
        assertEquals(listOf(b.id), stored(2).map { it.id })
        assertEquals(DownloadStatus.Paused, stored(2).single().status)
        // Profile 1's download was neither paused nor removed, and its job is still scheduled.
        assertEquals(DownloadStatus.Downloading, scheduler.store.get(a.fileName)?.item?.status)
        assertTrue(transferA.jobId in scheduler.jobScheduler().allPendingJobs.map { it.id })
        assertEquals(listOf(a.id to DownloadStatus.Downloading), stored(1).map { it.id to it.status })
    }

    // F: damaged or missing storage of one profile.

    @Test
    fun aDamagedListInOneProfileNeitherWipesNorLeaksTheOther() {
        val a = item("a", DownloadStatus.Completed)
        seed(1, listOf(a))
        downloads.edit().putString("downloads_payload_2", "{damaged").commit()

        switchTo(1)
        switchTo(2)
        assertTrue(shown().isEmpty())
        // Loading what it cannot read does not overwrite it.
        assertEquals("{damaged", raw(2))
        assertEquals(listOf(a), stored(1))

        switchTo(1)
        assertEquals(listOf(a), shown())
    }

    @Test
    fun aProfileWithNothingStoredStartsEmptyAndWritesNothing() {
        val a = item("a", DownloadStatus.Completed)
        seed(1, listOf(a))

        switchTo(3, profiles = listOf(1, 2, 3))
        assertTrue(shown().isEmpty())
        assertNull(raw(3))
        assertEquals(listOf(a), stored(1))
    }

    /**
     * Nuvio's behaviour (DownloadsRepository.persist writes the list in memory): the first new
     * download in a profile whose list could not be read replaces that unreadable list. The loss
     * stays inside that profile.
     */
    @Test
    fun aNewDownloadInADamagedProfileReplacesOnlyThatProfilesUnreadableList() {
        val a = item("a", DownloadStatus.Completed)
        seed(1, listOf(a))
        downloads.edit().putString("downloads_payload_2", "{damaged").commit()
        switchTo(2)

        val result = DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-$tag-new",
            parentMetaId = "tt-$tag-new",
            parentMetaType = "movie",
            title = "New",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(name = "1080p", url = "https://example.com/$tag-new.mkv", addonName = "Addon", addonId = "addon"),
        )
        idleMain()

        assertEquals(DownloadEnqueueResult.Started, result)
        assertNotEquals("{damaged", raw(2))
        assertEquals(listOf("tt-$tag-new"), stored(2).map { it.parentMetaId })
        assertEquals(listOf(a), stored(1))
    }

    // R + P: restart.

    @Test
    fun aRestartBringsBackEachProfilesOwnList() {
        val a = item("a", DownloadStatus.Completed)
        val b = item("b", DownloadStatus.Paused)
        val c = item("c", DownloadStatus.Failed)
        seed(1, listOf(a))
        seed(2, listOf(b, c))
        switchTo(2)

        // Process restart: nothing in memory, a cold start on the remembered profile 2.
        DownloadsRepository.clearLocalState()
        switchTo(2)
        assertEquals(listOf(b, c), shown())
        switchTo(1)
        assertEquals(listOf(a), shown())
        assertEquals(listOf(b, c), stored(2))
    }

    // D: the download preferences are device-wide by design ("Device-wide download settings").

    @Test
    fun downloadPreferencesAreSharedByEveryProfileAndKeptAcrossSwitches() {
        switchTo(1)
        DownloadPreferences.setFolder("content://tree/primary%3ANuvio", "Nuvio")
        DownloadPreferences.setWifiOnly(true)
        val expected = DownloadPreferencesState("content://tree/primary%3ANuvio", "Nuvio", wifiOnly = true)

        switchTo(2)
        assertEquals(expected, DownloadPreferences.current())
        val stored = assertNotNull(downloads.getString("extra_download_preferences", null))
        assertEquals(expected, Json { ignoreUnknownKeys = true }.decodeFromString(DownloadPreferencesState.serializer(), stored))
        assertNull(downloads.getString("extra_download_preferences_1", null))
        assertNull(downloads.getString("extra_download_preferences_2", null))
    }
}
