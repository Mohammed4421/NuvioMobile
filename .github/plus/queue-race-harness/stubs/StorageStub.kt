package com.nuvio.app.features.downloads

/**
 * Stand-in for SeasonQueueStorage (its JSON form needs the serialization compiler plugin, which is not
 * available offline; that form is covered by SeasonQueueStorageTest in CI). Same API; keeps every field
 * the queue's profile logic depends on, including a null profileId (queues from Build 12).
 */
internal object SeasonQueueStorage {
    private const val F = "\u0001"
    data class Decoded(val entries: List<SeasonQueueEntry>, val unreadable: List<String>)

    fun encode(entries: List<SeasonQueueEntry>): String = entries.joinToString("\n") { e ->
        listOf(e.id, e.contentType, e.parentMetaId, e.parentMetaType, e.title, e.videoId, e.seasonNumber, e.episodeNumber,
            e.reference.addonId, e.reference.addonName, e.reference.bingeGroup ?: "", e.reference.text,
            e.state.name, e.downloadId ?: "", e.profileId?.toString() ?: "", e.pausedBy.name).joinToString(F)
    }

    fun decode(stored: String?): Decoded {
        if (stored.isNullOrBlank()) return Decoded(emptyList(), emptyList())
        return Decoded(stored.lines().map { line ->
            val p = line.split(F)
            SeasonQueueEntry(
                id = p[0], contentType = p[1], parentMetaId = p[2], parentMetaType = p[3], title = p[4], videoId = p[5],
                seasonNumber = p[6].toInt(), episodeNumber = p[7].toInt(),
                reference = SeasonQueueReference(p[8], p[9], p[10].ifEmpty { null }, p[11]),
                state = SeasonQueueEntryState.valueOf(p[12]), downloadId = p[13].ifEmpty { null },
                profileId = p[14].ifEmpty { null }?.toInt(),
                pausedBy = SeasonPauseScope.valueOf(p[15]),
            )
        }, emptyList())
    }

    fun encodeHolds(h: SeasonQueueHolds): String = buildList {
        add("all:" + h.allPausedProfiles.sorted().joinToString(","))
        h.pausedSeasons.forEach { add("season:${it.profileId}|${it.parentMetaId}|${it.seasonNumber}") }
        h.pausedByAll.forEach { (profile, ids) -> add("byall:$profile|" + ids.sorted().joinToString(",")) }
    }.joinToString("\n")

    fun decodeHolds(stored: String?): SeasonQueueHolds {
        if (stored.isNullOrBlank()) return SeasonQueueHolds()
        val lines = stored.lines()
        return SeasonQueueHolds(
            allPausedProfiles = lines.first { it.startsWith("all:") }.removePrefix("all:").split(",").filter { it.isNotEmpty() }.map { it.toInt() }.toSet(),
            pausedSeasons = lines.filter { it.startsWith("season:") }.map {
                val p = it.removePrefix("season:").split("|"); SeasonKey(p[0].toInt(), p[1], p[2].toInt())
            }.toSet(),
            pausedByAll = lines.filter { it.startsWith("byall:") }.associate {
                val p = it.removePrefix("byall:").split("|"); p[0].toInt() to p[1].split(",").filter { id -> id.isNotEmpty() }.toSet()
            },
        )
    }

    fun keepUnreadable(previous: String?, unreadable: List<String>, limit: Int = 20): String = ""
}
