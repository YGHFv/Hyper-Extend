/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.screenshot

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

/** 当前 com.miui.screenshot 宿主的精确成员，证据见同名缓存成员资源。 */
internal object ScreenshotEditorTargets {
    const val SHELL_FETCHER = "com.miui.gallery.editor.photo.screen.shell.res.ShellResourceFetcher"
    const val MOSAIC_VIEW = "com.miui.gallery.editor.photo.screen.mosaic.ScreenMosaicView"
    const val CROP_VIEW = "com.miui.gallery.editor.photo.screen.crop.ScreenCropView"
    const val UTIL = "com.miui.screenshot.util.Util"

    val shellEntry = SystemUiMethod(SHELL_FETCHER, "getResId", "long", isStatic = true)
    val shellQuery = SystemUiMethod(
        SHELL_FETCHER,
        "getResId",
        "long",
        listOf("java.lang.String"),
        isStatic = true,
    )
    val privacyMarking = SystemUiMethod(MOSAIC_VIEW, "isSupportPrivacyMarking", "boolean", isStatic = true)

    /**
     * 裁剪框的最小边长计算 —— 解锁「拖不动更小」的唯一靶点。
     *
     * 取证（宿主 MIUIScreenshot.apk / versionCode 10603038 / classes.dex 全量 dexdump）：
     * 方法体是 `Math.max((int)(getImageMatrix().mapRadius(calculateMinPixels()) + 0.5f), 100)`，
     * 即 **private 实例方法、`()I`、用到 0.5f、且 `invoke Math.max(II)I`**
     * （access flags `0x0002 PRIVATE`，指令 `26d2f0 const/high16 #3f00`、`26d2fc const/16 #64`）。
     * 全 APK 里同时满足这四条的方法**只有一个**，因此不需要 DexKit 模糊匹配，直连类名即可。
     *
     * 影响面也已核到指令级：`calculateMinSize` 全 APK **只有 1 个调用点**
     * （`ScreenCropView$ResizeDetector#onFreeResize(FF)Landroid/graphics/PointF;`，
     * 指令 `26cad8 invoke-direct`），4 处取值全部只用于 `constraint()` 的钳位。
     * 所以改它只影响「拖拽裁剪框时的最小尺寸」，不触碰显示缩放、导出与保存。
     *
     * 刻意**不拦** `calculateMinPixels()F`（`26dcc4 access$2200` 那条链）：它参与
     * `getImageBounds()` 的缩放上限判定，属于另一套语义，且本卡片的症状只在拖拽。
     */
    val cropMinSize = SystemUiMethod(
        "$CROP_VIEW\$ResizeDetector", "calculateMinSize", "int",
    )

    /**
     * 截图保存目录判定 —— 全宿主只有这一处决定 Pictures / DCIM。
     *
     * 取证（MIUIScreenshot.apk / versionCode 10603038）：
     * `public static boolean n()` 读取 `gallery_screenshots_version`，并按国际版/国内版特性值
     * 返回是否使用新目录。它的五个调用点全部只用于选择保存目录：
     * `ImageSaveTask`、`AnnotationImageSaveTask`、`ExportTask`、`MediaUtils` 与
     * `RearImageExporter`。因此固定为 true 只会把新截图改存到 `Pictures/Screenshots`。
     *
     * 刻意不改 `Environment.DIRECTORY_DCIM`：那是进程级静态字段，且常量可能已被内联；
     * 改它既可能不生效，也会波及截图进程内所有读取该字段的代码。
     */
    val usePicturesDirectory = SystemUiMethod(UTIL, "n", "boolean", isStatic = true)

    val all = listOf(shellEntry, shellQuery, privacyMarking, cropMinSize, usePicturesDirectory)
}
