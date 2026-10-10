/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class StockTilesScopeTest {
    private val scope = StockTilesScope()
    private val owner = Any()
    private val key = StockTilesTargets.STOCK_RESOURCE

    @Test fun readsOutsideEditorScopeStayNative() {
        assertFalse(scope.take(owner, key))
    }

    @Test fun matchingReadIsConsumedExactlyOnce() {
        scope.withFrame(StockTilesScope.Frame(owner, key)) {
            assertTrue(scope.take(owner, key))
            assertFalse(scope.take(owner, key))
        }
        assertFalse(scope.take(owner, key))
    }

    @Test fun wrongReceiverOrResourceDoesNotConsumeTheRead() {
        scope.withFrame(StockTilesScope.Frame(owner, key)) {
            assertFalse(scope.take(Any(), key))
            assertFalse(scope.take(owner, key + 1))
            assertFalse(scope.take(null, key))
            assertTrue(scope.take(owner, key))
        }
    }

    @Test fun equalButDifferentObjectsCannotMatch() {
        val a = listOf(1)
        val b = listOf(1)
        assertEquals(a, b)
        scope.withFrame(StockTilesScope.Frame(a, key)) {
            assertFalse(scope.take(b, key))
            assertTrue(scope.take(a, key))
        }
    }

    @Test fun excludedNestedEditorMasksOuterScope() {
        scope.withFrame(StockTilesScope.Frame(owner, key)) {
            scope.withFrame(null) { assertFalse(scope.take(owner, key)) }
            assertTrue(scope.take(owner, key))
        }
    }

    @Test fun nestedEditorDoesNotConsumeOuterScope() {
        val nested = Any()
        scope.withFrame(StockTilesScope.Frame(owner, key)) {
            scope.withFrame(StockTilesScope.Frame(nested, key)) {
                assertFalse(scope.take(owner, key))
                assertTrue(scope.take(nested, key))
            }
            assertTrue(scope.take(owner, key))
        }
    }

    @Test fun hostExceptionRestoresOuterScopeAndThenCleansUp() {
        scope.withFrame(StockTilesScope.Frame(owner, key)) {
            runCatching { scope.withFrame(null) { error("host failure") } }
            assertTrue(scope.take(owner, key))
        }
        assertFalse(scope.take(owner, key))
    }

    @Test fun otherThreadCannotInheritUiScope() {
        var observed = true
        scope.withFrame(StockTilesScope.Frame(owner, key)) {
            Thread { observed = scope.take(owner, key) }.apply { start(); join() }
            assertFalse(observed)
            assertTrue(scope.take(owner, key))
        }
    }
}
