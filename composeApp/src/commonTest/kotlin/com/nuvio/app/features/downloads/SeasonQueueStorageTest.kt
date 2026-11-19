package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The stored season queue across builds: Build 13 writes what Build 12 can read, and reads Build 12's
 * queues and anything damaged or newer without losing the readable entries.
 */
class SeasonQueueStorageTest {

    // --- Build 12's model and decoder, copied exactly from custom-subtitles@28a3e4dd -------------

    @Serializable
    private enum class Build12State { Waiting, Searching, Downloading, NeedsChoice }

    @Serializable
    private data class Build12Reference(val addonId: String, val addonName: String, val bingeGroup: String? = null, val text: String)

    @Serializable
    private data class Build12Entry(
        val id: String,
        val contentType: String,
        val parentMetaId: String,
        val parentMetaType: String,
        val title: String,
        val logo: String? = null,
        val poster: String? = null,
        val background: String? = null,
        val videoId: String,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val episodeTitle: String? = null,
        val episodeThumbnail: String? = null,
        val reference: Build12Reference,
        val state: Build12State = Build12State.Waiting,
        val downloadId: String? = null,
    )

    @Serializable
    private data class Build12Queue(val entries: List<Build12Entry> = emptyList())

    private val build12Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What Build 12 does on start: the whole queue, or nothing (and nothing then overwrites the store). */
    private fun build12Load(stored: String): List<Build12Entry> =
        runCatching { build12Json.decodeFromString(Build12Queue.serializer(), stored).entries }.getOrNull().orEmpty()

    // --- Entries ----------------------------------------------------------------------------------

    private val reference = SeasonQueueReference(addonId = "addon", addonName = "Addon", bingeGroup = "g", text = "Show 1080p")

    private fun entry(episode: Int, state: SeasonQueueEntryState, downloadId: String? = null, profile: Int? = 2) = SeasonQueueEntry(
        id = "e$episode",
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        title = "Show",
        poster = "https://img/p.jpg",
        videoId = "tt1:1:$episode",
        seasonNumber = 1,
        episodeNumber = episode,
        episodeTitle = "Episode $episode",
        reference = reference,
        state = state,
        downloadId = downloadId,
        profileId = profile,
    )

    private val queue = listOf(
        entry(1, SeasonQueueEntryState.Paused, "d1"),
        entry(2, SeasonQueueEntryState.Downloading, "d2"),
        entry(3, SeasonQueueEntryState.Searching),
        entry(4, SeasonQueueEntryState.NeedsChoice),
        entry(5, SeasonQueueEntryState.Waiting),
    )

    @Test
    fun everyStateSurvivesARoundTrip() {
        val decoded = SeasonQueueStorage.decode(SeasonQueueStorage.encode(queue))
        assertEquals(queue, decoded.entries)
        assertEquals(emptyList(), decoded.unreadable)
    }

    @Test
    fun aPausedEpisodeIsStoredInAFormBuild12Knows() {
        val stored = SeasonQueueStorage.encode(queue)
        assertTrue("\"state\":\"Downloading\",\"downloadId\":\"d1\",\"paused\":true" in stored, stored)
        assertTrue("\"Paused\"" !in stored, stored)
    }

