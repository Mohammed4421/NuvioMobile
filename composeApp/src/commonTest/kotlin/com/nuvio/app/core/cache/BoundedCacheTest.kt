package com.nuvio.app.core.cache

import kotlin.test.Test
import kotlin.test.assertEquals
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
