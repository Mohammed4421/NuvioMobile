package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamGroup
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CompletableDeferred

object DebridSettingsRepository { fun snapshot(): Any = Unit }
object DebridStreamPresentation { fun apply(groups: List<StreamGroup>, settings: Any): List<StreamGroup> = groups }

sealed class DirectDebridPlayableResult {
    data class Success(val stream: StreamItem) : DirectDebridPlayableResult()
    data object Error : DirectDebridPlayableResult()
}

/**
 * Resolves immediately unless [hold] is true; then each call waits on its own gate, which the test
 * completes later (a late network answer), in any order.
 */
object DirectDebridPlaybackResolver {
    var hold = false
    val calls = mutableListOf<Pair<String, CompletableDeferred<Boolean>>>()
    /** Calls whose answer arrived after the caller was cancelled. */
    var lateAnswersIgnored = 0

    fun shouldResolveToPlayableStream(stream: StreamItem): Boolean = false

    suspend fun resolveToPlayableStream(stream: StreamItem, season: Int?, episode: Int?): DirectDebridPlayableResult {
        if (!hold) return DirectDebridPlayableResult.Success(stream)
        val gate = CompletableDeferred<Boolean>()
        calls += "S${season}E$episode" to gate
        // Like the real resolver: an await that a cancelled caller leaves.
        return if (gate.await()) DirectDebridPlayableResult.Success(stream) else DirectDebridPlayableResult.Error
    }
}
