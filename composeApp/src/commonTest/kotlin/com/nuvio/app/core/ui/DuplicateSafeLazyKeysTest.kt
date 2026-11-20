package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DuplicateSafeLazyKeysTest {
    @Test
    fun `duplicate lazy keys receive stable occurrence suffixes`() {
        val result = listOf("duplicate", "other", "duplicate")
            .withDuplicateSafeLazyKeys { value -> value }

        assertEquals(listOf("duplicate#0", "other", "duplicate#1"), result.map { it.lazyKey })
    }

    @Test
    fun `generated suffixes do not collide with real keys`() {
        val keys = listOf("section", "section#0", "section")
            .withDuplicateSafeLazyKeys { it }.map { it.lazyKey }

        assertEquals(listOf("section#1", "section#0", "section#2"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `generated keys remain unique across colliding string representations`() {
        val keys = listOf<Any>(1, "1", 1, "1")
            .withDuplicateSafeLazyKeys { it }.map { it.lazyKey }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `key selector is evaluated once per item`() {
        var evaluations = 0
        listOf("one", "two", "one").withDuplicateSafeLazyKeys { value ->
            evaluations++
            value
        }
        assertEquals(3, evaluations)
    }
}
