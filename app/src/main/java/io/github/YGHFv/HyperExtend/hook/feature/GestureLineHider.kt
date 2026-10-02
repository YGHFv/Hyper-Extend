/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 的默示担保。详见 GNU Affero 通用公共许可证。
 *
 * ---------------------------------------------------------------------------
 * 功能来源：HideLine（com.ccc.hideline，1.0）。
 *
 * 该模块**未声明开源协议**，因此这里的实现不是它的代码，而是依据对它 APK 的
 * 逆向结果（作用域 com.android.systemui）自行重写的。逆向结论摘要：
 *
 *   Application.attach(Context) → after
 *     ├─ NavigationHandle#onDraw          → 短路，不再绘制横条
 *     └─ MultiTaskingSettingsObserver#isGestureLineShowing → 返回 false
 *
 * ## 靶子在本机（澎湃 OS 4 / V816）上的位置 —— 曾被误判为「已删除」
 *
 * `com.android.wm.shell.multitasking.common.MultiTaskingSettingsObserver` **不在
 * SystemUI APK 里**：澎湃把 WMShell 编译成了独立的 boot classpath 共享库
 * `/system_ext/framework/Miui-WindowManager-Shell.jar`（dex 全文检索实证，
 * `isGestureLineShowing` 与该类都在其中）。只搜 SystemUI APK 会得出「靶子已死」的
 * 错误结论 —— 实际上它一直活着，通过类加载器双亲委派对 SystemUI 进程可见。
 * 教训：**核对 hook 靶子前先确认类归属哪个 jar，boot classpath 共享库不在宿主 APK 里。**
 *
 * 「不影响小爱识屏」是这个功能的验收标准，也是它全部价值所在：横条本体不画了，
 * 但 SystemUI 里判断「用户是否在手势区」的逻辑保持原样，所以长按电源键、
 * 小爱识屏这些依赖手势区的入口照常工作。
 */

package io.github.YGHFv.HyperExtend.hook.feature

import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.DexScan
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** 「隐藏手势横条」。作用域：com.android.systemui。 */
internal object GestureLineHider {

    private const val FEATURE = "gesture_line"
    private const val OPT_SKIP_DRAW = "gesture_line.skip_draw"
    private const val OPT_REPORT_HIDDEN = "gesture_line.report_hidden"

    /** 横条本体。AOSP / MIUI 都用这个名字，是最稳的一个靶子。 */
    private const val NAVIGATION_HANDLE = "com.android.systemui.navigationbar.gestural.NavigationHandle"

    /**
     * 「横条当前是否显示」的判定处。WMShell 的类 —— 在澎湃上位于 boot classpath 的
     * `Miui-WindowManager-Shell.jar`（不在 SystemUI APK 里），但进程内照样可加载。
     */
    private const val GESTURE_LINE_OBSERVER =
        "com.android.wm.shell.multitasking.common.MultiTaskingSettingsObserver"

    /**
     * @return 成功挂上的 hook 数（0 表示这个功能在本次启动里没有任何效果）
     */
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var installed = 0
        if (settings.isOn(OPT_SKIP_DRAW)) installed += installSkipDraw(loader)
        if (settings.isOn(OPT_REPORT_HIDDEN)) installed += installReportHidden(loader)
        if (installed == 0) {
            ModuleLog.warn("$FEATURE: no hook installed (all sub-switches off, or targets missing)")
        }
        return installed
    }

    /**
     * 拦截横条绘制。
     *
     * 选择「让 onDraw 直接返回」而不是「把 View 的 visibility 设成 GONE」：
     * 后者要拿到横条 View 实例（得再挂一层构造/attach），而且有些版本会把它重新 set 回来。
     * onDraw 是它唯一一次真正落到画布上的地方，从这里拦是收敛的 —— 无论谁把它设成可见，
     * 画面上都不会出现。
     *
     * 参数个数固定为 1（Canvas），所以顺便校验一下，避免挂到同名的无参重载上
     * （那种重载在别的类里可能是别的方法）。
     */
    private fun installSkipDraw(loader: ClassLoader): Int {
        val clazz = loadClass(loader, NAVIGATION_HANDLE, "NavigationHandle", "gestural")
            ?: return 0
        val onDraw = Reflect.findMethods(clazz, "onDraw", paramCount = 1).firstOrNull()
        if (onDraw == null) {
            ModuleLog.warn("$FEATURE: ${clazz.name}#onDraw(Canvas) not found")
            return 0
        }
        // 返回 null：onDraw 的返回值是 void，null 即「什么都不返回」，
        // 效果等于方法体执行完，只是没有画任何东西。
        return if (HookRuntime.hookReturning(onDraw, "$FEATURE/${clazz.simpleName}#onDraw", null)) 1 else 0
    }

    /**
     * 让「横条是否显示」的判定返回 false。
     *
     * 这个 hook 单看是「多余」的：横条已经不画了。但 SystemUI 里有一批逻辑（横条的
     * 淡入淡出动画、截屏时的横条高亮、部分 ROM 的「显示横条」开关回写）会读这个判定，
     * 读了 true 就会去做那些视觉动作 —— 结果是一条时隐时现的横条。
     * 两个 hook 一起装，效果才是「稳定地没有」。
     */
    private fun installReportHidden(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, GESTURE_LINE_OBSERVER)
            ?: DexScan.findBySimpleName(
                loader,
                HookRuntime.codePaths,
                "MultiTaskingSettingsObserver",
                "com.android.wm.shell",
            )
            ?: run {
                ModuleLog.warn("$FEATURE: $GESTURE_LINE_OBSERVER not found")
                return 0
            }
        val methods = Reflect.findMethods(clazz, "isGestureLineShowing")
        if (methods.isEmpty()) {
            ModuleLog.warn("$FEATURE: ${clazz.name}#isGestureLineShowing not found")
            return 0
        }
        return methods.count {
            HookRuntime.hookReturning(it, "$FEATURE/${clazz.simpleName}#isGestureLineShowing", false)
        }
    }

    /**
     * 按已知名加载，失败则按简单名扫 dex。
     *
     * [packagePrefix] 只用于扫描：命中的类必须落在给定包前缀下，避免在别的模块里
     * 捞到一个同名的无关类。写死的名字不设这个限制 —— 全限定名本身就是最强的约束。
     */
    private fun loadClass(
        loader: ClassLoader,
        knownName: String,
        simpleName: String,
        packagePrefix: String,
    ): Class<*>? =
        Reflect.loadClass(loader, knownName)
            ?: DexScan.findBySimpleName(
                loader,
                HookRuntime.codePaths,
                simpleName,
                "com.android.systemui.$packagePrefix",
            )
}
