/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import java.util.WeakHashMap
import org.junit.Assert.*
import org.junit.Test

class MobileViewModelFacadeTest {
    interface Vm { fun isVisible(): String; fun getType(): String; fun fail(): String }
    private val failure = IllegalStateException("host")
    private val native = object : Vm {
        override fun isVisible() = "native-visible"
        override fun getType() = "native-type"
        override fun fail(): String = throw failure
    }
    private fun facade(delegate: Vm, values: Map<String, Any>) =
        MobileViewModelFacade.create(Vm::class.java.classLoader!!, Vm::class.java, delegate, values) as Vm

    @Test fun eitherHookOrderPreservesBothDecorationsAndUnwrapsNative() {
        val visibility = mapOf("isVisible" to "dual-row")
        val type = mapOf("getType" to "separate")
        for (result in listOf(facade(facade(native, visibility), type), facade(facade(native, type), visibility))) {
            assertEquals("dual-row", result.isVisible())
            assertEquals("separate", result.getType())
            assertSame(native, MobileViewModelFacade.original(result))
        }
    }

    @Test fun identityEqualityAndWeakMapLookupNeverDelegateToHost() {
        val one = facade(native, emptyMap())
        val two = facade(native, emptyMap())
        assertTrue(one == one)
        assertFalse(one == two)
        assertFalse(one == native)
        assertEquals(System.identityHashCode(one), one.hashCode())
        val map = WeakHashMap<Vm, String>()
        map[one] = "one"
        map[two] = "two"
        assertEquals("one", map.remove(one))
        assertEquals("two", map[two])
    }

    @Test fun hostExceptionsAreUnwrappedAndMethodsStillRunOnOriginal() {
        val result = facade(native, emptyMap())
        assertEquals("native-type", result.getType())
        assertSame(failure, runCatching { result.fail() }.exceptionOrNull())
    }
}
