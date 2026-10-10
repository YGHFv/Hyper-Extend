/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Per-bind decorators can be stacked in either hook order without mutating a shared VM. */
internal object MobileViewModelFacade {
    private class Handler(val delegate: Any, val overrides: Map<String, Any>) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                else -> "HyperExtend mobile view-model facade"
            }
            if (method.parameterCount == 0 && method.name in overrides) return overrides[method.name]
            return try { method.invoke(delegate, *(args ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }

    fun create(loader: ClassLoader, contract: Class<*>, delegate: Any, overrides: Map<String, Any>): Any =
        Proxy.newProxyInstance(loader, arrayOf(contract), Handler(delegate, overrides))

    fun original(value: Any): Any {
        var current = value
        while (Proxy.isProxyClass(current.javaClass)) {
            val handler = Proxy.getInvocationHandler(current) as? Handler ?: break
            current = handler.delegate
        }
        return current
    }
}
