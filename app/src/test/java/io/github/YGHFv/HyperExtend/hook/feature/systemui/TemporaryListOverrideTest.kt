/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class TemporaryListOverrideTest {
    @Test fun restoresExactOriginalsAfterNestedCalls() {
        val original = arrayOf<Any?>(listOf("cloud"), listOf("local"))
        val state = original.copyOf()
        withTemporaryLists(state, { state[it] }, { i, v -> state[i] = v }) {
            val outer = state.copyOf()
            assertEquals(emptyList<String>(), state[0])
            withTemporaryLists(state, { state[it] }, { i, v -> state[i] = v }) { assertNotSame(outer[0], state[0]) }
            assertSame(outer[0], state[0])
        }
        original.indices.forEach { assertSame(original[it], state[it]) }
    }

    @Test fun preservesConcurrentCloudReplacementEvenWhenTheActionThrows() {
        val local = listOf("local")
        val newCloud = listOf("new cloud")
        val state = arrayOf<Any?>(listOf("old cloud"), local)
        runCatching {
            withTemporaryLists(state, { state[it] }, { i, v -> state[i] = v }) {
                state[0] = newCloud
                error("host failure")
            }
        }
        assertSame(newCloud, state[0])
        assertSame(local, state[1])
    }

    @Test fun failureToSetSecondFieldRestoresTheFirstField() {
        val cloud = listOf("cloud")
        val state = arrayOf<Any?>(cloud, null)
        var invoked = false
        val outcome = runCatching {
            withTemporaryLists(state, { state[it] }, { i, v -> if (i == 1) error("write denied") else state[i] = v }) {
                invoked = true
            }
        }
        assertTrue(outcome.isFailure)
        assertFalse(invoked)
        assertSame(cloud, state[0])
    }
}
