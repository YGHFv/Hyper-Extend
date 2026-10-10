/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import java.lang.reflect.Proxy

/** Use the host controller so battery, thermal and camera-availability policy stays native. */
internal class LockscreenFlashlightBridge(loader: ClassLoader) {
    private val methods = LockscreenFlashlightTargets.queries.associateWith {
        requireNotNull(NotificationHooks.resolve(loader, it))
    }
    private val stubType = Class.forName("miui.stub.MiuiStub", false, loader)
    private val stub = stubType.getDeclaredField("INSTANCE").get(null)
    private val provider = stubType.getDeclaredField("mSysUIProvider")
    private val lazy = provider.type.getDeclaredField("mFlashlightController")
    private val lazyGet = methods.getValue(LockscreenFlashlightTargets.queries.last())
    private val listener = Class.forName(LockscreenFlashlightTargets.LISTENER, false, loader).also {
        require(it.isInterface)
        it.getDeclaredMethod("onFlashlightChanged", Boolean::class.javaPrimitiveType)
        it.getDeclaredMethod("onFlashlightAvailabilityChanged", Boolean::class.javaPrimitiveType)
        it.getDeclaredMethod("onFlashlightError")
    }

    fun controller(): Any? {
        val source = provider.get(stub) ?: return null
        val value = lazy.get(source) ?: return null
        return lazyGet.invoke(value)?.takeIf { methods.getValue(LockscreenFlashlightTargets.enabled).declaringClass.isInstance(it) }
    }
    fun enabled(controller: Any) = methods.getValue(LockscreenFlashlightTargets.enabled).invoke(controller) == true
    fun available(controller: Any) = methods.getValue(LockscreenFlashlightTargets.available).invoke(controller) == true
    fun toggle(controller: Any): Boolean {
        if (!available(controller)) return false
        methods.getValue(LockscreenFlashlightTargets.set).invoke(controller, !enabled(controller))
        return true
    }
    fun callback(changed: () -> Unit): Any = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "HyperExtendFlashlightListener"
            else -> { changed(); null }
        }
    }
    fun add(controller: Any, callback: Any) { methods.getValue(LockscreenFlashlightTargets.add).invoke(controller, callback) }
    fun remove(controller: Any, callback: Any) { methods.getValue(LockscreenFlashlightTargets.remove).invoke(controller, callback) }
}
