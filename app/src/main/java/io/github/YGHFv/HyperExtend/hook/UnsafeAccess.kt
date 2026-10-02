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
 *
 * ---------------------------------------------------------------------------
 * 功能来源：修复澎湃系统通行密钥（Howard20181，GPL-3.0）的 `UnsafeUtils`。
 * 需求相同（要改 `static final` 字段，普通反射办不到），实现按本模块的风格重写。
 */

package io.github.YGHFv.HyperExtend.hook

import io.github.YGHFv.HyperExtend.core.ModuleLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 通过 `sun.misc.Unsafe` 绕过 final 限制写字段。
 *
 * ## 为什么普通反射不够
 *
 * 通行密钥修复要临时把 `miui.os.Build.IS_INTERNATIONAL_BUILD` 掰成 `true`，
 * 而它是 `public static final boolean`。`Field.setAccessible(true)` + `Field.set(...)`
 * 在 Android 9 之后对 **static final** 字段一律抛 `IllegalAccessException`
 * （`Can not set static final boolean field`），连「先反射改掉 modifiers 再去掉 FINAL 位」
 * 这条老路也被 `Field.modifiers` 的访问检查堵住了。
 *
 * `Unsafe` 走的是另一条通道：不经过字段访问检查，直接按「静态字段基址 + 偏移」写内存。
 * 它是 Android 平台自带的类（libcore 里就有），不需要额外依赖。
 *
 * ## 用它的纪律
 *
 * 只用于**临时**改写（`IS_INTERNATIONAL_BUILD` 是「只在被 hook 的那一次调用期间为 true，
 * 返回前改回去」）。把一个 static final 永久改掉，等于在 SystemUI/设置进程里留下一个
 * 与代码常量不一致的状态，其他模块和系统自身都会读到它 —— 那种故障极难归因。
 */
internal object UnsafeAccess {

    /** `sun.misc.Unsafe.theUnsafe`。取到一次就够，取不到说明这台设备/版本不支持。 */
    private val unsafe: Any? by lazy {
        Reflect.attempt {
            val clazz = Class.forName("sun.misc.Unsafe")
            clazz.getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null)
        }.also {
            if (it == null) ModuleLog.warn("Unsafe unavailable — static-final patches disabled")
        }
    }

    private val putBooleanMethod: Method? by lazy { method("putBoolean", Boolean::class.javaPrimitiveType!!) }
    private val putObjectMethod: Method? by lazy { method("putObject", Any::class.java) }
    private val staticFieldBaseMethod: Method? by lazy { method("staticFieldBase") }
    private val staticFieldOffsetMethod: Method? by lazy { method("staticFieldOffset") }
    private val objectFieldOffsetMethod: Method? by lazy { method("objectFieldOffset") }

    val isAvailable: Boolean get() = unsafe != null

    /** 写一个 `static` 字段（含 `static final`）。 */
    fun putStaticBoolean(field: Field, value: Boolean): Boolean {
        val instance = unsafe ?: return false
        val put = putBooleanMethod ?: return false
        val base = staticFieldBaseMethod ?: return false
        val offset = staticFieldOffsetMethod ?: return false
        return Reflect.attempt {
            put.invoke(instance, base.invoke(instance, field), offset.invoke(instance, field) as Long, value)
            true
        } ?: false
    }

    /** 写一个实例字段（含 `final`）。`mHybridService` 就是这种。 */
    fun putObjectField(field: Field, target: Any?, value: Any?): Boolean {
        val instance = unsafe ?: return false
        val put = putObjectMethod ?: return false
        val offset = objectFieldOffsetMethod ?: return false
        return Reflect.attempt {
            put.invoke(instance, target, offset.invoke(instance, field) as Long, value)
            true
        } ?: false
    }

    /** 按名字找 `Unsafe` 上的方法，参数类型从简（同名方法在 Unsafe 上一般只有一个形状）。 */
    private fun method(name: String, extraParam: Class<*>? = null): Method? = Reflect.attempt {
        val clazz = Class.forName("sun.misc.Unsafe")
        val candidates = clazz.methods.filter { it.name == name }
        val picked = if (extraParam == null) {
            candidates.firstOrNull { it.parameterCount == 1 }
        } else {
            candidates.firstOrNull { it.parameterCount == 3 && it.parameterTypes[2] == extraParam }
        }
        picked?.also { it.isAccessible = true }
    }
}
