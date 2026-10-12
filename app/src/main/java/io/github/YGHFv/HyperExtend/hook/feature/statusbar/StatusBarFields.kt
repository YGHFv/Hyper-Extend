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

/** Type-safe field utilities. Presentation adapters no longer replace concrete Flow fields. */
internal object StatusBarFields {
    fun write(target: Any, name: String, value: Any?): Boolean {
        val field = Reflect.findField(target.javaClass, name) ?: return false
        return runCatching {
            field.isAccessible = true
            field.set(target, value)
            true
        }.getOrDefault(false)
    }

    fun read(target: Any, name: String): Any? = Reflect.readField(target, name)

    fun alreadyReplaced(target: Any, name: String, replacement: Any?): Boolean =
        replacement != null && read(target, name) === replacement
}
