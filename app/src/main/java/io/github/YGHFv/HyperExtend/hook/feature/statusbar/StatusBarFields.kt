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
import io.github.YGHFv.HyperExtend.hook.UnsafeAccess

/**
 * 状态栏几处 hook 共用的字段读写。
 *
 * ## 为什么「按类型绕过去」这一条是必需的
 *
 * 新版系统界面里若干个可见性字段的**声明类型不是 `StateFlow`，而是某个 `combine(...)`
 * 表达式生成的匿名类**（`FlowKt__ZipKt$combine$$inlined$combineUnsafe$...`）。
 * 我们用 `StateFlowKt.MutableStateFlow()` 造出来的流实现的是 `StateFlow` 接口 ——
 * 语义上完全兼容，但 `Field.set` 会按**声明类型**做一次可赋值性检查，直接抛
 * `IllegalArgumentException`。
 *
 * 于是这类字段只能走 `Unsafe` 写内存的通道（绕过访问检查与类型检查）。
 * 这不是「图省事」：字段的**读**方只把它当 `Flow` 用，多态调用走的是实例自己的方法表，
 * 写入的具体类型是什么对它没有影响。
 */
internal object StatusBarFields {

    /**
     * 往一个字段里写值，允许写入值的类型与字段声明类型不完全一致。
     *
     * 先试普通反射（类型对得上时它更快、也更安全），失败再走 `Unsafe`。
     */
    fun write(target: Any, name: String, value: Any?): Boolean {
        val field = Reflect.findField(target.javaClass, name) ?: return false
        field.isAccessible = true
        val direct = try {
            field.set(target, value)
            true
        } catch (_: Throwable) {
            false
        }
        if (direct) return true
        return UnsafeAccess.putObjectField(field, target, value)
    }

    /** 读一个字段的当前值；字段不存在返回 null。 */
    fun read(target: Any, name: String): Any? = Reflect.readField(target, name)

    /**
     * 「已经是我们换上去的那条流了」就不必再写一遍。
     *
     * 宿主会为同一个视图模型反复走绑定流程，每次都重新赋值等于把界面层已经收集到的
     * 那条流换掉 —— 表现是「开关开了，图标闪一下又回来」。用恒等比较就能判掉。
     */
    fun alreadyReplaced(target: Any, name: String, replacement: Any?): Boolean =
        replacement != null && read(target, name) === replacement
}
