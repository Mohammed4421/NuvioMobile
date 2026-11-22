package com.nuvio.app.features.downloads

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3-03: the season queue of several profiles. Every profile's episodes are stored together
 * (device-wide `season_queue`, one `profileId` per entry) with the pauses in `season_queue_holds`;
 * these tests read and write them with the real codec and decide with the real policy, as the queue
 * does on a start and on a profile switch.
 */
class ProfileSeasonQueueIsolationTest {
    private val reference = SeasonQueueReference(addonId = "addon", addonName = "Addon", text = "Show 1080p")

    private fun entry(
        profile: Int?,
        episode: Int,
        state: SeasonQueueEntryState = SeasonQueueEntryState.Waiting,
        downloadId: String? = null,
        meta: String = "tt1",
        pausedBy: SeasonPauseScope = SeasonPauseScope.Episode,
    ) = SeasonQueueEntry(
        id = "p${profile}-$meta-e$episode",
        contentType = "series",
        parentMetaId = meta,
        parentMetaType = "series",
        title = "Show $meta",
        videoId = "$meta:1:$episode",
        seasonNumber = 1,
        episodeNumber = episode,
        reference = reference,
        state = state,
        downloadId = downloadId,
        pausedBy = pausedBy,
        profileId = profile,
    )

    private val one = listOf(
        entry(1, 1, SeasonQueueEntryState.Downloading, "d1"),
        entry(1, 2),
    )
    private val two = listOf(
        entry(2, 1, SeasonQueueEntryState.Paused, "d3", meta = "tt2"),
        entry(2, 2, meta = "tt2"),
    )

    /** What a stored queue looks like after a restart: read back from what was written. */
    private fun restarted(entries: List<SeasonQueueEntry>): SeasonQueueStorage.Decoded =
        SeasonQueueStorage.decode(SeasonQueueStorage.encode(entries))

    // F: damaged storage of one profile's part.

    @Test
    fun aDamagedEntryOfOneProfileLeavesTheOtherProfilesQueueWhole() {
        val stored = Json.parseToJsonElement(SeasonQueueStorage.encode(one + two)).jsonObject
        val entries = stored.getValue("entries").jsonArray
        val damagedIndex = entries.indexOfFirst { it.jsonObject.getValue("id").jsonPrimitive.content == two[1].id }
        val damaged = JsonObject(
            stored + ("entries" to JsonArray(entries.mapIndexed { index, element ->
                if (index != damagedIndex) element
                else JsonObject(element.jsonObject + ("seasonNumber" to JsonPrimitive("one")))
            })),
        ).toString()

        val decoded = SeasonQueueStorage.decode(damaged)
        assertEquals(one, SeasonQueuePolicy.visible(decoded.entries, 1))
        assertEquals(listOf(two[0]), SeasonQueuePolicy.visible(decoded.entries, 2))
        // Set aside for keeping (SeasonDownloadQueue stores it under season_queue_unreadable), not dropped.
        assertEquals(1, decoded.unreadable.size)
        assertTrue(two[1].id in decoded.unreadable.single())
        // Profile 1's queue decides exactly as before the damage.
        val settled = SeasonQueuePolicy.settle(decoded.entries, 1, mapOf("d1" to DownloadStatus.Downloading)::get, emptySet())
        assertTrue(SeasonQueuePolicy.busy(settled, 1, emptySet()))
        val finished = SeasonQueuePolicy.settle(decoded.entries, 1, mapOf("d1" to DownloadStatus.Completed)::get, emptySet())
        assertEquals(one[1].id, SeasonQueuePolicy.next(finished, 1)?.id)
    }

    /**
     * The pauses of every profile are one stored value: when it cannot be read, no profile is held
     * by "pause all" or a paused season any more (finding P3-03-F3). Each episode's own pause is
     * stored in the episode and stays.
     */
    @Test
    fun damagedHoldsReleaseEveryProfilesHoldsButKeepEachEpisodesOwnPause() {
        val held = listOf(
            entry(1, 1, SeasonQueueEntryState.Paused, "d1", pausedBy = SeasonPauseScope.All),
            entry(1, 2),
            entry(2, 1, SeasonQueueEntryState.Paused, "d2", meta = "tt2", pausedBy = SeasonPauseScope.Season),
            entry(2, 2, meta = "tt2"),
        )
        val holds = SeasonQueueHolds(allPausedProfiles = setOf(1), pausedSeasons = setOf(SeasonKey(2, "tt2", 1)))
        assertNull(SeasonQueuePolicy.next(held, 1, holds))
        assertNull(SeasonQueuePolicy.next(held, 2, holds))

        val lost = SeasonQueueStorage.decodeHolds(SeasonQueueStorage.encodeHolds(holds).dropLast(3))
        assertEquals(SeasonQueueHolds(), lost)
        val entries = restarted(held).entries
        assertEquals(held.map { it.state to it.pausedBy }, entries.map { it.state to it.pausedBy })
        // The paused episodes stay paused; the waiting ones of both profiles are free to start.
        assertEquals(held[1].id, SeasonQueuePolicy.next(entries, 1, lost)?.id)
        assertEquals(held[3].id, SeasonQueuePolicy.next(entries, 2, lost)?.id)
    }

