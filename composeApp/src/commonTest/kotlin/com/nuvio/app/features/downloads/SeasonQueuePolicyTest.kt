package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The season queue's decisions. Since Build 15: pausing one episode keeps its place and partial file
 * but no longer holds the queue (the next episodes go on); a paused season holds only itself; pause
 * all holds every start of the profile; a resume while another episode downloads waits its turn.
 */
class SeasonQueuePolicyTest {
    private val reference = SeasonQueueReference(addonId = "addon", addonName = "Addon", text = "Show 1080p")

    private fun entry(
        episode: Int,
        state: SeasonQueueEntryState = SeasonQueueEntryState.Waiting,
        downloadId: String? = null,
        profile: Int? = 1,
        meta: String = "tt1",
        pausedBy: SeasonPauseScope = SeasonPauseScope.Episode,
    ) =
        SeasonQueueEntry(
            id = (if (meta == "tt1") "" else meta) + if (profile == 1) "e$episode" else "p${profile}e$episode",
            contentType = "series",
            parentMetaId = meta,
            parentMetaType = "series",
            title = "Show",
            videoId = "tt1:1:$episode",
            seasonNumber = 1,
            episodeNumber = episode,
            reference = reference,
            state = state,
            downloadId = downloadId,
            pausedBy = pausedBy,
            profileId = profile,
        )

