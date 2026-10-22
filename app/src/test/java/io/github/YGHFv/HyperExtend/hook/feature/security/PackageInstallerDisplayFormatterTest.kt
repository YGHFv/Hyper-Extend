/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageInstallerDisplayFormatterTest {
    @Test fun fileSizeUsesReadableBinaryUnits() {
        assertEquals("0 B", PackageInstallerDisplayFormatter.bytes(0))
        assertEquals("1.00 KB", PackageInstallerDisplayFormatter.bytes(1024))
        assertEquals("1.50 MB", PackageInstallerDisplayFormatter.bytes(1024L * 1024 + 512 * 1024))
        assertEquals("2.00 GB", PackageInstallerDisplayFormatter.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test fun detailsIncludePackageVersionSdkAndSize() {
        val text = PackageInstallerDisplayFormatter.details(
            packageName = "com.example.app",
            versionName = "2.0",
            versionCode = 20,
            minSdk = 26,
            targetSdk = 35,
            size = "12.00 MB",
            installedVersionName = "1.0",
        )
        assertTrue(text.contains("包名：com.example.app"))
        assertTrue(text.contains("版本：1.0 -> 2.0 (20)"))
        assertTrue(text.contains("SDK：26-35"))
        assertTrue(text.contains("大小：12.00 MB"))
    }

    @Test fun missingSdkFallsBackWithoutThrowing() {
        val text = PackageInstallerDisplayFormatter.details(
            packageName = "",
            versionName = "",
            versionCode = 0,
            minSdk = null,
            targetSdk = null,
            size = "",
            installedVersionName = "",
        )
        assertTrue(text.contains("包名：未知"))
        assertTrue(text.contains("SDK：未知"))
    }
}
