package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * How the season queue is written and read, so no build silently loses it.
 *
 * **Written in a form Build 12 can read.** Build 12 decodes the whole stored queue at once and,
 * on any value it does not know, keeps an empty queue and overwrites the stored one. So the state is
 * stored only as one of Build 12's four states. A paused episode is stored as `Downloading` plus
 * `"paused": true`, which Build 12 ignores (it ignores unknown keys), as it ignores `profileId`.
 * Going back to Build 12 keeps every entry; there the paused one behaves as in Build 12 (it leaves the
 * queue and the next episode starts), and profiles share the queue again.
 *
 * **Since Build 15** a paused episode also stores what paused it (`pausedBy`), and a paused episode
 * waiting to resume is stored as a paused one plus `"resuming": true`. Builds 13 and 14 read both as
 * paused (they ignore the new keys), which keeps the download and its partial file. Pause all and
 * paused seasons are stored apart, under their own key ([encodeHolds]), which older builds never read.
 *
 * **Read entry by entry, from any build.** A damaged entry is set aside instead of losing the others,
 * an unknown future state is read as the nearest safe one, and whatever cannot be read is returned in
 * [Decoded.unreadable] so the caller can keep it instead of deleting it.
 */
internal object SeasonQueueStorage {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The states Build 12 knows; nothing else is ever written to `state`. */
    private val build12States = setOf("Waiting", "Searching", "Downloading", "NeedsChoice")

    @Serializable
    private data class StoredReference(
        val addonId: String,
        val addonName: String,
        val bingeGroup: String? = null,
        val text: String,
    )

    /** One stored entry: Build 12's fields in Build 12's shape, plus fields Build 12 ignores. */
    @Serializable
    private data class StoredEntry(
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
        val reference: StoredReference,
        val state: String = "Waiting",
        val downloadId: String? = null,
        // Since build 13; Build 12 ignores them.
        val paused: Boolean = false,
        val profileId: Int? = null,
        // Since Build 15; Builds 12-14 ignore them.
        val pausedBy: String? = null,
        val resuming: Boolean = false,
    )

    @Serializable
    private data class StoredQueue(val entries: List<StoredEntry> = emptyList())

    data class Decoded(
        val entries: List<SeasonQueueEntry>,
        /** Stored data that could not be read: the whole payload, or single entries. Never dropped silently. */
        val unreadable: List<String>,
    )

    fun encode(entries: List<SeasonQueueEntry>): String =
        json.encodeToString(StoredQueue.serializer(), StoredQueue(entries.map(::toStored)))

    fun decode(stored: String?): Decoded {
        if (stored.isNullOrBlank()) return Decoded(emptyList(), emptyList())
        val root = runCatching { json.parseToJsonElement(stored) }.getOrNull() as? JsonObject
            ?: return Decoded(emptyList(), listOf(stored))
        val items = when (val value = root["entries"]) {
            null -> return Decoded(emptyList(), emptyList())
            is JsonArray -> value
            else -> return Decoded(emptyList(), listOf(stored))
        }
        val entries = mutableListOf<SeasonQueueEntry>()
        val unreadable = mutableListOf<String>()
        for (item in items) {
            val entry = runCatching { fromStored(json.decodeFromJsonElement(StoredEntry.serializer(), item)) }.getOrNull()
            if (entry != null) entries += entry else unreadable += item.toString()
        }
        return Decoded(entries, unreadable)
    }

    /** Keeps what could not be read next to earlier leftovers (newest last, at most [limit]). */
    fun keepUnreadable(previous: String?, unreadable: List<String>, limit: Int = 20): String {
        val earlier = previous
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        return buildJsonArray { (earlier + unreadable).takeLast(limit).forEach { add(JsonPrimitive(it)) } }.toString()
    }

