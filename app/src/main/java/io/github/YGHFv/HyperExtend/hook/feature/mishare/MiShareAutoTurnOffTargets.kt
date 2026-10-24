/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.mishare

import android.content.Context
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 定位互传「临时开启到点自动关闭」的定时器建立方法。
 *
 * 宿主把这件事做成一个私有小类：字段恰好是一个 `MiShareService`、一个 `Context`、
 * 一个 int 延时（观察值 600000ms）、一个定时器对象与一个 boolean 状态；`i()V` 是
 * **唯一**建立并启动该定时器的入口（`f()` 在「临时开启」时调它，`d()`→`j()` 才是到点关停）。
 * 只有 `i()` 体内带有 `"stopAdvertAllDelay"`，所以它正是上游 NoAutoTurnOff 用字符串找到的那个方法。
 *
 * ⚠️ **只认类名是不够的**。两条观察到的宿主布局里它叫不同名字（OS4 的 5.4.2 是 `s1.b`，
 * Android 14 接收端的 3.4.1 是 `b2.c`），而同一份 APK 里那两个名字**都另有用途**：
 * 3.4.1 的 `s1.b` 是蓝牙近场扫描、5.4.2 的 `b2.c` 是统计参数容器。所以逐个形状必须
 * 校验「唯一一个 (MiShareService) 构造器 + 四种字段类型各恰一个 + 私有 无参 void 靶点」，
 * 不合就跳过，绝不猜。版本号既不接受也不拒绝靶点。
 */
internal object MiShareAutoTurnOffTargets {
    internal data class Shape(
        val ownerClass: String,
        val armMethod: String,
        val serviceClass: String = "com.miui.mishare.connectivity.MiShareService",
    )

    // Observed host layouts. A version number alone neither accepts nor rejects a target.
    private val shapes = listOf(
        Shape("s1.b", "i"),  // 5.4.2_20260923_1315 · OS4 侧
        Shape("b2.c", "i"),  // 3.4.1 · Android 14 接收端
    )

    fun locate(load: (String) -> Class<*>?, candidates: List<Shape> = shapes): Method? =
        candidates.mapNotNull { match(load, it) }.singleOrNull()

    private fun match(load: (String) -> Class<*>?, shape: Shape): Method? = try {
        val owner = load(shape.ownerClass)
        val service = load(shape.serviceClass)
        if (owner == null || service == null) null
        else if (!hasServiceConstructor(owner, service)) null
        else if (!hasObservedFieldLayout(owner, service)) null
        else owner.declaredMethods.singleOrNull { isArmMethod(it, shape.armMethod) }
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun hasServiceConstructor(owner: Class<*>, service: Class<*>): Boolean =
        owner.declaredConstructors.count { it.parameterTypes.contentEquals(arrayOf(service)) } == 1

    private fun hasObservedFieldLayout(owner: Class<*>, service: Class<*>): Boolean {
        // 只看实例字段：静态字段是编译器/混淆器补进去的标记（Kotlin 2.x 的 `$stable` 就是典型），
        // 与「这个类持有哪几样东西」无关，混进来会把形状判定直接带偏。
        val declared = owner.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.type }
        val observed = listOf(
            service,
            Context::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        return observed.all { expected -> declared.count { it == expected } == 1 }
    }

    private fun isArmMethod(method: Method, name: String): Boolean =
        method.name == name && Modifier.isPrivate(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 0 && method.returnType == Void.TYPE
}
