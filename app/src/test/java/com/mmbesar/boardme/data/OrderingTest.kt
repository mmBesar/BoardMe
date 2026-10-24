package com.mmbesar.boardme.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderingTest {

    @Test
    fun insertsAtTop() {
        assertEquals(listOf(9L, 1L, 2L, 3L), insertAt(listOf(1, 2, 3), movedId = 9, index = 0))
    }

    @Test
    fun insertsInTheMiddle() {
        assertEquals(listOf(1L, 9L, 2L, 3L), insertAt(listOf(1, 2, 3), movedId = 9, index = 1))
    }

    @Test
    fun hugeIndexMeansBottom() {
        assertEquals(listOf(1L, 2L, 9L), insertAt(listOf(1, 2), movedId = 9, index = Int.MAX_VALUE))
    }

    @Test
    fun negativeIndexMeansTop() {
        assertEquals(listOf(9L, 1L, 2L), insertAt(listOf(1, 2), movedId = 9, index = -5))
    }

    @Test
    fun movingWithinTheSameColumnDoesNotDuplicate() {
        // 2 is already in the list; moving it to the top must not leave a second copy.
        assertEquals(listOf(2L, 1L, 3L), insertAt(listOf(1, 2, 3), movedId = 2, index = 0))
    }

    @Test
    fun emptyColumn() {
        assertEquals(listOf(5L), insertAt(emptyList(), movedId = 5, index = 3))
    }
}
