package com.nuvio.app.features.downloads

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Data saved by the official app or older Nuvio Plus builds must keep loading after an update. */
class PlusDownloadsMigrationTest {

    private val officialItem = """
        {"items":[{"id":"d1","contentType":"movie","parentMetaId":"tt1","parentMetaType":"movie",
        "videoId":"tt1","title":"Movie","streamTitle":"1080p","providerName":"Addon",
        "sourceUrl":"https://example.com/a.mkv","localFileUri":"file:///data/a.mkv","fileName":"a.mkv",
        "status":"Completed","downloadedBytes":10,"totalBytes":10,"createdAtEpochMs":1,"updatedAtEpochMs":2}]}
    """.trimIndent()

    @Test
    fun downloadsSavedWithoutPlusFieldsStillLoad() {
        val item = DownloadsCodec.decodeItems(officialItem).single()
        assertEquals("d1", item.id)
        assertEquals(DownloadStatus.Completed, item.status)
        assertNull(item.targetFolderUri)
        assertFalse(item.wifiOnly)
    }

    @Test
    fun plusFieldsSurviveSavingAndLoading() {
        val item = DownloadsCodec.decodeItems(officialItem).single()
            .copy(targetFolderUri = "content://tree/primary%3AMovies", wifiOnly = true)
        val restored = DownloadsCodec.decodeItems(DownloadsCodec.encodeItems(listOf(item))).single()
        assertEquals(item, restored)
    }

    @Test
    fun unknownFieldsFromNewerBuildsAreIgnored() {
        val withExtra = officialItem.replace("\"updatedAtEpochMs\":2", "\"updatedAtEpochMs\":2,\"futureField\":true")
        assertEquals(1, DownloadsCodec.decodeItems(withExtra).size)
    }

    @Test
    fun brokenPayloadLoadsAsEmptyInsteadOfCrashing() {
        assertEquals(emptyList(), DownloadsCodec.decodeItems("{not json"))
    }

    @Test
    fun savedSeasonQueueEntryStillLoads() {
        val json = Json { ignoreUnknownKeys = true }
        val entry = json.decodeFromString<SeasonQueueEntry>(
            """{"id":"q1","contentType":"series","parentMetaId":"tt2","parentMetaType":"series","title":"Show",
            "videoId":"tt2:1:2","seasonNumber":1,"episodeNumber":2,
            "reference":{"addonId":"a","addonName":"A","text":"show 1080p"}}""",
        )
        assertEquals(SeasonQueueEntryState.Waiting, entry.state)
        assertNull(entry.downloadId)
    }

    @Test
    fun downloadPreferencesDefaultToAppStorage() {
        val json = Json { ignoreUnknownKeys = true }
        val state = json.decodeFromString<DownloadPreferencesState>("{}")
        assertNull(state.folderUri)
        assertFalse(state.wifiOnly)
    }
}
