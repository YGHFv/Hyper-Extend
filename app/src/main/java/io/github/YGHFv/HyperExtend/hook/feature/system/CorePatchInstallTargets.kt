/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.system

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 权限声明校验与安装调用链的精确结构，避免仅按参数数量误挂重载。 */
internal object CorePatchInstallTargets {
    const val HELPER = "com.android.server.pm.InstallPackageHelper"
    const val PARSED = "com.android.internal.pm.parsing.pkg.ParsedPackage"
    const val REQUEST = "com.android.server.pm.InstallRequest"

    data class PermissionDeclaration(
        val match: Method,
        val packageName: Method,
        val callers: List<Method>,
    )

    fun resolve(loader: ClassLoader): PermissionDeclaration = resolve(
        Class.forName(HELPER, false, loader),
        Class.forName(PARSED, false, loader),
        Class.forName(REQUEST, false, loader),
    )

    internal fun resolve(helper: Class<*>, parsed: Class<*>, request: Class<*>): PermissionDeclaration {
        fun exact(owner: Class<*>, name: String, returns: Class<*>, vararg parameters: Class<*>): Method =
            owner.getDeclaredMethod(name, *parameters).apply {
                require(returnType == returns && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
        val match = exact(helper, "doesSignatureMatchForPermissions", Boolean::class.javaPrimitiveType!!,
            String::class.java, parsed, Int::class.javaPrimitiveType!!)
        // getPackageName 定义在父接口，getDeclaredMethod 或只遍历父类会漏掉它。
        val packageName = parsed.getMethod("getPackageName").apply {
            require(returnType == String::class.java && !Modifier.isStatic(modifiers))
            isAccessible = true
        }
        val callers = listOf(
            exact(helper, "preparePackage", Void.TYPE, request),
            exact(helper, "prepareInstallPackages", Boolean::class.javaPrimitiveType!!, List::class.java),
        )
        return PermissionDeclaration(match, packageName, callers)
    }
}
