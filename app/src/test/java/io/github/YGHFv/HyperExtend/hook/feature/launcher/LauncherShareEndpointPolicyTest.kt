/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * system_server 转发模块文件名的纯函数合同。
 *
 * 本测试不创建 Android Intent、不访问 Provider。Intent 的组件、动作与授权标志
 * 由生产类字节码审计和 `test_launcher_share_apk_flow_audit.py` 的离线链路合同核对。
 */
class LauncherShareEndpointPolicyTest {

    @Test fun acceptsOnlyOneBoundedFileName() {
        assertEquals("com.example-1.apk", LauncherShareEndpoint.safeShareFileName("com.example-1.apk"))
        assertEquals("a b.apks", LauncherShareEndpoint.safeShareFileName("a b.apks"))
        for (name in listOf(
            null,
            "",
            "../escape.apk",
            "dir/file.apk",
            "dir\\file.apk",
            "a..b.apk",
            "x".repeat(256),
        )) {
            assertNull(name, LauncherShareEndpoint.safeShareFileName(name))
        }
    }
}
