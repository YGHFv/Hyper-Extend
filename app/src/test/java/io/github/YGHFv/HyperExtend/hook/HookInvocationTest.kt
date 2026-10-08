/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook

import org.junit.Assert.*
import org.junit.Test

class HookInvocationTest {
    @Test fun failureBeforeOriginalFallsBackOnce() {
        var calls = 0
        val result = HookInvocation().protect({ error("module") }, { calls++; 42 }, {})
        assertEquals(42, result)
        assertEquals(1, calls)
    }
    @Test fun failingPostprocessorPreservesHostResultWithoutReplay() {
        val invocation = HookInvocation()
        var calls = 0
        val host = { calls++; "host" }
        assertEquals("host", invocation.protect({ invocation.proceed(host); error("post") }, host, {}))
        assertEquals(1, calls)
    }
    @Test fun nullVoidResultsAreAlsoCached() {
        val invocation = HookInvocation()
        var calls = 0
        val host = { calls++; null }
        assertNull(invocation.protect({ invocation.proceed(host); error("post") }, host, {}))
        assertEquals(1, calls)
    }
    @Test fun originalExceptionIsRethrownByIdentityWithoutReplay() {
        val invocation = HookInvocation()
        val failure = IllegalStateException("host")
        var calls = 0
        val host = { calls++; throw failure }
        val caught = runCatching { invocation.protect({ invocation.proceed(host) }, host, {}) }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, calls)
    }
    @Test fun replacementDoesNotCallOriginal() {
        var calls = 0
        assertEquals(false, HookInvocation().protect({ false }, { calls++; true }, {}))
        assertEquals(0, calls)
    }
    @Test fun reportingFailureCannotPreventFallback() {
        assertEquals(5, HookInvocation().protect({ error("module") }, { 5 }, { error("log") }))
    }
}
