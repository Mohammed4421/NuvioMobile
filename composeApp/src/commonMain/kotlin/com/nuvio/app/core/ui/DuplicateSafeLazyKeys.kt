package com.nuvio.app.core.ui

internal data class DuplicateSafeLazyEntry<T>(
    val value: T,
    val lazyKey: Any,
)

internal fun <T> List<T>.withDuplicateSafeLazyKeys(key: (T) -> Any): List<DuplicateSafeLazyEntry<T>> {
    // Compute the original keys once. Keep them all reserved so that a generated
    // "name#0" cannot overwrite an unrelated entry whose actual key is "name#0".
    val keys = map(key)
    val keyCounts = keys.groupingBy { it }.eachCount()
    val reservedKeys = keyCounts.keys
    val usedKeys = mutableSetOf<Any>()
    val occurrences = mutableMapOf<Any, Int>()

    return mapIndexed { index, entry ->
        val baseKey = keys[index]
        val lazyKey = if (keyCounts.getValue(baseKey) == 1) {
            baseKey
        } else {
            var occurrence = occurrences.getOrElse(baseKey) { 0 }
            var candidate = "$baseKey#$occurrence"
            while (candidate in reservedKeys || candidate in usedKeys) {
                occurrence++
                candidate = "$baseKey#$occurrence"
            }
            occurrences[baseKey] = occurrence + 1
            candidate
        }
        usedKeys.add(lazyKey)
        DuplicateSafeLazyEntry(value = entry, lazyKey = lazyKey)
    }
}
