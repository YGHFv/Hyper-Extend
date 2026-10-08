/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import java.security.SecureRandom

/** Only these ten slots are digits; emergency, delete and confirm must not move. */
internal object PinPermutation {
    val slots = (1..3).flatMap { row -> (0..2).map { column -> row to column } } + (4 to 1)

    fun shuffled(random: java.util.Random = SecureRandom()): List<Int> =
        (0..9).toMutableList().also { java.util.Collections.shuffle(it, random) }

    fun <T : Any> digits(views: List<List<T?>>, numbers: List<List<T?>>): List<T>? {
        val digits = slots.map { (row, column) -> views.getOrNull(row)?.getOrNull(column) ?: return null }
        if (digits.indices.any { i -> (0 until i).any { j -> digits[i] === digits[j] } }) return null
        if (slots.withIndex().any { (i, slot) -> numbers.getOrNull(slot.first - 1)?.getOrNull(slot.second) !== digits[i] }) return null
        return digits
    }

    fun <T> reorder(digits: List<T>, permutation: List<Int>): List<T> {
        require(digits.size == 10 && permutation.size == 10 && permutation.toSet() == (0..9).toSet())
        return permutation.map(digits::get)
    }
}
