package com.nuvio.app.core.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Nuvio Plus: joins identical requests that run at the same time. The first caller for a key does
 * the work; callers that arrive while it is running wait for that result instead of sending the same
 * network requests again. Nothing is kept after the work finishes (caching is the caller's job), and
 * a failure reaches every waiting caller, so the next call starts fresh.
 */
class InFlightRequests<K, V> {
    private val mutex = Mutex()
    private val running = HashMap<K, CompletableDeferred<V>>()

    suspend fun join(key: K, block: suspend () -> V): V {
        val (deferred, owner) = mutex.withLock {
            running[key]?.takeIf { !it.isCompleted }?.let { it to false }
                ?: CompletableDeferred<V>().also { running[key] = it }.let { it to true }
        }
        if (!owner) {
            return try {
                deferred.await()
            } catch (cancelled: CancellationException) {
                // The caller doing the work was cancelled (it left the screen), not this one:
                // this caller does the work itself.
                currentCoroutineContext().ensureActive()
                join(key, block)
            }
        }
        try {
            // A failure, cancellation included, completes the shared result and is rethrown here.
            deferred.completeWith(runCatching { block() })
            return deferred.await()
        } finally {
            // Also when this caller was cancelled, so a finished request never stays registered.
            withContext(NonCancellable) {
                mutex.withLock { if (running[key] === deferred) running.remove(key) }
            }
        }
    }
}