    /** Going back to Build 12 after Build 13 keeps every entry of the queue. */
    @Test
    fun build12ReadsTheQueueBuild13Writes() {
        val read = build12Load(SeasonQueueStorage.encode(queue))
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), read.map { it.id })
        // The paused episode reads as Downloading there (Build 12 then treats it as it always did).
        assertEquals(Build12State.Downloading, read.first().state)
        assertEquals("d1", read.first().downloadId)
        assertEquals("Episode 1", read.first().episodeTitle)
    }

    /** The cause: Build 12 drops the whole queue when one entry has a state it does not know. */
    @Test
    fun aStateBuild12DoesNotKnowWouldEraseItsWholeQueue() {
        val withUnknownState = SeasonQueueStorage.encode(queue).replace("\"state\":\"Waiting\"", "\"state\":\"Paused\"")
        assertFailsWith<Exception> { build12Json.decodeFromString(Build12Queue.serializer(), withUnknownState) }
        assertEquals(emptyList(), build12Load(withUnknownState))
    }

    @Test
    fun aQueueSavedByBuild12StillLoads() {
        val build12Saved = build12Json.encodeToString(
            Build12Queue.serializer(),
            Build12Queue(
                listOf(
                    Build12Entry("a", "series", "tt1", "series", "Show", videoId = "tt1:1:1", seasonNumber = 1, episodeNumber = 1,
                        reference = Build12Reference("addon", "Addon", text = "Show 1080p"), state = Build12State.Downloading, downloadId = "d1"),
                    Build12Entry("b", "series", "tt1", "series", "Show", videoId = "tt1:1:2", seasonNumber = 1, episodeNumber = 2,
                        reference = Build12Reference("addon", "Addon", text = "Show 1080p")),
                ),
            ),
        )
        val decoded = SeasonQueueStorage.decode(build12Saved)
        assertEquals(listOf(SeasonQueueEntryState.Downloading, SeasonQueueEntryState.Waiting), decoded.entries.map { it.state })
        assertEquals(listOf(null, null), decoded.entries.map { it.profileId }) // given the active profile when loaded (F2)
        assertEquals("d1", decoded.entries.first().downloadId)
        assertEquals(emptyList(), decoded.unreadable)
    }

    @Test
    fun aDamagedEntryIsSetAsideAndTheOthersAreKept() {
        val stored = SeasonQueueStorage.encode(queue)
        val damaged = stored.replaceFirst("\"seasonNumber\":1", "\"seasonNumber\":\"one\"")
        val decoded = SeasonQueueStorage.decode(damaged)
        assertEquals(listOf("e2", "e3", "e4", "e5"), decoded.entries.map { it.id })
        assertEquals(1, decoded.unreadable.size)
        assertTrue("\"one\"" in decoded.unreadable.single())
    }

    @Test
    fun anEntryWithoutARequiredFieldIsSetAside() {
        val missing = "{\"entries\":[{\"id\":\"x\",\"title\":\"Show\"}," + SeasonQueueStorage.encode(listOf(queue[4])).removePrefix("{\"entries\":[")
        val decoded = SeasonQueueStorage.decode(missing)
        assertEquals(listOf("e5"), decoded.entries.map { it.id })
        assertEquals(1, decoded.unreadable.size)
    }

    @Test
    fun anUnreadablePayloadIsReturnedWhole() {
        for (broken in listOf("{not json", "[1,2]", "{\"entries\":\"oops\"}")) {
            val decoded = SeasonQueueStorage.decode(broken)
            assertEquals(emptyList(), decoded.entries, broken)
            assertEquals(listOf(broken), decoded.unreadable, broken)
        }
    }

    @Test
    fun nothingStoredIsAnEmptyQueue() {
        for (empty in listOf(null, "", "{}", "{\"entries\":[]}")) {
            assertEquals(SeasonQueueStorage.Decoded(emptyList(), emptyList()), SeasonQueueStorage.decode(empty), empty.toString())
        }
    }

    @Test
    fun aStateFromANewerBuildIsReadAsTheNearestSafeOne() {
        val newer = SeasonQueueStorage.encode(listOf(entry(1, SeasonQueueEntryState.Downloading, "d1"), entry(2, SeasonQueueEntryState.Waiting)))
            .replace("\"state\":\"Downloading\"", "\"state\":\"Throttled\"")
            .replace("\"state\":\"Waiting\"", "\"state\":\"Scheduled\"")
        val decoded = SeasonQueueStorage.decode(newer)
        // Handed to the downloader: matched with its download again. Otherwise: waits for its turn.
        assertEquals(listOf(SeasonQueueEntryState.Downloading, SeasonQueueEntryState.Waiting), decoded.entries.map { it.state })
        assertEquals(emptyList(), decoded.unreadable)
    }

    @Test
    fun theLiteralPausedStateOfEarlierTestBuildsIsStillRead() {
        val literal = SeasonQueueStorage.encode(listOf(entry(1, SeasonQueueEntryState.Downloading, "d1")))
            .replace("\"state\":\"Downloading\"", "\"state\":\"Paused\"")
        assertEquals(SeasonQueueEntryState.Paused, SeasonQueueStorage.decode(literal).entries.single().state)
    }

    @Test
    fun unreadableDataIsKeptWithEarlierLeftovers() {
        val first = SeasonQueueStorage.keepUnreadable(null, listOf("a"))
        val second = SeasonQueueStorage.keepUnreadable(first, listOf("b", "c"))
        assertEquals("[\"a\",\"b\",\"c\"]", second)
        assertEquals("[\"c\"]", SeasonQueueStorage.keepUnreadable(second, emptyList(), limit = 1))
        assertEquals("[\"x\"]", SeasonQueueStorage.keepUnreadable("{damaged", listOf("x")))
    }
}