    // R + P: restart.

    @Test
    fun aRestartReturnsEachProfilesQueueAndPausesWhicheverProfileIsActive() {
        val queue = listOf(
            entry(1, 1, SeasonQueueEntryState.Paused, "d1", pausedBy = SeasonPauseScope.Season),
            entry(1, 2),
            entry(2, 1, SeasonQueueEntryState.Resuming, "d5", meta = "tt2"),
            entry(2, 2, SeasonQueueEntryState.Searching, meta = "tt2"),
            entry(2, 3, meta = "tt2"),
        )
        val holds = SeasonQueueHolds(
            allPausedProfiles = setOf(2),
            pausedSeasons = setOf(SeasonKey(1, "tt1", 1)),
            pausedByAll = mapOf(2 to setOf("film")),
        )

        val decoded = restarted(queue)
        val restoredHolds = SeasonQueueStorage.decodeHolds(SeasonQueueStorage.encodeHolds(holds))
        assertEquals(emptyList(), decoded.unreadable)
        assertEquals(holds, restoredHolds)
        for (active in listOf(1, 2)) {
            val loaded = SeasonQueuePolicy.migrate(decoded.entries, active)
            assertEquals(queue.filter { it.profileId == 1 }, SeasonQueuePolicy.visible(loaded, 1))
            assertEquals(queue.filter { it.profileId == 2 }, SeasonQueuePolicy.visible(loaded, 2))
        }
        // Both profiles are still held after the restart: profile 1 by its paused season, 2 by pause all.
        val settledIn2 = SeasonQueuePolicy.settle(decoded.entries, 2, mapOf("d5" to DownloadStatus.Paused)::get, emptySet())
        assertEquals(SeasonQueueEntryState.Waiting, settledIn2.first { it.id == queue[3].id }.state) // search cut short starts over
        assertNull(SeasonQueuePolicy.next(settledIn2, 2, restoredHolds))
        assertNull(SeasonQueuePolicy.next(decoded.entries, 1, restoredHolds))
        // Resume all in profile 2 releases profile 2 only; its waiting resume goes first.
        val resumedAll = restoredHolds.copy(allPausedProfiles = emptySet(), pausedByAll = emptyMap())
        assertEquals(queue[2].id, SeasonQueuePolicy.next(settledIn2, 2, resumedAll)?.id)
        assertNull(SeasonQueuePolicy.next(decoded.entries, 1, resumedAll))
    }

    @Test
    fun aQueueFromBeforeProfilesGoesToTheProfileActiveAtTheFirstStartOnly() {
        val legacy = listOf(entry(null, 1, SeasonQueueEntryState.Paused, "d1"), entry(null, 2))
        val firstStart = SeasonQueuePolicy.migrate(restarted(legacy).entries, activeProfileId = 2)
        // Saved with the profile, so a later start on another profile does not take it over.
        val laterStart = SeasonQueuePolicy.migrate(restarted(firstStart).entries, activeProfileId = 1)
        assertEquals(listOf(2, 2), laterStart.map { it.profileId })
        assertTrue(SeasonQueuePolicy.visible(laterStart, 1).isEmpty())
        assertEquals(legacy.map { it.id }, SeasonQueuePolicy.visible(laterStart, 2).map { it.id })
    }

    // D: a deleted profile.

    /**
     * ProfileRepository.deleteProfile (Nuvio) does not touch the season queue: a deleted profile's
     * episodes stay stored. They are never shown to, started for, or allowed to hold another
     * profile. A new profile created later with the same index gets them back (finding P3-03-F1).
     */
    @Test
    fun aDeletedProfilesEpisodesStayStoredButNeverReachAnotherProfile() {
        val deleted = listOf(entry(3, 1, SeasonQueueEntryState.Downloading, "d9", meta = "tt3"), entry(3, 2, meta = "tt3"))
        val entries = restarted(one + deleted).entries

        assertEquals(one + deleted, entries)
        assertEquals(one, SeasonQueuePolicy.visible(entries, 1))
        val settled = SeasonQueuePolicy.settle(entries, 1, mapOf("d1" to DownloadStatus.Completed)::get, emptySet())
        assertEquals(deleted, settled.filter { it.profileId == 3 })
        // Its running episode does not hold profile 1's queue.
        assertFalse(SeasonQueuePolicy.busy(settled, 1, emptySet()))
        assertEquals(one[1].id, SeasonQueuePolicy.next(settled, 1)?.id)
        // The index is reused by a new profile: it sees the old episodes.
        assertEquals(deleted, SeasonQueuePolicy.visible(entries, 3))
    }
}
