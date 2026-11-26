package com.nuvio.app.core.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedCacheTest {
    @Test
    fun neverHoldsMoreThanItsLimit() {
        val cache = BoundedCache<Int, String>(3)
        repeat(100) { cache[it] = "v$it" }
        assertEquals(3, cache.size)
        assertEquals("v99", cache[99])
        assertNull(cache[0])
    }

    @Test
    fun dropsTheLeastRecentlyUsedEntry() {
        val cache = BoundedCache<String, Int>(2)
        cache["a"] = 1
        cache["b"] = 2
        assertEquals(1, cache["a"]) // "a" is now the most recent
        cache["c"] = 3
        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
    }

    @Test
    fun keepsNullValuesApartFromMissingKeys() {
        val cache = BoundedCache<String, String?>(2)
        cache["none"] = null
        assertTrue(cache.containsKey("none"))
        assertNull(cache["none"])
        cache.remove("none")
        assertTrue(!cache.containsKey("none"))
    }

    @Test
    fun clearAndPrefixLookup() {
        val cache = BoundedCache<String, Int>(4)
        cache["10:movie:en"] = 1
        cache["11:tv:ar"] = 2
        assertEquals(2, cache.firstValueOrNull { it.startsWith("11:tv:") })
        cache.clear()
        assertEquals(0, cache.size)
    }
}

class InFlightRequestsTest {
    @Test
    fun identicalRequestsAtTheSameTimeShareOneCall() = runTest {
        val requests = InFlightRequests<String, Int>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { requests.join("k") { calls++; gate.await(); 42 } }
        val second = async { requests.join("k") { calls++; 7 } }
        yield()
        gate.complete(Unit)
        assertEquals(42, first.await())
        assertEquals(42, second.await())
        assertEquals(1, calls)
    }

    @Test
    fun differentKeysAndLaterCallsRunAgain() = runTest {
        val requests = InFlightRequests<String, Int>()
        var calls = 0
        assertEquals(1, requests.join("a") { ++calls })
        assertEquals(2, requests.join("a") { ++calls })
        assertEquals(3, requests.join("b") { ++calls })
    }

    @Test
    fun aFailureReachesTheCallerAndIsNotKept() = runTest {
        val requests = InFlightRequests<String, Int>()
        assertFailsWith<IllegalStateException> { requests.join("k") { error("boom") } }
        assertEquals(5, requests.join("k") { 5 })
    }

    @Test
    fun aWaiterDoesTheWorkWhenTheFirstCallerIsCancelled() = runTest {
        val requests = InFlightRequests<String, Int>()
        val gate = CompletableDeferred<Unit>()
        val first = launch { requests.join("k") { gate.await(); 1 } }
        yield()
        val second = async { requests.join("k") { 2 } }
        yield()
        first.cancel()
        assertEquals(2, second.await())
    }
}
