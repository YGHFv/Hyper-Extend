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

package io.github.YGHFv.HyperExtend

import android.app.Application
import io.github.YGHFv.HyperExtend.config.HyperSettings
import io.github.YGHFv.HyperExtend.core.FrameworkBridge
import io.github.YGHFv.HyperExtend.core.LauncherIconStyle
import io.github.YGHFv.HyperExtend.core.LauncherIcons
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.NfcCardImage
import io.github.YGHFv.HyperExtend.ui.UiPrefs
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 进程的 Application。**唯一职责是接上框架服务**。
 *
 * ## 为什么模块 App 需要这个东西
 *
 * 模块的设置存在本地 SharedPreferences，但真正要读它的是**被注入的宿主进程**（SystemUI、
 * 设置、system_server）。两者是两个 uid，彼此的私有目录互不可见。唯一能穿透这条边界的是
 * 框架托管的 RemotePreferences，而拿到它的句柄（`XposedService`）只有一条途径：
 * 在模块进程里向 `XposedServiceHelper` 注册监听，等框架通过 AAR 自带的那个 `<provider>`
 * 把 binder 递过来。
 *
 * 没接上会发生什么：设置照常保存在本地，界面上开关也能拨，但注入侧读到的永远是空 ——
 * 用户看到的现象是「开关拨了没反应」。这正是 [ModuleLog] 里把那句
 * `framework service unavailable` 写成 warn 的原因：它是这类问题的第一现场。
 *
 * ## 框架不在线时静默降级
 *
 * 模块没在 LSPosed 里启用、或本模块不在作用域里时，监听会一直不回调 binder。
 * 这**不是错误**（用户可能只是刚装上还没启用），所以只记一条日志、不重试、不轮询 ——
 * 轮询会在用户明确没启用模块时持续唤醒进程，纯属耗电。
 */
class HyperExtendApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerXposedService()
        // 卡面图片的读权限补授一次。见 NfcCardImage.ensureGranted 的注释：
        // 它只在真的配过图时才做一次 binder 调用，成本可以忽略，
        // 换来的是一条「重启之后卡面不会莫名变回原样」的保险。
        runCatching { NfcCardImage.ensureGranted(this) }
            .onFailure { ModuleLog.warn("nfc card face grant restore failed: ${it.javaClass.simpleName}") }
        // 桌面图标选择恢复一次。APK 更新不必然重置组件状态，但「偏好记录了一套、
        // 组件实际是另一套」的漂移确实存在（别的工具动过、或上次切换被打断）——
        // 打开一次模块就是修复时机。只在「未隐藏」时修：隐藏态下点亮别名是把
        // 用户藏起来的图标重新摆回桌面，那是绝对不能自动做的事。
        runCatching {
            val ui = UiPrefs.read(this)
            if (!ui.hideLauncherIcon) {
                val actual = LauncherIcons.readState(this, ui.iconStyle)
                if (actual != null && !actual.hidden && actual.style.key != ui.iconStyle) {
                    if (LauncherIcons.apply(this, LauncherIconStyle.fromKey(ui.iconStyle), actual)) {
                        ModuleLog.info("launcher icon style restored to ${ui.iconStyle}")
                    }
                }
            }
        }.onFailure { ModuleLog.warn("launcher icon restore failed: ${it.javaClass.simpleName}") }
        // 启动横幅。
        //
        // 这一行不是为了好看，是为了填一个真实的诊断空档：`registerListener` 之后如果框架没把
        // binder 递过来（模块还没在 LSPosed 里启用、或本模块不在作用域里），[onServiceBind] 不会
        // 被调用，于是**整个启动过程一条日志都没有** —— 用户第一次打开模块，「关于与日志」页
        // 显示「暂无日志」，而这恰恰是他最需要知道自己处在哪一步的时候。
        // 有了这一行，「模块 App 起来了 / 框架服务绑上了没有」就各有一行可查。
        ModuleLog.entry(
            "app started: ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "frameworkService=${if (FrameworkBridge.isConnected()) "bound" else "pending"}",
        )
    }

    /**
     * 注册框架服务监听。
     *
     * 整段包 `runCatching`：`io.github.libxposed.service` 是 `implementation` 依赖，
     * 正常情况下一定会打进 APK，但一旦哪天被误改成 `compileOnly`，
     * 这里就会以 `NoClassDefFoundError` 崩在 Application.onCreate —— 那是「装上就闪退」，
     * 比「设置同步不了」严重得多。宁可吞掉异常留一条日志。
     */
    private fun registerXposedService() {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {

                override fun onServiceBind(service: XposedService) {
                    FrameworkBridge.attach(service)
                    ModuleLog.info(
                        "framework service bound: api=${service.apiVersion} " +
                            "framework=${service.frameworkName} ${service.frameworkVersion}",
                    )
                    // 诊断：框架视角里模块当前挂载在哪些进程。这一行直接回答
                    // 「功能不生效是因为没注入，还是注入了但别的地方坏了」——
                    // 界面上的宿主日志文件只能证明"日志文件在不在"，证明不了注入本身。
                    runCatching {
                        val targets = service.runningTargets
                        ModuleLog.info(
                            "running targets (${targets.size}): " +
                                targets.joinToString { "${it.processName}/${it.pid}" },
                        )
                    }.onFailure { ModuleLog.error("query runningTargets failed", it) }
                    // 绑上就立刻把本地设置整体投影一次。
                    //
                    // 不这么做的话有一条真实的漏：用户上次改完设置后从没再打开过界面，
                    // 而此时框架服务是新的（比如框架刚更新、设备刚重启）——
                    // RemotePreferences 里是空的，注入侧于是读到「全关」，
                    // 表现为「重启之后功能自己失效了」，而用户什么都没改过。
                    // 投影是幂等的（写的就是本地已有的那份），多做一次没有副作用。
                    runCatching { HyperSettings.syncToFramework(this@HyperExtendApplication) }
                        .onFailure { ModuleLog.error("initial settings projection failed", it) }
                }

                override fun onServiceDied(service: XposedService) {
                    // 服务死了不等于设置丢了：本地那份还在，只是暂时推不过去。
                    // 下次绑定（onServiceBind）会重新投影。
                    FrameworkBridge.attach(null)
                    ModuleLog.warn(
                        "framework service died — switches saved locally only until it rebinds",
                    )
                }
            })
        }.onFailure {
            ModuleLog.error("register framework service listener failed", it)
        }
    }
}
