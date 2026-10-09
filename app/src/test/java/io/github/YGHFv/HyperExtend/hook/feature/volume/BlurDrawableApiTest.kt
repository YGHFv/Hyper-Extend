/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: Apache-2.0 */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import org.junit.Assert.*
import org.junit.Test

class BlurDrawableApiTest {
    class Single {
        var blur = 0; var corner = 0f; var tint = 0
        fun setBlurRadius(value: Int) { blur = value }
        fun setCornerRadius(value: Float) { corner = value }
        fun setColor(value: Int) { tint = value }
    }
    class Four {
        var blur = 0f; var corners = emptyList<Int>(); var tint = 0
        fun setBlurRadius(value: Float) { blur = value }
        fun setCornerRadius(a: Int, b: Int, c: Int, d: Int) { corners = listOf(a, b, c, d) }
        fun setColor(value: Int) { tint = value }
    }
    @Test fun supportsSingleCornerAndIntegerBlur() {
        val target = Single()
        BlurDrawableApi.configure(target, 100f, 28.5f, 0x77626262)
        assertEquals(100, target.blur); assertEquals(28.5f, target.corner, 0f)
        assertEquals(0x77626262, target.tint)
    }
    @Test fun supportsFourCornersAndFloatBlur() {
        val target = Four()
        BlurDrawableApi.configure(target, 100f, 28f, 0x801e1e22.toInt())
        assertEquals(100f, target.blur, 0f); assertEquals(listOf(28, 28, 28, 28), target.corners)
        assertEquals(0x801e1e22.toInt(), target.tint)
    }
    @Test(expected = NoSuchMethodException::class) fun missingApiIsNotReportedAsSuccessfulBlur() {
        BlurDrawableApi.configure(Any(), 100f, 28f, 0)
    }
}
