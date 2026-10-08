/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.mishare

import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object MiShareTargets {
    internal data class Shape(
        val allocatorClass: String,
        val allocatorMethod: String,
        val receiverClass: String,
        val receiverParameters: List<String>,
    )

    internal data class Match(val allocator: Method, val receiver: Method)

    // Observed host layouts. A version number alone neither accepts nor rejects a target.
    private val shapes = listOf(
        Shape("n1.f", "k", "r2.b", listOf("k2.h\$a", "k2.g")),
        Shape("e1.i", "m", "T1.b", emptyList()),
    )

    fun locate(load: (String) -> Class<*>?, candidates: List<Shape> = shapes): Match? =
        candidates.mapNotNull { shape ->
            try {
                val allocator = load(shape.allocatorClass)?.declaredMethods?.singleOrNull {
                    it.name == shape.allocatorMethod && Modifier.isStatic(it.modifiers) &&
                        Modifier.isPublic(it.modifiers) && it.returnType == String::class.java &&
                        it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
                } ?: return@mapNotNull null
                val receiver = load(shape.receiverClass)?.declaredMethods?.singleOrNull {
                    it.name == "d" && !Modifier.isStatic(it.modifiers) && Modifier.isPrivate(it.modifiers) &&
                        it.returnType == Void.TYPE &&
                        it.parameterTypes.map { type -> type.name } == shape.receiverParameters
                } ?: return@mapNotNull null
                Match(allocator, receiver)
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: LinkageError) {
                null
            } catch (_: SecurityException) {
                null
            }
        }.singleOrNull()
}