    private val downloading = SeasonQueueEntryState.Downloading
    private val paused = SeasonQueueEntryState.Paused
    private val resuming = SeasonQueueEntryState.Resuming
    private val waiting = SeasonQueueEntryState.Waiting

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
        holds: SeasonQueueHolds = SeasonQueueHolds(),
    ): String? {
        val settled = settle(entries, *statuses, searching = searching, profile = profile)
        return if (SeasonQueuePolicy.busy(settled, profile, searching)) null else SeasonQueuePolicy.next(settled, profile, holds)?.id
    }

    private val season = listOf(entry(1, downloading, "d1"), entry(2), entry(3))

    @Test
    fun pausingTheFirstEpisodeStartsTheSecond() {
        val settled = settle(season, "d1" to DownloadStatus.Paused)
        assertEquals(listOf(paused, waiting, waiting), settled.map { it.state })
        assertEquals(SeasonPauseScope.Episode, settled.first().pausedBy)
        assertEquals("d1", settled.first().downloadId)
        assertFalse(SeasonQueuePolicy.busy(settled, 1, emptySet()))
        assertEquals("e2", nextAfter(season, "d1" to DownloadStatus.Paused))
    }

    @Test
    fun aPausedEpisodeIsNeverPickedAgainByItself() {
        val onlyPaused = listOf(entry(1, paused, "d1"), entry(2, SeasonQueueEntryState.NeedsChoice))
        assertNull(nextAfter(onlyPaused, "d1" to DownloadStatus.Paused))
    }

    private val e1PausedE2Running = listOf(entry(1, paused, "d1"), entry(2, downloading, "d2"), entry(3))

    @Test
    fun resumingE1WhileE2DownloadsWaitsForItsTurn() {
        assertTrue(SeasonQueuePolicy.holdsResume(e1PausedE2Running, 1, "d1", emptySet()))
        // E1 then waits as Resuming: E2 is not stopped, nothing else starts, and E1 comes before E3.
        val held = e1PausedE2Running.map { if (it.id == "e1") it.copy(state = resuming) else it }
        val settled = settle(held, "d1" to DownloadStatus.Paused, "d2" to DownloadStatus.Downloading)
        assertEquals(listOf(resuming, downloading, waiting), settled.map { it.state })
        assertNull(nextAfter(held, "d1" to DownloadStatus.Paused, "d2" to DownloadStatus.Downloading))
        assertEquals("e1", nextAfter(held, "d1" to DownloadStatus.Paused, "d2" to DownloadStatus.Completed))
    }

    @Test
    fun resumingWhenNothingElseRunsStartsAtOnce() {
        val alone = listOf(entry(1, paused, "d1"), entry(2))
        assertFalse(SeasonQueuePolicy.holdsResume(alone, 1, "d1", emptySet()))
        // A search holds it too: it would become a second download.
        assertTrue(SeasonQueuePolicy.holdsResume(alone, 1, "d1", setOf("e2")))
        // Not a queue episode, or another profile's: never held.
        assertFalse(SeasonQueuePolicy.holdsResume(e1PausedE2Running, 1, "other", emptySet()))
        assertFalse(SeasonQueuePolicy.holdsResume(e1PausedE2Running, 2, "d1", emptySet()))
    }

    @Test
    fun aWaitingResumeLeavesWhenItsDownloadIsDeletedAndRunsWhenResumedElsewhere() {
        val held = listOf(entry(1, resuming, "d1"), entry(2))
        assertEquals(listOf("e2"), settle(held).map { it.id })
        assertEquals(downloading, settle(held, "d1" to DownloadStatus.Downloading).first().state)
    }

    @Test
    fun aPausedSeasonHoldsOnlyItself() {
        val two = listOf(entry(1, paused, "d1", pausedBy = SeasonPauseScope.Season), entry(2)) +
            listOf(entry(1, meta = "tt2"), entry(2, meta = "tt2"))
        val holds = SeasonQueueHolds(pausedSeasons = setOf(SeasonKey(1, "tt1", 1)))
        assertEquals("tt2e1", nextAfter(two, "d1" to DownloadStatus.Paused, holds = holds))
        // Resuming the season brings back only what the season paused.
        val resumed = SeasonQueuePolicy.resume(two, SeasonPauseScope.Season) { it.parentMetaId == "tt1" }
        assertEquals(resuming, resumed.first().state)
        assertEquals("e1", nextAfter(resumed, "d1" to DownloadStatus.Paused))
    }

    @Test
    fun pauseAllHoldsEveryStartUntilResumeAll() {
        val holds = SeasonQueueHolds(allPausedProfiles = setOf(1))
        val all = SeasonQueuePolicy.pause(season, SeasonPauseScope.All) { it.profileId == 1 }
        assertEquals(listOf(paused, waiting, waiting), all.map { it.state })
        assertEquals(SeasonPauseScope.All, all.first().pausedBy)
        assertNull(nextAfter(all, "d1" to DownloadStatus.Paused, holds = holds))
        // Another profile is not held.
        assertEquals("p2e1", nextAfter(profileB, profile = 2, holds = holds))
    }

    @Test
    fun resumeAllSkipsEpisodesPausedOneByOne() {
        val mixed = listOf(entry(1, paused, "d1"), entry(2, paused, "d2", pausedBy = SeasonPauseScope.All), entry(3))
        val resumed = SeasonQueuePolicy.resume(mixed, SeasonPauseScope.All) { true }
        assertEquals(listOf(paused, resuming, waiting), resumed.map { it.state })
        // E2 goes first (its place), then E3; E1 stays paused.
        assertEquals("e2", nextAfter(resumed, "d1" to DownloadStatus.Paused, "d2" to DownloadStatus.Paused))
    }

    @Test
    fun pauseAllAlsoHoldsAResumeWaitingForItsTurn() {
        val held = listOf(entry(1, resuming, "d1"), entry(2, downloading, "d2"))
        assertEquals(listOf(paused, paused), SeasonQueuePolicy.pause(held, SeasonPauseScope.All) { true }.map { it.state })
    }

    @Test
    fun aQueuePauseIsNotMistakenForTheUsersOwn() {
        // The queue marks the pause first, then pauses the download: settling keeps the queue's reason.
        val marked = SeasonQueuePolicy.pause(season, SeasonPauseScope.Season) { it.id == "e1" }
        assertEquals(SeasonPauseScope.Season, settle(marked, "d1" to DownloadStatus.Paused).first().pausedBy)
        // A download resumed elsewhere is the user's again.
        assertEquals(SeasonPauseScope.Episode, settle(marked, "d1" to DownloadStatus.Downloading).first().pausedBy)
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
    fun aPausedEpisodeStaysPausedAfterARestart() {
        // Stored as Paused; after a restart it is still paused and skipped, and E2 goes on.
        val stored = listOf(entry(1, paused, "d1"), entry(2))
        assertEquals(paused, settle(stored, "d1" to DownloadStatus.Paused).first().state)
        assertEquals("e2", nextAfter(stored, "d1" to DownloadStatus.Paused))
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
