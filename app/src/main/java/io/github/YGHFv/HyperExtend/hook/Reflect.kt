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

package io.github.YGHFv.HyperExtend.hook

import android.util.Log
import io.github.YGHFv.HyperExtend.core.ModuleLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 宿主反射工具。
 *
 * ## 为什么全篇都是反射
 *
 * 模块只对 libxposed api 编译（`compileOnly`），一切宿主类型（SystemUI 的
 * `ThemeOverlayController`、设置的 `DefaultCombinedPicker`、system_server 的
 * `RequestSession`……）在编译期都是不存在的。直接 `import` 会在运行时
 * `NoClassDefFoundError`，而且不同 HyperOS 版本之间这些类还会改名/改包。
 * 所以：
 *
 * - 类名一律按**候选列表**给（同一个类在不同 HyperOS 版本下的几种包名），取第一个能加载的；
 * - 方法一律按**名字 + 参数个数/参数类型**找，找不到就返回 null，由调用方决定是跳过还是报错；
 * - 所有取值/调用都在 [safe] 里包一层，异常只记日志 —— **宿主进程里绝不能因为模块抛异常而崩**。
 *
 * ## 关于 classloader 的选择
 *
 * 一律用调用方传进来的宿主 classloader（`PackageReadyParam.getClassLoader()`），
 * 不要用 `Class.forName(name)` 的单参重载 —— 那个会用模块自己的 classloader，
 * 而模块 classloader 解析不到宿主类型，结果是「明明类就在那儿却 ClassNotFound」。
 */
internal object Reflect {

    /** 按候选名依次尝试加载，返回第一个成功的。全失败返回 null（不抛）。 */
    fun loadClass(loader: ClassLoader, vararg candidates: String): Class<*>? {
        for (name in candidates) {
            val clazz = safe("loadClass($name)") {
                Class.forName(name, false, loader)
            }
            if (clazz != null) return clazz
        }
        return null
    }

    /**
     * 按名字和参数类型找方法，找不到再放宽到「名字相同 + 参数个数相同」。
     *
     * 放宽这一步是必需的：宿主类型之间常有 `Context` / `ContextWrapper`、
     * `Notification` / `ExpandedNotification` 这类父子关系，逐字比对参数类型会漏。
     * 只在精确匹配失败时才放宽，避免一上来就撞上重载。
     */
    fun findMethod(
        clazz: Class<*>,
        name: String,
        vararg params: Class<*>,
    ): Method? {
        val declared = declaredMethodsIncludingInherited(clazz)
        declared.firstOrNull { it.name == name && it.parameterTypes.contentEquals(params) }
            ?.let { return it }
        declared.firstOrNull { it.name == name && it.parameterCount == params.size }
            ?.let { return it }
        return null
    }

    /** 找**全部**同名同参数个数的方法（重载都算）。用于「这个类有好几个重载都得挂」的场景。 */
    fun findMethods(clazz: Class<*>, name: String, paramCount: Int? = null): List<Method> =
        declaredMethodsIncludingInherited(clazz).filter {
            it.name == name && (paramCount == null || it.parameterCount == paramCount)
        }

    /** 按名字找方法，额外用一个谓词过滤（用来区分同名重载）。 */
    fun firstMethod(clazz: Class<*>, name: String, predicate: (Method) -> Boolean): Method? =
        declaredMethodsIncludingInherited(clazz).firstOrNull { it.name == name && predicate(it) }

    fun declaredMethodsIncludingInherited(clazz: Class<*>): List<Method> {
        val result = LinkedHashMap<String, Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                // 子类同名同参的方法后覆盖先到的父类版本：key 里带参数类型，
                // 避免把重载当成同一个而丢掉。
                val key = method.name + "/" + method.parameterTypes.joinToString(",") { it.name }
                result.putIfAbsent(key, method)
            }
            current = current.superclass
        }
        return result.values.toList()
    }

    /**
     * 按类型找字段。
     *
     * 宿主里这些字段的**名字**在各版本间不稳定（`mCurrentColors`、`mWallpaperManager`
     * 算稳的，`mUserTracker` 之类则不一定），所以「按名字找」之外还要能「按类型找」。
     */
    fun findFieldByType(clazz: Class<*>, type: Class<*>): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { type.isAssignableFrom(it.type) }
                ?.let { it.isAccessible = true; return it }
            current = current.superclass
        }
        return null
    }

    /** 按名字找字段（含继承链）。 */
    fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    fun readField(target: Any, name: String): Any? =
        safe("read $name") { findField(target.javaClass, name)?.get(target) }

    fun writeField(target: Any, name: String, value: Any?): Boolean =
        safe("write $name") {
            findField(target.javaClass, name)?.let { field ->
                field.isAccessible = true
                field.set(target, value)
                true
            } ?: false
        } ?: false

    fun writeField(field: Field, target: Any?, value: Any?): Boolean =
        safe("write field ${field.name}") {
            field.isAccessible = true
            field.set(target, value)
            true
        } ?: false

    /** 调一个带参方法。参数按运行时类型匹配。 */
    fun callWith(target: Any?, name: String, vararg args: Any?): Any? =
        safe("call $name") {
            val clazz = target!!.javaClass
            val method = findMethods(clazz, name, args.size).firstOrNull() ?: return@safe null
            method.isAccessible = true
            method.invoke(target, *args)
        }

    /**
     * 取静态字段句柄（`miui.os.Build.IS_INTERNATIONAL_BUILD` 之类）。
     *
     * 只取句柄、不改值：真正写它要走 `UnsafeAccess` —— `static final` 用反射 `set` 会被 ART 拒绝
     * （要么抛 `IllegalAccessException`，要么报告成功但值不变，后者更难查）。
     */
    fun staticFieldOrNull(clazz: Class<*>, name: String): Field? =
        safe("static field $name") {
            clazz.getDeclaredField(name).also { it.isAccessible = true }
        }

    /**
     * 统一异常出口。
     *
     * 写成内联泛型是为了让调用点保持表达式风格（`safe("x") { ... } ?: fallback`），
     * 同时保证**任何** throwable 都不会冒泡出去 —— 这是宿主进程里的硬要求。
     * `NoClassDefFoundError` 这类 Error 也一并吞掉：宿主类加载器缺类时抛的正是它。
     */
    inline fun <T> safe(what: String, block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        ModuleLog.error("reflect failed: $what", t)
        null
    }

    /** 只记 debug 级失败、不打扰日志的场景（例如「按候选名依次尝试」里的正常失败）。 */
    inline fun <T> attempt(block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        Log.d(ModuleLog.TAG, "attempt failed: ${t.javaClass.simpleName}", t)
        null
    }
}
