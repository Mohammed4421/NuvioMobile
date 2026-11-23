package com.nuvio.app.features.profiles

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.nuvio.app.features.downloads.AndroidDownloadScheduler
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsCodec
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.downloads.downloadItem
import com.nuvio.app.features.player.PlayerSettingsStorage
import java.io.File
import java.util.UUID
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One cached profile, in the form Nuvio stores the profile list on the device. */
private fun hostProfile(index: Int): String = """{"profile_index":$index,"name":"Profile $index"}"""

/** Makes [active] the active profile from the profile list cached on the device, as a cold start does. */
private fun useHostProfiles(active: Int, profiles: List<Int> = listOf(1, 2)) {
    ProfileStorage.savePayload(
        """{"userId":"host-test-user","activeProfileIndex":$active,"profiles":[${profiles.joinToString(",") { hostProfile(it) }}]}""",
    )
    ProfileRepository.clearInMemory()
    ProfileRepository.ensureLoaded("host-test-user")
    check(ProfileRepository.activeProfileId == active) { "profile $active is not active: ${ProfileRepository.activeProfileId}" }
}

/**
 * Finding F1 (journeys-profiles.md): a new profile takes the smallest free index, so it could find a
 * deleted profile's Plus data. ProfileDeletedDataCleanup clears it when the profile is deleted, on the
 * real Android DownloadsStorage, transfer store and PlayerSettingsStorage. The season queue side is
 * tested in ProfileDeletedQueueCleanupTest (the queue is a singleton that cannot be reloaded here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileDeletedDataCleanupTest {
    private lateinit var downloads: SharedPreferences
    private lateinit var settings: SharedPreferences
    private lateinit var scheduler: AndroidDownloadScheduler
    private val tag = UUID.randomUUID().toString().take(8)

    @BeforeTest
    fun setUp() {
        DownloadsRepository.clearLocalState()
        val context = RuntimeEnvironment.getApplication()
        downloads = context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        settings = context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        context.getSharedPreferences("nuvio_profile_cache", Context.MODE_PRIVATE).edit().clear().commit()
        DownloadsStorage.initialize(context)
        PlayerSettingsStorage.initialize(context)
        ProfileStorage.initialize(context)
        scheduler = DownloadsPlatformDownloader.scheduler(context)
    }

    @AfterTest
    fun tearDown() {
        DownloadsRepository.clearLocalState()
        ProfileRepository.clearInMemory()
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

    private fun stored(profile: Int): String? = downloads.getString("downloads_payload_$profile", null)

    @Test
    fun deletingAProfileClearsItsListTransfersAndSubtitleAppearanceOnly() {
        useHostProfiles(active = 1)
        val mine = item("mine", DownloadStatus.Completed)
        val finished = item("finished", DownloadStatus.Completed)
        val unfinished = item("unfinished", DownloadStatus.Downloading)
        seed(1, listOf(mine))
        seed(2, listOf(finished, unfinished))
        val transfer = scheduler.enqueue(unfinished)
        assertEquals(DownloadStatus.Downloading, transfer.item.status, "the transfer did not start")
        val finishedFile = File(scheduler.directory, finished.fileName).apply { parentFile?.mkdirs(); writeText("done") }
        settings.edit()
            .putString("subtitle_appearance_1", """{"fontId":"serif"}""")
            .putString("subtitle_appearance_2", """{"fontId":"mono"}""")
            .commit()
        val profileOneBefore = stored(1)

        ProfileDeletedDataCleanup.onProfileDeleted(2)
        idleMain()

        assertNull(stored(2), "the deleted profile's download list is still stored")
        assertNull(scheduler.store.get(unfinished.fileName), "the deleted profile's unfinished transfer is still in the store")
        assertNull(settings.getString("subtitle_appearance_2", null), "the deleted profile's subtitle appearance is still stored")
        assertTrue(finishedFile.exists(), "a finished downloaded file was removed")
        assertEquals(profileOneBefore, stored(1), "the remaining profile's download list changed")
        assertEquals("""{"fontId":"serif"}""", settings.getString("subtitle_appearance_1", null))
    }

    @Test
    fun aNewProfileAtTheDeletedIndexStartsWithAnEmptyList() {
        useHostProfiles(active = 1)
        seed(2, listOf(item("old", DownloadStatus.Completed)))

        ProfileDeletedDataCleanup.onProfileDeleted(2)
        idleMain()
        useHostProfiles(active = 2, profiles = listOf(1, 2))
        DownloadsRepository.onProfileChanged()
        idleMain()

        assertTrue(DownloadsRepository.uiState.value.items.isEmpty(), "the new profile at index 2 sees the deleted profile's downloads")
    }

    @Test
    fun theActiveProfileIsNotCleared() {
        useHostProfiles(active = 2)
        seed(2, listOf(item("active", DownloadStatus.Completed)))
        settings.edit().putString("subtitle_appearance_2", """{"fontId":"mono"}""").commit()

        ProfileDeletedDataCleanup.onProfileDeleted(2)
        idleMain()

        assertNotNull(stored(2), "the active profile's download list was cleared")
        assertNotNull(settings.getString("subtitle_appearance_2", null), "the active profile's subtitle appearance was cleared")
    }
}
