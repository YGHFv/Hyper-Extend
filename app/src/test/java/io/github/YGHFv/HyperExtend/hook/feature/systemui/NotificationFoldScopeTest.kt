/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class NotificationFoldScopeTest {
    private val scope = NotificationFoldScope<Any>()

    @Test fun onlyCurrentUnfoldedMenuNotificationIsSuppressed() {
        val notification = Any()
        assertFalse(scope.suppressMenuFor(notification))
        scope.inMenu(notification, false) {
            assertTrue(scope.suppressMenuFor(notification))
            assertFalse(scope.suppressMenuFor(Any()))
            assertFalse(scope.suppressMenuFor(null))
            assertFalse(scope.ignoreAutomatic)
        }
        assertFalse(scope.suppressMenuFor(notification))
    }

    @Test fun foldedAndUnknownStateRetainNativeMoveOut() {
        val notification = Any()
        for (folded in listOf(true, null)) scope.inMenu(notification, folded) {
            assertFalse(scope.suppressMenuFor(notification))
        }
        scope.inMenu(null, false) { assertFalse(scope.suppressMenuFor(null)) }
    }

    @Test fun nestedMenuRestoresPreviousIdentityEvenOnFailure() {
        val first = Any()
        val second = Any()
        scope.inMenu(first, false) {
            scope.inMenu(second, false) {
                assertTrue(scope.suppressMenuFor(second))
                assertFalse(scope.suppressMenuFor(first))
            }
            assertTrue(scope.suppressMenuFor(first))
            assertTrue(runCatching {
                scope.inMenu(second, true) {
                    assertFalse(scope.suppressMenuFor(first))
                    error("host failure")
                }
            }.isFailure)
            assertTrue(scope.suppressMenuFor(first))
        }
        assertFalse(scope.suppressMenuFor(first))
    }

    @Test fun automaticScopeRestoresAfterNestedHostFailure() {
        assertFalse(scope.ignoreAutomatic)
        scope.inAutomatic {
            assertTrue(scope.ignoreAutomatic)
            assertTrue(runCatching { scope.inAutomatic { error("host failure") } }.isFailure)
            assertTrue(scope.ignoreAutomatic)
        }
        assertFalse(scope.ignoreAutomatic)
        assertTrue(runCatching { scope.inAutomatic { error("host failure") } }.isFailure)
        assertFalse(scope.ignoreAutomatic)
    }

    @Test fun identityNotEqualityAndThreadIsolation() {
        val first = listOf("notification")
        val equal = listOf("notification")
        scope.inAutomatic {
            scope.inMenu(first, false) {
                assertFalse(scope.suppressMenuFor(equal))
                var otherAutomatic = true
                var otherMenu = true
                Thread {
                    otherAutomatic = scope.ignoreAutomatic
                    otherMenu = scope.suppressMenuFor(first)
                }.apply { start(); join() }
                assertFalse(otherAutomatic)
                assertFalse(otherMenu)
                assertTrue(scope.suppressMenuFor(first))
            }
        }
    }

    @Test fun automaticAndMenuContextsDoNotOverrideEachOther() {
        val notification = Any()
        scope.inAutomatic {
            scope.inMenu(notification, false) {
                assertTrue(scope.ignoreAutomatic)
                assertTrue(scope.suppressMenuFor(notification))
            }
            assertTrue(scope.ignoreAutomatic)
            assertFalse(scope.suppressMenuFor(notification))
        }
    }

    @Test fun normalResultAndHostExceptionAreNotReplayed() {
        val notification = Any()
        var calls = 0
        assertEquals(42, scope.inMenu(notification, false) { calls++; 42 })
        val failure = IllegalStateException("host failure")
        assertSame(failure, runCatching { scope.inMenu(notification, false) { calls++; throw failure } }.exceptionOrNull())
        assertEquals(2, calls)
        assertFalse(scope.suppressMenuFor(notification))
    }
}
