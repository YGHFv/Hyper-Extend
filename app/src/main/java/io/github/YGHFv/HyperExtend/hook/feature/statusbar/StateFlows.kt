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

/**
 * 用宿主自己的 `kotlinx.coroutines` 造一条「永远是同一个值」的状态流。
 *
 * ## 为什么不是模块自己 new 一个
 *
 * 新版系统界面的图标可见性、WIFI 标准、漫游标记全都改成了状态流：宿主在构造时建一条流，
 * 之后由界面层 `collect` 它。模块要「把某个值钉死」，就得往那个字段里换一条流 ——
 * 而这条流**必须和宿主用的是同一份 kotlinx**：宿主的收集方调的是它自己 classloader 里那个
 * `StateFlow` 接口，模块 classloader 里另有一份同名接口，两边不通用（`ClassCastException`）。
 *
 * 所以这里反射调用 `StateFlowKt.MutableStateFlow(value)` + `asStateFlow()`，
 * 造出来的对象天然属于宿主的类型体系。
 *
 * ## 为什么用只读流
 *
 * 宿主往这些字段上写的机会不多（基本只在构造里赋一次），而只读流能保证「没有人能再把值改掉」——
 * 这正是「始终隐藏」这类开关需要的语义。
 */
internal class StateFlowFactory(private val loader: ClassLoader) {

    private val factoryClass: Class<*>? =
        Reflect.loadClass(loader, "kotlinx.coroutines.flow.StateFlowKt")

    private val mutableFactory = factoryClass?.let {
        Reflect.firstMethod(it, "MutableStateFlow") { method -> method.parameterCount == 1 }
    }

    private val readonlyFactory = factoryClass?.let {
        Reflect.firstMethod(it, "asStateFlow") { method -> method.parameterCount == 1 }
    }

    val isAvailable: Boolean get() = mutableFactory != null && readonlyFactory != null

    /**
     * 造一条只读常量流。
     *
     * 返回 null 表示宿主里拿不到 kotlinx（不是这种形态的系统界面），调用方据此安静跳过 ——
     * 猜一个替代做法只会把不确定变成故障。
     */
    fun constant(value: Any?): Any? {
        val mutable = mutableFactory ?: return null
        val readonly = readonlyFactory ?: return null
        return Reflect.attempt { readonly.invoke(null, mutable.invoke(null, value)) }
    }
}
