/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimeoutOptionsTest {
    @Test fun additionalOptionsContainRequestedValues() {
        assertEquals(
            listOf(20 * 60 * 1000, 30 * 60 * 1000, 60 * 60 * 1000, Int.MAX_VALUE),
            ADDITIONAL_SCREEN_TIMEOUTS.map { it.millis },
        )
        assertEquals(
            listOf("20 分钟", "30 分钟", "60 分钟", "永不"),
            ADDITIONAL_SCREEN_TIMEOUTS.map { it.fallbackLabel },
        )
    }

    @Test fun mergeAddsOptionsInMillisecondOrderAndKeepsNativeLabels() {
        val merged = mergeScreenTimeoutOptions(
            entries = listOf("15 秒", "30 秒", "10 分钟", "永不"),
            values = listOf("15000", "30000", "600000", Int.MAX_VALUE.toString()),
            additionalLabels = linkedMapOf(
                20L * 60 * 1000 to "20 分钟",
                30L * 60 * 1000 to "30 分钟",
                60L * 60 * 1000 to "60 分钟",
                Int.MAX_VALUE.toLong() to "不应覆盖",
            ),
        )!!

        assertEquals(
            listOf(15000L, 30000L, 600000L, 1200000L, 1800000L, 3600000L, Int.MAX_VALUE.toLong()),
            merged.map { it.first },
        )
        assertEquals("永不", merged.last().second)
    }

    @Test fun mergeKeepsExistingDuplicateValuesAndDoesNotDuplicateAdditions() {
        val merged = mergeScreenTimeoutOptions(
            entries = listOf("10 分钟", "10 分钟"),
            values = listOf("600000", "600000"),
            additionalLabels = linkedMapOf(600000L to "不应覆盖"),
        )!!

        assertEquals(listOf(600000L, 600000L), merged.map { it.first })
        assertEquals(listOf<CharSequence>("10 分钟", "10 分钟"), merged.map { it.second })
    }

    @Test fun invalidNativeValuesLeaveHostUntouched() {
        assertNull(
            mergeScreenTimeoutOptions(
                entries = listOf("15 秒"),
                values = listOf("invalid"),
                additionalLabels = emptyMap(),
            ),
        )
        assertNull(
            mergeScreenTimeoutOptions(
                entries = listOf("15 秒", "30 秒"),
                values = listOf("15000"),
                additionalLabels = emptyMap(),
            ),
        )
    }

    @Test fun additionalDefinitionsAreUnique() {
        val options = ADDITIONAL_SCREEN_TIMEOUTS
        assertEquals(options.size, options.map { it.millis }.distinct().size)
        assertEquals(options.size, options.map { it.stringResource }.distinct().size)
        assertTrue(options.all { it.millis > 0 })
    }
}
