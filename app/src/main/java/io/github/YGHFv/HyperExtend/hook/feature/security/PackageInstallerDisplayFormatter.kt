/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.security

import java.util.Locale

/** 安装器扩展信息只做纯格式化，便于在 JVM 单测里验证边界。 */
internal object PackageInstallerDisplayFormatter {
    fun bytes(value: Long): String {
        val safe = value.coerceAtLeast(0)
        return when {
            safe >= 1L shl 30 -> format(safe.toDouble() / (1L shl 30), "GB")
            safe >= 1L shl 20 -> format(safe.toDouble() / (1L shl 20), "MB")
            safe >= 1L shl 10 -> format(safe.toDouble() / (1L shl 10), "KB")
            else -> "$safe B"
        }
    }

    fun details(
        packageName: String,
        versionName: String,
        versionCode: Int,
        minSdk: Int?,
        targetSdk: Int?,
        size: String,
        installedVersionName: String,
    ): String {
        val installed = installedVersionName.trim()
        val version = if (installed.isNotEmpty() && installed != versionName) {
            "$installed -> $versionName"
        } else {
            versionName
        }
        val sdk = when {
            minSdk != null && targetSdk != null -> "$minSdk-$targetSdk"
            targetSdk != null -> targetSdk.toString()
            minSdk != null -> minSdk.toString()
            else -> "未知"
        }
        return buildString {
            append("包名：").append(packageName.ifBlank { "未知" })
            append('\n').append("版本：").append(version.ifBlank { "未知" })
            append(" (").append(versionCode).append(')')
            append('\n').append("SDK：").append(sdk)
            append('\n').append("大小：").append(size.ifBlank { "未知" })
        }
    }

    private fun format(value: Double, unit: String): String =
        String.format(Locale.US, "%.2f %s", value, unit)
}
