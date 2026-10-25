/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.tsmclient

import android.os.Bundle
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 小米智能卡刷卡页的精确生命周期靶点。 */
internal data class TsmAutoNfcTarget(
    val name: String,
    val parameters: List<String>,
    val returns: String = "void",
)

internal object TsmAutoNfcTargets {
    const val ACTIVITY = "com.miui.tsmclient.ui.quick.DoubleClickActivity"

    val onCreate = TsmAutoNfcTarget("onCreate", listOf("android.os.Bundle"))
    val onDestroy = TsmAutoNfcTarget("onDestroy", emptyList())
    val all = listOf(onCreate, onDestroy)

    fun resolve(owner: Class<*>, target: TsmAutoNfcTarget): Method? =
        Reflect.declaredMethodsIncludingInherited(owner).firstOrNull { matches(it, target) }

    internal fun matches(method: Method, target: TsmAutoNfcTarget): Boolean =
        method.name == target.name &&
            method.parameterTypes.contentEquals(target.parameters.map(::typeOf).toTypedArray()) &&
            method.returnType == typeOf(target.returns) &&
            !Modifier.isStatic(method.modifiers)

    private fun typeOf(name: String): Class<*> = when (name) {
        "void" -> Void.TYPE
        "android.os.Bundle" -> Bundle::class.java
        else -> error("unsupported TSM AutoNfc target type: $name")
    }
}
