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

package io.github.YGHFv.HyperExtend.hook

import android.content.pm.ApplicationInfo
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiPluginHooks
import io.github.YGHFv.HyperExtend.hook.feature.volume.MiSoundVolumeHooks
import io.github.YGHFv.HyperExtend.core.NFC_IMAGE_KEY
import io.github.YGHFv.HyperExtend.hook.feature.GestureLineHider
import io.github.YGHFv.HyperExtend.hook.feature.ScreenshotClipboard
import io.github.YGHFv.HyperExtend.hook.feature.MilinkClipboardGuard
import io.github.YGHFv.HyperExtend.hook.feature.mishare.MiShareReceiveGuard
import io.github.YGHFv.HyperExtend.hook.feature.NativeNotifyIcon
import io.github.YGHFv.HyperExtend.hook.feature.NfcCardFace
import io.github.YGHFv.HyperExtend.hook.feature.PasskeyFix
import io.github.YGHFv.HyperExtend.hook.feature.RotationSuggestionHider
import io.github.YGHFv.HyperExtend.hook.feature.WallpaperMonetFix
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.ScreenshotStatusBar
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusBarBatteryStyle
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusBarClock
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusBarGestures
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusBarIcons
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusBarMobile
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusBarNetworkSpeed
import io.github.YGHFv.HyperExtend.hook.feature.systemui.LockScreenHooks
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationExpansionHooks
import io.github.YGHFv.HyperExtend.hook.feature.systemui.ClipboardOverlayHooks
import io.github.YGHFv.HyperExtend.hook.feature.systemui.ControlCenterHooks
import io.github.YGHFv.HyperExtend.hook.feature.systemui.installSystemUiFeature
import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiCustomHooks
import io.github.YGHFv.HyperExtend.hook.feature.systemui.MediaCardHooks
import java.util.concurrent.ConcurrentHashMap

/**
 * 某个宿主包要做的安装动作。
 *
 * 做成函数类型而不是给每个 feature 定接口：这些安装过程的共同点只有「给我 loader 和设置，
 * 返回一句结果摘要」，它们自己该挂几个 hook、挂了几个，都是各自的事。
 * [HookSettings] 是参数而不是闭包捕获 —— 它在确认设置可用之后才创建。
 */
private typealias HostTask = (ClassLoader, HookSettings) -> String

/**
 * 按宿主包名分派功能。
 *
 * 存在的理由是**进程边界**：五个功能住在四个完全不同的进程里（SystemUI、智能卡、
 * 系统设置一类、system_server），它们能摸到的类和能承受的崩溃代价都不一样。
 * 把「谁在哪个进程里装什么」集中在这一个文件里，好处是加功能时只有一处要改，
 * 而且一眼能看出某个宿主进程会同时被哪几个功能碰到。
 *
 * ## 只在自己人身上花时间
 *
 * `onPackageReady` 会对进程里加载的**每一个包**回调，其中绝大多数（androidx 的各个组件、
 * 宿主的依赖库……）与模块毫无关系。所以「是不是我们的宿主」这一判断必须排在所有
 * 昂贵动作之前 —— 早先的版本是先把 RemotePreferences 握手完再判断，结果是
 * 一个进程里做十几次跨进程握手，全是白费。现在 [hostTask] 一次 map 判断就把它们挡掉了。
 *
 * ## 幂等
 *
 * 同一个宿主包只装一次：重复挂同一个方法会得到两层拦截，行为上大多数时候看不出差别，
 * 但会让日志翻倍、排查时误以为是两个功能在打架。热重载走的是 [reinstallFromHotReload]，
 * 它明确要求「再装一遍」，与这里的幂等是两件事。
 */
internal object HookDispatcher {

    /** 已经处理过的宿主包。值只是用来记日志的（什么时候、装了几个）。 */
    private val handled = ConcurrentHashMap<String, String>()