    private fun toStored(entry: SeasonQueueEntry): StoredEntry = StoredEntry(
        id = entry.id,
        contentType = entry.contentType,
        parentMetaId = entry.parentMetaId,
        parentMetaType = entry.parentMetaType,
        title = entry.title,
        logo = entry.logo,
        poster = entry.poster,
        background = entry.background,
        videoId = entry.videoId,
        seasonNumber = entry.seasonNumber,
        episodeNumber = entry.episodeNumber,
        episodeTitle = entry.episodeTitle,
        episodeThumbnail = entry.episodeThumbnail,
        reference = StoredReference(
            addonId = entry.reference.addonId,
            addonName = entry.reference.addonName,
            bingeGroup = entry.reference.bingeGroup,
            text = entry.reference.text,
        ),
        state = when (entry.state) {
            SeasonQueueEntryState.Paused, SeasonQueueEntryState.Resuming -> "Downloading"
            else -> entry.state.name
        },
        downloadId = entry.downloadId,
        paused = entry.state == SeasonQueueEntryState.Paused || entry.state == SeasonQueueEntryState.Resuming,
        profileId = entry.profileId,
        pausedBy = entry.pausedBy.name.takeIf { entry.state == SeasonQueueEntryState.Paused },
        resuming = entry.state == SeasonQueueEntryState.Resuming,
    )

    private fun fromStored(stored: StoredEntry): SeasonQueueEntry = SeasonQueueEntry(
        id = stored.id,
        contentType = stored.contentType,
        parentMetaId = stored.parentMetaId,
        parentMetaType = stored.parentMetaType,
        title = stored.title,
        logo = stored.logo,
        poster = stored.poster,
        background = stored.background,
        videoId = stored.videoId,
        seasonNumber = stored.seasonNumber,
        episodeNumber = stored.episodeNumber,
        episodeTitle = stored.episodeTitle,
        episodeThumbnail = stored.episodeThumbnail,
        reference = SeasonQueueReference(
            addonId = stored.reference.addonId,
            addonName = stored.reference.addonName,
            bingeGroup = stored.reference.bingeGroup,
            text = stored.reference.text,
        ),
        state = stateOf(stored),
        downloadId = stored.downloadId,
        pausedBy = SeasonPauseScope.entries.firstOrNull { it.name == stored.pausedBy } ?: SeasonPauseScope.Episode,
        profileId = stored.profileId,
    )

    private fun stateOf(stored: StoredEntry): SeasonQueueEntryState = when {
        stored.state == "Downloading" && stored.paused && stored.resuming -> SeasonQueueEntryState.Resuming
        stored.state == "Downloading" && stored.paused -> SeasonQueueEntryState.Paused
        stored.state == "Paused" -> SeasonQueueEntryState.Paused
        stored.state in build12States -> SeasonQueueEntryState.valueOf(stored.state)
        // A state from a newer build: an episode handed to the downloader is matched against its
        // download again (the queue settles it); anything else waits for its turn.
        stored.downloadId != null -> SeasonQueueEntryState.Downloading
        else -> SeasonQueueEntryState.Waiting
    }

    @Serializable
    private data class StoredHolds(
        val allPausedProfiles: List<Int> = emptyList(),
        val pausedSeasons: List<SeasonKey> = emptyList(),
        val pausedByAll: Map<String, List<String>> = emptyMap(),
    )

    fun encodeHolds(holds: SeasonQueueHolds): String = json.encodeToString(
        StoredHolds.serializer(),
        StoredHolds(
            allPausedProfiles = holds.allPausedProfiles.sorted(),
            pausedSeasons = holds.pausedSeasons.toList(),
            pausedByAll = holds.pausedByAll.map { (profile, ids) -> profile.toString() to ids.sorted() }.toMap(),
        ),
    )

    /** Nothing stored (every build before 15) or unreadable: nothing is held. */
    fun decodeHolds(stored: String?): SeasonQueueHolds {
        if (stored.isNullOrBlank()) return SeasonQueueHolds()
        val holds = runCatching { json.decodeFromString(StoredHolds.serializer(), stored) }.getOrNull()
            ?: return SeasonQueueHolds()
        return SeasonQueueHolds(
            allPausedProfiles = holds.allPausedProfiles.toSet(),
            pausedSeasons = holds.pausedSeasons.toSet(),
            pausedByAll = holds.pausedByAll.mapNotNull { (profile, ids) -> profile.toIntOrNull()?.let { it to ids.toSet() } }.toMap(),
        )
    }
}
