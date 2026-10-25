/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object ScreenshotEditorSettings {
    const val DEVICE_FEATURE = "screenshot_device_customize"
    const val DEVICE = "$DEVICE_FEATURE.device"
    const val PRIVACY_FEATURE = "screenshot_unlock_privacy_marking"
    const val CROP_FEATURE = "screenshot_unlock_minimum_crop_limit"
    const val SAVE_TO_PICTURES_FEATURE = "screenshot_save_to_pictures"

    private val DEVICE_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

    /** 外壳资源键只允许宿主现有资源表能表示的标识符，空白表示保持原生。 */
    fun device(raw: String): String? = raw.trim().takeIf(DEVICE_PATTERN::matches)

    /**
     * 裁剪框最小边长的放宽倍数（分母）。宿主算出的下限除以它，就是本功能给出的新下限。
     *
     * ## 为什么不能像上游那样直接 `returnConstant(0)`
     *
     * 保存路径 `ScreenCropEntry#apply` 是
     * `Bitmap.createBitmap((int)(mCropArea.width() + 0.5f), (int)(mCropArea.height() + 0.5f), …)`，
     * **没有任何宽高非空校验**；而 `mCropArea` 能被拖到多小，正是由本方法这个下限钳位的
     * （`onFreeResize` 里 4 处 `constraint()` 全拿它当硬下限）。下限归零 = 钳位消失 =
     * 可以拖出亚像素裁剪框 = 保存时 `IllegalArgumentException: width must be > 0`，
     * 崩在用户点「保存」的那一刻。上游 HyperCeiler / Cemiuiler 就是这个缺陷。
     *
     * ## 为什么是「除以 8」而不是写死一个显示像素常量
     *
     * 单位不可移植。本方法的返回值在**显示坐标系**，而 `apply` 在取整之前先用
     * `setRectToRect(mBitmapArea, RectF(0,0,bitmap.width, bitmap.height), FILL)`
     * 把 `mCropArea` 从显示坐标映回**图像像素**，所以进 `createBitmap` 的是
     * `显示尺寸 / scale`（`scale` = 显示/图像的缩放系数，即宿主 `mapRadius` 用的那个）。
     * 任何绝对显示像素值都会随用户缩放倍数而变，写死的数没法保证非零。
     *
     * ⚠️ 这里否掉的正是「写死一个显示像素常量」这条路：宿主自己的
     * `下限 = max(mapRadius(CROP_AREA_MIN_PIXELS=16.0f), 100) = max(16·scale, 100)`
     * 是**随 scale 线性变化**的，它因此恒有 `下限/scale ≥ 16`，也就是宿主从来不允许
     * 保存出小于 16 个**图像像素**的图。换成固定值就把这条自洽关系断了 ——
     * 放大倍数越高，固定显示像素折算回图像侧越小，`scale = 400` 时 16 显示像素
     * 只剩 0.04 图像像素，取整即 0，一样崩在保存上。上一版提交里的固定 16 就是这个缺陷。
     *
     * 按比例放宽则保留这条关系：宿主值 `/8` 之后仍有 `值/8/scale ≥ 2` 个图像像素。
     * 整除最多再损失 `(k-1)/k / scale` 个像素，`scale ≈ 16` 的交叉处最不利。
     * 对 `scale ∈ (0, 400)` 数值扫描：`k=8` 最坏 2.36（含 `+0.5f`）→ 位图宽恒 ≥ 2；
     * `k=16` 最坏 1.36 → 恒 ≥ 1；`k=32` 最坏 0.88 → **会出现 0 宽位图**。
     * 所以分母 8 是「留出一档余量」的选择，不是随手取的经验数；上限在 16 与 32 之间。
     */
    const val CROP_RELAX_DIVISOR = 8

    val config = listOf(
        HyperText(
            DEVICE,
            "设备代号",
            summary = "填写截图外壳资源使用的设备标识；留空跟随系统。仅改变截图编辑器查询，不改 Build.DEVICE。",
            placeholder = "例如 shell_code",
        ),
    )
}
