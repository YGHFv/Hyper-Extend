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

package io.github.YGHFv.HyperExtend.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 查宿主应用在不在。
 *
 * 界面要如实回答「这个作用域里的应用装了没有」：没装的应用点进去永远是空的，
 * 用户会以为是模块坏了，而不是「这台机器上本来就没有这个应用」。
 *
 * 用 [PackageManager.getApplicationInfo] 而不是 `getLaunchIntentForPackage`：
 * 后者对「有包但没有启动图标」的应用（系统界面就是）返回 null，
 * 会把装得好好的系统界面报成未安装。
 */
object HostApps {

    fun isInstalled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (t: Throwable) {
            // 个别 ROM 在包管理器被限制时抛别的异常，一律按「查不到」处理：
            // 这个函数只影响一句提示文案，不值得让它把界面打断。
            ModuleLog.warn("query package failed: $packageName (${t.javaClass.simpleName})")
            false
        }
    }
}
