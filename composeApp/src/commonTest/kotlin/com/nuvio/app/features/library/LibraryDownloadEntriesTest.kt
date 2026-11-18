package com.nuvio.app.features.library

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.SeasonQueueEntry
import com.nuvio.app.features.downloads.SeasonQueueReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryDownloadEntriesTest {

    @Test
    fun episodesOfOneShowBecomeOnePoster() {
        val entries = buildLibraryDownloadEntries(
            items = listOf(
                episode("show", 1, DownloadStatus.Completed, updated = 10),
                episode("show", 2, DownloadStatus.Completed, updated = 20),
                movie("movie", DownloadStatus.Completed, updated = 30),
            ),
            queue = emptyList(),
        )
        assertEquals(listOf("movie", "show"), entries.map { it.metaId })
        val show = entries.single { it.metaId == "show" }
        assertTrue(show.isSeries)
        assertEquals(2, show.completed.size)
        assertFalse(entries.single { it.metaId == "movie" }.isSeries)
    }

    @Test
    fun runningDownloadsComeFirstWithTheirProgress() {
        val entries = buildLibraryDownloadEntries(
            items = listOf(
                movie("done", DownloadStatus.Completed, updated = 50),
                movie("running", DownloadStatus.Downloading, updated = 10, downloaded = 25, total = 100),
            ),
            queue = emptyList(),
        )
        assertEquals("running", entries.first().metaId)
        assertEquals(DownloadStatus.Downloading, entries.first().activeState)
        assertEquals(0.25f, entries.first().activeProgress)
    }

    @Test
    fun unknownSizeGivesNoProgress() {
        val entry = buildLibraryDownloadEntries(
            items = listOf(movie("m", DownloadStatus.Downloading, updated = 1, downloaded = 5, total = null)),
            queue = emptyList(),
        ).single()
        assertNull(entry.activeProgress)
    }

    @Test
    fun queuedShowWithoutDownloadsStillAppears() {
        val entry = buildLibraryDownloadEntries(
            items = emptyList(),
            queue = listOf(queued("show", 3)),
        ).single()
        assertTrue(entry.isSeries)
        assertEquals(1, entry.queuedCount)
        assertEquals("Show", entry.title)
    }

    private fun movie(id: String, status: DownloadStatus, updated: Long, downloaded: Long = 0, total: Long? = 1) =
        item(id, null, status, updated, downloaded, total)

    private fun episode(id: String, number: Int, status: DownloadStatus, updated: Long) =
        item(id, number, status, updated, 1, 1)

    private fun item(
        metaId: String,
        episode: Int?,
        status: DownloadStatus,
        updated: Long,
        downloaded: Long,
        total: Long?,
    ) = DownloadItem(
        id = "$metaId-${episode ?: 0}",
        contentType = if (episode == null) "movie" else "series",
        parentMetaId = metaId,
        parentMetaType = if (episode == null) "movie" else "series",
        videoId = "$metaId:${episode ?: 0}",
        title = metaId,
        seasonNumber = episode?.let { 1 },
        episodeNumber = episode,
        streamTitle = "stream",
        providerName = "addon",
        sourceUrl = "https://example.com/$metaId",
        localFileUri = if (status == DownloadStatus.Completed) "file:///tmp/$metaId" else null,
        fileName = "$metaId.mkv",
        status = status,
        downloadedBytes = downloaded,
        totalBytes = total,
        createdAtEpochMs = updated,
        updatedAtEpochMs = updated,
    )

    private fun queued(metaId: String, episode: Int) = SeasonQueueEntry(
        id = "q$episode",
        contentType = "series",
        parentMetaId = metaId,
        parentMetaType = "series",
        title = "Show",
        videoId = "$metaId:1:$episode",
        seasonNumber = 1,
        episodeNumber = episode,
        reference = SeasonQueueReference(addonId = "a", addonName = "A", text = "x"),
    )
}
