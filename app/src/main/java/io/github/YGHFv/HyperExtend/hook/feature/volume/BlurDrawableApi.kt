/* Copyright (C) 2026 zhhhyyyyyy (HyperVolumeANC)
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: Apache-2.0 */
package io.github.YGHFv.HyperExtend.hook.feature.volume

/** Vendor builds expose either one/four radii, with int or float parameters. */
internal object BlurDrawableApi {
    fun configure(drawable: Any, radius: Float, corner: Float, color: Int) {
        numeric(drawable, "setBlurRadius", radius, listOf(1))
        numeric(drawable, "setCornerRadius", corner, listOf(4, 1))
        drawable.javaClass.getMethod("setColor", Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(drawable, color)
    }

    private fun numeric(target: Any, name: String, value: Float, counts: List<Int>) {
        val method = counts.firstNotNullOfOrNull { count ->
            target.javaClass.methods.firstOrNull { method ->
                method.name == name && method.parameterCount == count && method.parameterTypes.all {
                    it == Float::class.javaPrimitiveType || it == Int::class.javaPrimitiveType
                }
            }
        } ?: throw NoSuchMethodException("${target.javaClass.name}.$name")
        method.isAccessible = true
        val args = method.parameterTypes.map { type ->
            if (type == Int::class.javaPrimitiveType) value.toInt() as Any else value as Any
        }.toTypedArray()
        method.invoke(target, *args)
    }
}