    /**
     * 这个包里有哪些功能要装。**不认识就返回 null**，调用方据此直接退出，
     * 不做任何读设置、扫 dex 的动作。
     */
    private fun hostTask(packageName: String): HostTask? = when (packageName) {
        AppVolumeSettings.PACKAGE -> { loader, settings ->
            buildList {
                installSystemUiFeature(settings, AppVolumeSettings.FEATURE) { MiSoundVolumeHooks.install(loader) }
            }.joinToString()
        }
        "com.miui.screenshot" -> { loader, settings ->
            val parts = mutableListOf<String>()
            if (settings.isOn("screenshot_clipboard")) {
                parts += "screenshot_clipboard=${ScreenshotClipboard.install(loader)}"
            }
            // Native capture-layer exclusion runs entirely in the screenshot host.
            if (settings.isOn("status_bar_screenshot_hide")) {
                parts += "status_bar_screenshot_hide=${ScreenshotStatusBar.installScreenshot(loader)}"
            }
            parts.joinToString(",").ifEmpty { "no feature enabled" }
        }
        "com.milink.service" -> { loader, settings ->
            if (settings.isOn("milink_clipboard_guard")) "milink_clipboard_guard=${MilinkClipboardGuard.install(loader)}"
            else "no feature enabled"
        }
        "com.miui.mishare.connectivity" -> { loader, settings ->
            if (settings.isOn("mishare_receive_guard")) "mishare_receive_guard=${MiShareReceiveGuard.install(loader)}"
            else "no feature enabled"
        }
        HostPlatform.SYSTEMUI -> { loader, settings -> installSystemUi(loader, settings) }
        HostPlatform.TSM_CLIENT -> { loader, settings -> installNfcCardFace(loader, settings) }
        HostPlatform.SETTINGS -> { loader, settings ->
            (ControlCenterHooks.installSettings(loader, settings) +
                PasskeyFix.installPackage(packageName, loader, settings)).joinToString()
        }
        HostPlatform.SECURITY_CENTER,
        HostPlatform.XIAOMI_SCANNER,
        -> { loader, settings -> PasskeyFix.installPackage(packageName, loader, settings) }

        else -> null
    }

    /**
     * @param packageName 本次 ready 的包名
     * @param loader 这个包的 classloader（**必须**用它，不能用模块自己的）
     * @param applicationInfo 用来取 dex 路径，供「写死的类名找不到」时兜底扫描
     */
    fun dispatch(packageName: String, loader: ClassLoader, applicationInfo: ApplicationInfo?) {
        // 逃生门放在最前面：合上时**连 dex 扫描都不做**，也不读设置 ——
        // 这是个「系统可能已经不正常」的路径，越少动作越好。
        if (KillSwitch.reportOnce()) return

        val task = hostTask(packageName) ?: return
        handled[packageName]?.let {
            ModuleLog.info("already handled $packageName ($it), skip")
            return
        }

        // 宿主身份先记下来：热重载时框架不会重放本回调，这两样东西是那时唯一的线索。
        HookRuntime.rememberHost(packageName, loader)
        HookRuntime.setHostCodePaths(DexScan.codePathsOf(applicationInfo))
        HookRuntime.setHostDataDir(applicationInfo?.dataDir)

        // 日志镜像要赶在任何功能日志之前挂上：这条设备上 logcat 拿不到注入侧日志
        // （logd 冻结），文件是唯一证据源。数据目录此刻刚知道，立刻接上，
        // 缓冲里已有的 entry 日志会由 ModuleLog 回放进文件。
        applicationInfo?.dataDir?.let { dir ->
            ModuleLog.attachFileSink("$dir/files/hyperextend.log")
        }

        val settings = HookRuntime.settings()
        if (!settings.isAvailable) {
            // 一句就说清楚：开关读不到 = 一个功能都不会装，再往下看 hook 日志没有意义。
            ModuleLog.warn(
                "switches unavailable (framework has no PROP_CAP_REMOTE) — " +
                    "nothing will be installed in $packageName",
            )
            return
        }


        if (packageName == HostPlatform.SYSTEMUI && BootLoopGuard.shouldSkipSystemUiHooks(settings, applicationInfo?.dataDir)) {
            ModuleLog.warn("SystemUI hooks skipped (framework hook fuse engaged)")
            return
        }

        runTask(packageName, task, loader, settings)
    }

    /**
     * 热重载之后的重新挂载。
     *
     * 框架热重载**不会**重放 `onPackageReady` / `onSystemServerStarting`，新的一代代码只会收到
     * `onHotReloaded`。所以这里的信息来源只有两个：上一代留下的缓存（[HookRuntime]），
     * 以及模块侧随重载一起递过来的 extras（`HotReloader.EXTRA_*`）。
     * 缓存优先 —— 它是本进程亲眼见到的；extras 是兜底，用于「上一代代码没留缓存、
     * 或者这个进程是被更早的版本挂上的」这类情况。
     *
     * @param extrasPackage 模块侧传过来的目标进程名
     * @param extrasCodePaths 模块侧按包名查出来的 dex 路径
     * @param extrasDataDir 模块侧按包名查出来的私有目录（文件日志要写进去的那个）
     */

