/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import java.net.URLClassLoader
import javax.tools.ToolProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StateFlowFactoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun usesHostClassesWithoutAsStateFlowAndKeepsOneUpdatableWrapper() {
        // An isolated loader with OS4's reduced API, not the module's kotlinx dependency.
        val sources = mapOf(
            "MutableStateFlow" to "public interface MutableStateFlow { Object getValue(); void setValue(Object v); }",
            "StateFlowImpl" to """
                public class StateFlowImpl implements MutableStateFlow {
                    private Object value;
                    public StateFlowImpl(Object v) { value = v; }
                    public Object getValue() { return value; }
                    public void setValue(Object v) { value = v; }
                }
            """,
            "StateFlowKt" to """
                public class StateFlowKt {
                    public static StateFlowImpl MutableStateFlow(Object v) { return new StateFlowImpl(v); }
                }
            """,
            "ReadonlyStateFlow" to """
                public class ReadonlyStateFlow {
                    private final MutableStateFlow delegate;
                    public ReadonlyStateFlow(MutableStateFlow d) { delegate = d; }
                    public Object getValue() { return delegate.getValue(); }
                }
            """,
        )
        val root = temp.newFolder()
        val files = sources.map { (name, body) ->
            root.resolve("$name.java").apply { writeText("package kotlinx.coroutines.flow; $body") }
        }
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull("Tests require a JDK", compiler)
        assertEquals(0, compiler.run(null, null, null, "-d", root.path, *files.map { it.path }.toTypedArray()))
        URLClassLoader(arrayOf(root.toURI().toURL()), null).use { host ->
            val factory = StateFlowFactory(host)
            assertTrue(factory.isAvailable)
            val relay = factory.mutable(false)!!
            val wrapper = relay.readonly
            val getter = wrapper.javaClass.getMethod("getValue")
            assertSame(host, wrapper.javaClass.classLoader)
            assertEquals(false, getter.invoke(wrapper))
            assertTrue(relay.set(true))
            assertSame(wrapper, relay.readonly)
            assertEquals(true, getter.invoke(wrapper))
            assertTrue(relay.set(false))
            assertEquals(false, getter.invoke(wrapper))
            assertEquals(0, getter.invoke(factory.constant(0)))
        }
    }
}
