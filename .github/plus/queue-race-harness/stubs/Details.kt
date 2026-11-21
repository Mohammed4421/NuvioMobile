package com.nuvio.app.features.details

import kotlinx.coroutines.CompletableDeferred

data class MetaVideo(val id: String, val season: Int?, val episode: Int?, val available: Boolean = true, val title: String = "", val thumbnail: String? = null)
data class MetaDetails(val videos: List<MetaVideo>)

/** peek() is empty unless [cached]; fetch() waits on [gate] when it is set (a slow network). */
object MetaDetailsRepository {
    var cached: MetaDetails? = null
    /** Per show, for tests with several shows; falls back to [cached]. */
    val byId = mutableMapOf<String, MetaDetails>()
    var gate: CompletableDeferred<MetaDetails>? = null
    fun peek(type: String, id: String): MetaDetails? = byId[id] ?: cached
    suspend fun fetch(type: String, id: String): MetaDetails = gate?.await() ?: error("no meta")
}

fun season(count: Int, seasonNumber: Int = 1, prefix: String = "tt1") =
    MetaDetails((1..count).map { MetaVideo("$prefix:$seasonNumber:$it", seasonNumber, it) })