    /** 跑一个宿主任务，并把结果记进 [handled]。 */
    private fun runTask(
        packageName: String,
        task: HostTask,
        loader: ClassLoader,
        settings: HookSettings,
    ): String {
        val summary = try {
            task(loader, settings)
        } catch (t: Throwable) {
            ModuleLog.error("dispatch failed for $packageName", t)
            "failed"
        } finally {
            // 全量类名缓存的生命周期就是一次分派：见 DexScan.fullCache 的注释。
            DexScan.clearCache()
        }
        handled[packageName] = summary
        ModuleLog.info("dispatch[$packageName] -> $summary | ${HookRuntime.hookSummary()}")
        return summary
    }

    /**
     * SystemUI 进程。
     *
     * 这里是全模块功能最集中的一处：手势横条、壁纸取色、通知图标、以及「状态栏」那一整页
     * （图标、电池、移动网络、网速、时钟、双击锁屏、截屏时隐藏）都住在同一个进程里。
     * 它们互相之间没有依赖，各自 [HookSettings.isOn] 一次即可 —— 谁被打开谁装。
     */
    private fun installSystemUi(loader: ClassLoader, settings: HookSettings): String {
        // 非小米 ROM 上这三个功能的靶子要么不存在、要么同名不同义，一律不装（见 HostPlatform 注释）。
        if (!HostPlatform.isXiaomiRom(loader)) {
            ModuleLog.warn("not a Xiaomi ROM — SystemUI features skipped")
            return "skipped (not xiaomi)"
        }
        val parts = mutableListOf<String>()

        parts += LockScreenHooks.install(loader, settings)
        parts += NotificationHooks.install(loader, settings)
        parts += NotificationExpansionHooks.install(loader, settings)
        parts += ControlCenterHooks.install(loader, settings)
        parts += SystemUiCustomHooks.install(loader, settings)
        parts += MediaCardHooks.install(loader, settings)
        parts += SystemUiPluginHooks.install(loader, settings)
        parts.installSystemUiFeature(settings, "clipboard_native_overlay") { ClipboardOverlayHooks.install(loader) }
        if (settings.isOn("gesture_line")) {
            parts += "gesture_line=${GestureLineHider.install(loader, settings)}"
        }
        if (settings.isOn("wallpaper_monet")) {
            parts += "wallpaper_monet=${WallpaperMonetFix.install(loader, settings)}"
        }
        if (settings.isOn("native_notify_icon")) {
            parts += "native_notify_icon=${NativeNotifyIcon.install(loader, settings)}"
        }
        if (settings.isOn("rotation_suggestion")) {
            parts += "rotation_suggestion=${RotationSuggestionHider.install(loader)}"
        }
        if (settings.isOn("status_bar_icons")) {
            parts += "status_bar_icons=${StatusBarIcons.install(loader, settings)}"
        }
        if (settings.isOn("status_bar_battery_style")) {
            parts += "status_bar_battery_style=${StatusBarBatteryStyle.install(loader, settings)}"
        }
        if (settings.isOn("status_bar_mobile")) {
            parts += "status_bar_mobile=${StatusBarMobile.install(loader, settings)}"
        }
        if (settings.isOn("status_bar_network_speed")) {
            parts += "status_bar_network_speed=${StatusBarNetworkSpeed.install(loader, settings)}"
        }
        if (settings.isOn("status_bar_clock")) {
            parts += "status_bar_clock=${StatusBarClock.install(loader, settings)}"
        }
        if (settings.isOn("status_bar_double_tap")) {
            parts += "status_bar_double_tap=${StatusBarGestures.install(loader, settings)}"
        }
        return if (parts.isEmpty()) "no feature enabled" else parts.joinToString(",")
    }

    /**
     * 小米智能卡进程：NFC 卡面。
     *
     * ## 为什么「没配图」也要装
     *
     * 这个功能没有布尔主开关（见 `HyperFeature.configKey`）：配了图 = 换卡面，
     * 没配图 = 保持钱包自带。但**「钱包自带是哪张图」这件事只有钱包自己知道**，
     * 模块界面左侧要把那张图显示出来，就必须在钱包加载它的时候把这个地址截下来。
     * 所以「没配图」这一路装的是一组**只观察、不改写**的 hook（见 [NfcCardFace.install]），
     * 它们的作用就是把「钱包自带卡面」交回模块界面。
     *
     * 代价是钱包进程里恒有三个只读挂载点。它们不改变任何行为（`proceed()` 原样放行），
     * 所以这个代价是可以接受的；换来的是「打开钱包一次，卡面页左侧就能看到它自带的那张」。
     */
    private fun installNfcCardFace(loader: ClassLoader, settings: HookSettings): String {
        val installed = NfcCardFace.install(loader, settings)
        val configured = settings.string(NFC_IMAGE_KEY).isNotBlank()
        return "nfc_card_face=$installed" + if (configured) "[replace]" else "[capture]"
    }
}
