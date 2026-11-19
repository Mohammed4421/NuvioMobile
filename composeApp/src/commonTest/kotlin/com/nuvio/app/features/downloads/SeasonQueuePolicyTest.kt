package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The season queue's decisions (F1): pausing an episode keeps its place and holds the queue; the
 * next episode starts only when it finishes, fails, is deleted or is removed from the queue.
 */
class SeasonQueuePolicyTest {
    private val reference = SeasonQueueReference(addonId = "addon", addonName = "Addon", text = "Show 1080p")

    private fun entry(
        episode: Int,
        state: SeasonQueueEntryState = SeasonQueueEntryState.Waiting,
        downloadId: String? = null,
        profile: Int? = 1,
    ) =
        SeasonQueueEntry(
            id = if (profile == 1) "e$episode" else "p${profile}e$episode",
            contentType = "series",
            parentMetaId = "tt1",
            parentMetaType = "series",
            title = "Show",
            videoId = "tt1:1:$episode",
            seasonNumber = 1,
            episodeNumber = episode,
            reference = reference,
            state = state,
            downloadId = downloadId,
            profileId = profile,
        )

    private val downloading = SeasonQueueEntryState.Downloading
    private val paused = SeasonQueueEntryState.Paused

    private fun settle(
        entries: List<SeasonQueueEntry>,
        vararg statuses: Pair<String, DownloadStatus>,
        searching: Set<String> = emptySet(),
        profile: Int = 1,
    ) = SeasonQueuePolicy.settle(entries, profile, statuses.toMap()::get, searching)

    /** What the queue would start next after settling, or null when it waits. */
    private fun nextAfter(
        entries: List<SeasonQueueEntry>,
        vararg statuses: Pair<String, DownloadStatus>,
        searching: Set<String> = emptySet(),
        profile: Int = 1,
    ): String? {
        val settled = settle(entries, *statuses, searching = searching, profile = profile)
        return if (SeasonQueuePolicy.busy(settled, profile, searching)) null else SeasonQueuePolicy.next(settled, profile)?.id
    }

    private val season = listOf(entry(1, downloading, "d1"), entry(2), entry(3))

    @Test
    fun pausingTheFirstEpisodeDoesNotStartTheSecond() {
        val settled = settle(season, "d1" to DownloadStatus.Paused)
        assertEquals(listOf(paused, SeasonQueueEntryState.Waiting, SeasonQueueEntryState.Waiting), settled.map { it.state })
        assertEquals("d1", settled.first().downloadId)
        assertTrue(SeasonQueuePolicy.busy(settled, 1, emptySet()))
        assertNull(nextAfter(season, "d1" to DownloadStatus.Paused))
    }

    @Test
    fun resumingContinuesTheSameEpisodeThenTheNext() {
        val pausedQueue = settle(season, "d1" to DownloadStatus.Paused)
        val resumed = settle(pausedQueue, "d1" to DownloadStatus.Downloading)
        assertEquals(downloading, resumed.first().state)
        assertNull(nextAfter(pausedQueue, "d1" to DownloadStatus.Downloading))
        assertEquals("e2", nextAfter(resumed, "d1" to DownloadStatus.Completed))
    }

    @Test
    fun pauseAllHoldsTheQueueAndResumeAllKeepsTheOrder() {
        // "Pause all" pauses every running download, the queue's included.
        val held = settle(season, "d1" to DownloadStatus.Paused, "other" to DownloadStatus.Paused)
        assertNull(nextAfter(held, "d1" to DownloadStatus.Paused))
        // "Resume all": the paused episode runs again; episode 2 starts only after it.
        assertNull(nextAfter(held, "d1" to DownloadStatus.Downloading))
        val afterFirst = settle(held, "d1" to DownloadStatus.Completed)
        assertEquals("e2", SeasonQueuePolicy.next(afterFirst, 1)?.id)
        val secondRunning = afterFirst.map { if (it.id == "e2") it.copy(state = downloading, downloadId = "d2") else it }
        assertNull(nextAfter(secondRunning, "d2" to DownloadStatus.Downloading))
        assertEquals("e3", nextAfter(secondRunning, "d2" to DownloadStatus.Completed))
    }

    @Test
    fun aPausedEpisodeLeavesTheQueueWhenItsDownloadIsDeletedOrFails() {
        assertEquals(listOf("e2", "e3"), settle(settle(season, "d1" to DownloadStatus.Paused)).map { it.id })
        assertEquals("e2", nextAfter(season, "d1" to DownloadStatus.Failed))
        assertEquals("e2", nextAfter(season))
    }

