package com.mmbesar.boardme.data

/**
 * Pure helper (no Android, easy to test): where does a moved task land?
 *
 * [others] are the ids already in the target column (without the moved task).
 * The moved task is inserted at [index] (0 = top). Out-of-range values are
 * clamped, so "Int.MAX_VALUE" simply means "at the bottom".
 */
fun insertAt(others: List<Long>, movedId: Long, index: Int): List<Long> {
    val result = others.filter { it != movedId }.toMutableList()
    result.add(index.coerceIn(0, result.size), movedId)
    return result
}
