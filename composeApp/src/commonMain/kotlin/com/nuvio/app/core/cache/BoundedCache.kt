package com.nuvio.app.core.cache

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Nuvio Plus: an in-memory cache with a fixed number of entries, safe to use from several
 * coroutines at once. When full, the entry read or written least recently is dropped, so a long
 * browsing session cannot grow the cache without limit. Drop-in for the `mutableMapOf` caches it
 * replaces: `cache[key]`, `cache[key] = value`, [remove], [clear].
 */
class BoundedCache<K, V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val lock = SynchronizedObject()

    // Insertion order of a LinkedHashMap; a read moves the key to the end, so the first key is
    // always the least recently used one.
    private val entries = LinkedHashMap<K, V>()

    val size: Int get() = synchronized(lock) { entries.size }

    operator fun get(key: K): V? = synchronized(lock) {
        if (!entries.containsKey(key)) return@synchronized null
        @Suppress("UNCHECKED_CAST")
        val value = entries.remove(key) as V
        entries[key] = value
        value
    }

    fun containsKey(key: K): Boolean = synchronized(lock) { entries.containsKey(key) }

    operator fun set(key: K, value: V) {
        synchronized(lock) {
            entries.remove(key)
            entries[key] = value
            while (entries.size > maxEntries) {
                entries.remove(entries.keys.first())
            }
        }
    }

    fun remove(key: K): V? = synchronized(lock) { entries.remove(key) }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** The first value whose key matches, without changing the eviction order. */
    fun firstValueOrNull(predicate: (K) -> Boolean): V? = synchronized(lock) {
        entries.entries.firstOrNull { predicate(it.key) }?.value
    }
}
