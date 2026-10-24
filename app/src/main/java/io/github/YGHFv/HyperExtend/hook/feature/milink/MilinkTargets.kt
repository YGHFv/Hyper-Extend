/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.milink

import android.content.Context
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * `com.milink.service` 缓存宿主的精确方法签名。
 *
 * 这里只收录 APK 中实际存在的成员。类名、参数与返回类型同时参与匹配，
 * 不接受只按方法名或参数个数的宽松匹配，避免在后续宿主版本里误挂到同义但不同用途的方法。
 */
internal data class MilinkTarget(
    val owner: String,
    val name: String,
    val parameters: List<String>,
    val returns: String,
    val isStatic: Boolean,
)

internal object MilinkTargets {
    private const val UNIVERSAL_CLIPBOARD_LYRA =
        "com.xiaomi.dist.universalclipboardservice.utils.LyraUtil"
    private const val FILE_SERVICE_LYRA = "com.xiaomi.dist.file.service.utils.LyraUtil"
    private const val CONTEXT_PATH = "com.hpplay.common.utils.ContextPath"

    private val universalClipboardRegionCheck = MilinkTarget(
        owner = UNIVERSAL_CLIPBOARD_LYRA,
        name = "isSameRegionWithLocal",
        parameters = listOf("android.content.Context", "java.lang.String"),
        returns = "boolean",
        isStatic = true,
    )
    private val universalClipboardRegion = MilinkTarget(
        owner = UNIVERSAL_CLIPBOARD_LYRA,
        name = "getDeviceRegion",
        parameters = listOf("android.content.Context", "java.lang.String"),
        returns = "java.lang.String",
        isStatic = true,
    )
    private val fileServiceRegionCheck = MilinkTarget(
        owner = FILE_SERVICE_LYRA,
        name = "isSameRegionWithLocal",
        parameters = listOf("android.content.Context", "java.lang.String"),
        returns = "boolean",
        isStatic = true,
    )
    private val fileServiceRegion = MilinkTarget(
        owner = FILE_SERVICE_LYRA,
        name = "getDeviceRegion",
        parameters = listOf("android.content.Context", "java.lang.String"),
        returns = "java.lang.String",
        isStatic = true,
    )
    private val hpplayMakeDir = MilinkTarget(
        owner = CONTEXT_PATH,
        name = "makeDir",
        parameters = listOf("[java.lang.String", "java.lang.String"),
        returns = "void",
        isStatic = false,
    )

    val regionTargets: List<MilinkTarget> = listOf(
        universalClipboardRegionCheck,
        universalClipboardRegion,
        fileServiceRegionCheck,
        fileServiceRegion,
    )

    val hpplayDirectoryTarget: MilinkTarget = hpplayMakeDir

    val all: List<MilinkTarget> = regionTargets + hpplayDirectoryTarget

    fun resolve(loader: ClassLoader, target: MilinkTarget): Method? {
        val clazz = Reflect.loadClass(loader, target.owner) ?: return null
        val parameterTypes = target.parameters.map(::typeOf).toTypedArray()
        val returnType = typeOf(target.returns)
        return Reflect.declaredMethodsIncludingInherited(clazz).firstOrNull { method ->
            method.name == target.name &&
                method.parameterTypes.contentEquals(parameterTypes) &&
                method.returnType == returnType &&
                Modifier.isStatic(method.modifiers) == target.isStatic
        }
    }

    private fun typeOf(name: String): Class<*> = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        "android.content.Context" -> Context::class.java
        "java.lang.String" -> String::class.java
        "[java.lang.String" -> Array<String>::class.java
        else -> error("unsupported Milink target type: $name")
    }
}
