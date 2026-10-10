/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

internal object SafeModeCallerPolicy {
    fun allowed(scope: String, process: String, uid: Int, packages: Set<String>): Boolean =
        if (scope == "system_server") uid == 1000 else uid >= 1000 && process in packages
}
