package com.nuvio.app.features.streams

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class StreamDebridCacheState { CACHED, NOT_CACHED, UNKNOWN }
data class DebridCacheStatus(val state: StreamDebridCacheState)
data class BehaviorHints(val bingeGroup: String? = null, val filename: String? = null, val videoSize: Long? = null)

data class StreamItem(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val addonId: String = "addon",
    val addonName: String = "Addon",
    val behaviorHints: BehaviorHints = BehaviorHints(),
    val playableDirectUrl: String? = null,
    val debridCacheStatus: DebridCacheStatus? = null,
    val streamLabel: String = "s",
)

class Manifest(val id: String, val name: String)
fun Manifest.supportsStream(type: String, videoId: String): Boolean = true

data class InstalledStreamAddonTarget(val addonName: String, val addonId: String, val manifest: Manifest)

data class StreamGroup(val streams: List<StreamItem>)

sealed class StreamLoadCompletion {
    data class Addon(val group: StreamGroup) : StreamLoadCompletion()
}

data class StreamSearchTargets(
    val type: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val streamAddons: List<InstalledStreamAddonTarget>,
    val serverSources: List<Any>,
    val pluginProviderGroups: List<Any>,
)

object StreamSearchSessions {
    /** Profile id each search was made for. */
    val searchedFor = mutableListOf<Pair<Int, String>>()
    /** Add-ons each search asked, in order. */
    val askedAddons = mutableListOf<List<String>>()
    /** When set, the streams one add-on answers for one episode (the default answers one 1080p stream). */
    var answer: ((addonId: String, videoId: String) -> List<StreamItem>)? = null
    fun search(profileId: Int, targets: StreamSearchTargets, forceRefresh: Boolean): Flow<StreamLoadCompletion> = flow {
        searchedFor += profileId to targets.videoId
        askedAddons += targets.streamAddons.map { it.addonId }
        val custom = answer
        if (custom == null) {
            emit(
                StreamLoadCompletion.Addon(
                    StreamGroup(listOf(StreamItem(name = "Show 1080p WEB", addonId = "addon", playableDirectUrl = "https://x/${targets.videoId}.mkv"))),
                ),
            )
        } else {
            targets.streamAddons.forEach { emit(StreamLoadCompletion.Addon(StreamGroup(custom(it.addonId, targets.videoId)))) }
        }
    }
}

class Addon(val manifest: Manifest?, val displayTitle: String)
fun Addon.streamAddonInstanceId(id: String): String = id
