/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 */

package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.Reflect

/** OS4 has an inlined asStateFlow; construct its host-owned read-only wrapper directly. */
internal class StateFlowFactory(private val loader: ClassLoader) {

    private val factoryClass: Class<*>? =
        Reflect.loadClass(loader, "kotlinx.coroutines.flow.StateFlowKt")

    private val mutableFactory = factoryClass?.let {
        Reflect.firstMethod(it, "MutableStateFlow") { method -> method.parameterCount == 1 }
    }

    private val readonlyConstructor = Reflect.loadClass(loader, "kotlinx.coroutines.flow.ReadonlyStateFlow")
        ?.declaredConstructors?.firstOrNull {
            it.parameterTypes.map { type -> type.name } == listOf("kotlinx.coroutines.flow.MutableStateFlow")
        }?.also { it.isAccessible = true }

    val isAvailable: Boolean get() = mutableFactory != null && readonlyConstructor != null

    /**
     * 造一条只读常量流。
     *
     * 返回 null 表示宿主里拿不到 kotlinx（不是这种形态的系统界面），调用方据此安静跳过 ——
     * 猜一个替代做法只会把不确定变成故障。
     */
    fun constant(value: Any?): Any? = mutable(value)?.readonly

    fun mutable(value: Any?): HostStateFlow? {
        val mutable = mutableFactory ?: return null
        val readonly = readonlyConstructor ?: return null
        return Reflect.attempt {
            mutable.isAccessible = true
            val state = mutable.invoke(null, value) ?: return@attempt null
            val setter = state.javaClass.getMethod("setValue", Any::class.java)
            HostStateFlow(readonly.newInstance(state), state, setter)
        }
    }
}

internal class HostStateFlow(
    val readonly: Any,
    private val state: Any,
    private val setter: java.lang.reflect.Method,
) {
    fun set(value: Any): Boolean = Reflect.attempt {
        setter.invoke(state, value)
        true
    } == true
}
