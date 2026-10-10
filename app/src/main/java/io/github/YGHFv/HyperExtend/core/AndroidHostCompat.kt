/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build

internal val PackageInfo.compatibleVersionCode: Long
    get() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }

// A non-display Context throws on modern Android; unknown displays must fail native.
internal fun Context.hostDisplayIdOrNull(): Int? =
    if (Build.VERSION.SDK_INT >= 30) runCatching { display.displayId }.getOrNull() else null
