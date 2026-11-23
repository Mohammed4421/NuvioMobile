package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Finding F1 (journeys-profiles.md), season queue side: when a profile is deleted, its episodes and
 * holds are dropped (SeasonDownloadQueue.forgetProfile applies these two functions), so a new
 * profile that gets the same index starts with an empty queue; the other profiles keep theirs.
 */
class ProfileDeletedQueueCleanupTest {
    private val reference = SeasonQueueReference(addonId = "addon", addonName = "Addon", text = "Show 1080p")

    private fun entry(profile: Int?, episode: Int, meta: String, state: SeasonQueueEntryState = SeasonQueueEntryState.Waiting) =
        SeasonQueueEntry(
            id = "p$profile-$meta-e$episode",
            contentType = "series",
            parentMetaId = meta,
            parentMetaType = "series",
            title = "Show $meta",
            videoId = "$meta:1:$episode",
            seasonNumber = 1,
            episodeNumber = episode,
            reference = reference,
            state = state,
            profileId = profile,
        )

    @Test
    fun theDeletedProfilesEpisodesGoAndTheOthersKeepTheirOrder() {
        val queue = listOf(
            entry(1, 1, "tt1", SeasonQueueEntryState.Downloading),
            entry(2, 1, "tt2", SeasonQueueEntryState.Paused),
            entry(1, 2, "tt1"),
            entry(2, 2, "tt2"),
            entry(3, 1, "tt3"),
        )

        val left = SeasonQueuePolicy.withoutProfile(queue, profileId = 2)

        assertEquals(listOf("p1-tt1-e1", "p1-tt1-e2", "p3-tt3-e1"), left.map { it.id })
        assertEquals(left, SeasonQueueStorage.decode(SeasonQueueStorage.encode(left)).entries, "not kept as stored")
    }

    @Test
    fun aNewProfileAtTheSameIndexSeesNothingOfTheDeletedOne() {
        val left = SeasonQueuePolicy.withoutProfile(listOf(entry(2, 1, "tt2"), entry(1, 1, "tt1")), profileId = 2)

        assertTrue(SeasonQueuePolicy.visible(left, 2).isEmpty())
        assertEquals(1, SeasonQueuePolicy.visible(left, 1).size)
    }

    @Test
    fun theDeletedProfilesHoldsGoAndTheOthersStay() {
        val holds = SeasonQueueHolds(
            allPausedProfiles = setOf(1, 2),
            pausedSeasons = setOf(SeasonKey(1, "tt1", 1), SeasonKey(2, "tt2", 1)),
            pausedByAll = mapOf(1 to setOf("d1"), 2 to setOf("d2")),
        )

        val left = holds.withoutProfile(2)

        assertFalse(left.allPaused(2))
        assertTrue(left.allPaused(1))
        assertEquals(setOf(SeasonKey(1, "tt1", 1)), left.pausedSeasons)
        assertEquals(mapOf(1 to setOf("d1")), left.pausedByAll)
        assertEquals(left, SeasonQueueStorage.decodeHolds(SeasonQueueStorage.encodeHolds(left)), "not kept as stored")
    }
}