    @Test
    fun removingAPausedEpisodeFromTheQueueSkipsIt() {
        val held = settle(season, "d1" to DownloadStatus.Paused)
        val skipped = held.filterNot { it.id == "e1" }
        assertEquals("e2", nextAfter(skipped, "d1" to DownloadStatus.Paused))
    }

    @Test
    fun aPausedQueueSurvivesARestart() {
        // Stored as Paused; after a restart the download is still paused, so the queue still waits.
        val stored = listOf(entry(1, paused, "d1"), entry(2))
        assertEquals(paused, settle(stored, "d1" to DownloadStatus.Paused).first().state)
        assertNull(nextAfter(stored, "d1" to DownloadStatus.Paused))
    }

    @Test
    fun aSearchHoldsTheQueueAndAnInterruptedOneStartsOver() {
        val searching = listOf(entry(1, SeasonQueueEntryState.Searching), entry(2))
        assertEquals(SeasonQueueEntryState.Searching, settle(searching, searching = setOf("e1")).first().state)
        assertNull(nextAfter(searching, searching = setOf("e1")))
        assertEquals(SeasonQueueEntryState.Waiting, settle(searching).first().state)
        assertEquals("e1", nextAfter(searching))
    }

    @Test
    fun anEpisodeWithoutASourceDoesNotHoldTheQueue() {
        val queue = listOf(entry(1, SeasonQueueEntryState.NeedsChoice), entry(2))
        assertEquals("e2", nextAfter(queue))
        assertFalse(SeasonQueuePolicy.busy(queue, 1, emptySet()))
    }

    @Test
    fun aDownloadingEntryWithoutADownloadLeaves() {
        assertEquals(listOf("e2"), settle(listOf(entry(1, downloading, null), entry(2))).map { it.id })
    }

    // F2: every profile has its own queue; downloads are loaded for the active profile only.

    private val profileA = listOf(entry(1, downloading, "a1", profile = 1), entry(2, profile = 1))
    private val profileB = listOf(entry(1, profile = 2), entry(2, profile = 2))

    @Test
    fun switchingProfilesKeepsTheOtherQueueUntouched() {
        val both = profileA + profileB
        // Profile B is active: A's running episode is not in B's downloads, but it stays as it is.
        val inB = settle(both, profile = 2)
        assertEquals(both, inB)
        // B's own queue moves on independently; A's episodes never start in B.
        assertEquals("p2e1", nextAfter(both, profile = 2))
        assertEquals(listOf("p2e1", "p2e2"), SeasonQueuePolicy.visible(inB, 2).map { it.id })
    }

    @Test
    fun aToBToAContinuesProfileAWithoutLossOrDuplicates() {
        val both = profileA + profileB
        val inB = settle(both, profile = 2)
        // Back to A while its download still runs: nothing changes, nothing new starts.
        val backInA = settle(inB, "a1" to DownloadStatus.Downloading, profile = 1)
        assertEquals(both, backInA)
        assertNull(nextAfter(inB, "a1" to DownloadStatus.Downloading, profile = 1))
        // A's episode finished while B was active: A continues with its own next episode.
        assertEquals("e2", nextAfter(inB, "a1" to DownloadStatus.Completed, profile = 1))
        assertEquals(listOf("e2", "p2e1", "p2e2"), settle(inB, "a1" to DownloadStatus.Completed, profile = 1).map { it.id })
    }

    @Test
    fun anotherProfilesSearchOrDownloadDoesNotHoldThisQueue() {
        val both = profileA + profileB
        assertEquals("p2e1", nextAfter(both, searching = setOf("e2"), profile = 2))
        assertNull(nextAfter(both, searching = setOf("p2e1"), profile = 2))
    }

    @Test
    fun queuesSavedBeforeProfilesAreKeptForTheActiveProfile() {
        val old = listOf(entry(1, paused, "d1", profile = null), entry(2, profile = null), entry(3, profile = 2))
        val migrated = SeasonQueuePolicy.migrate(old, activeProfileId = 1)
        assertEquals(listOf(1, 1, 2), migrated.map { it.profileId })
        assertEquals(3, migrated.size)
        assertEquals(old.map { it.copy(profileId = null) }, migrated.map { it.copy(profileId = null) })
    }
}
