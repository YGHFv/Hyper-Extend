/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class PinPermutationTest {
    @Test fun everyShuffleKeepsExactlyTenDigits() {
        repeat(100) { seed ->
            val permutation = PinPermutation.shuffled(Random(seed.toLong()))
            assertEquals((0..9).toSet(), permutation.toSet())
            assertEquals(10, PinPermutation.reorder((0..9).toList(), permutation).size)
        }
    }

    @Test fun acceptsAliasedRowsAndDoesNotTreatControlsAsDigits() {
        val digits = List(10) { Any() }
        val views = MutableList(6) { MutableList<Any?>(4) { Any() } }
        PinPermutation.slots.forEachIndexed { i, (row, column) -> views[row][column] = digits[i] }
        val numbers = views.subList(1, 5).map { it.toMutableList() }
        numbers[3][2] = Any() // OS4 uses manual verify here, not mViews' delete button.
        assertEquals(digits, PinPermutation.digits(views, numbers))
        assertFalse(PinPermutation.slots.contains(4 to 2))
        assertFalse(PinPermutation.slots.any { it.first == 5 })
        numbers[3][1] = Any()
        assertNull(PinPermutation.digits(views, numbers))
    }

    @Test fun rejectsMissingDuplicatedAndMismatchedDigits() {
        assertNull(PinPermutation.digits<Any>(emptyList(), emptyList()))
        val repeated = Any()
        val views = List(6) { List(3) { repeated } }
        assertNull(PinPermutation.digits(views, views.take(4)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPermutationBeforeChangingTheLayout() {
        PinPermutation.reorder((0..9).toList(), List(10) { 0 })
    }
}
