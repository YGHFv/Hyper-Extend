/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.core

/** 系统框架「默认音量流」配置键。 */
const val SYSTEM_FRAMEWORK_DEFAULT_VOLUME_STREAM = "system_framework_volume_default_stream.mode"

/** 系统框架「安全音量限制」配置键。 */
const val SYSTEM_FRAMEWORK_VOLUME_SAFE_MODE = "system_framework_volume_disable_safe.mode"

/** 系统框架「音量级数倍率」配置键。 */
const val SYSTEM_FRAMEWORK_VOLUME_STEPS = "system_framework_volume_steps.multiplier"

/** 系统框架「媒体音量级数」配置键。 */
const val SYSTEM_FRAMEWORK_VOLUME_MEDIA_STEPS = "system_framework_volume_media_steps.count"

/** 剪贴板白名单包名，使用换行分隔，复用系统界面的应用选择序列化格式。 */
const val SYSTEM_FRAMEWORK_CLIPBOARD_PACKAGES = "system_framework_clipboard_whitelist.packages"

/** 系统框架「输入法列表解锁」的应用选择键。 */
const val SYSTEM_FRAMEWORK_MIUI_IME_PACKAGES = "system_framework_miui_ime_unlock.packages"

/** 系统框架「AOSP 输入法导航栏兼容」的应用选择键。 */
const val SYSTEM_FRAMEWORK_AOSP_IME_PACKAGES = "system_framework_aosp_ime.packages"

/** 系统框架「触控笔蓝牙限制」使用的驱动版本键。 */
const val SYSTEM_FRAMEWORK_MIPAD_STYLUS_VERSION =
    "system_framework_mipad_stylus_bluetooth.version"

private val volumeStreamChoices = listOf(
    ChoiceEntry("0", "跟随系统"),
    ChoiceEntry("2", "铃声"),
    ChoiceEntry("3", "媒体"),
    ChoiceEntry("4", "闹钟"),
    ChoiceEntry("5", "通知"),
)

private val volumeSafeChoices = listOf(
    ChoiceEntry("0", "保持系统限制"),
    ChoiceEntry("1", "所有场景关闭限制"),
)

private val stylusVersionChoices = listOf(
    ChoiceEntry("2", "版本 2"),
    ChoiceEntry("1", "版本 1"),
)

/**
 * 西米露系统框架首批迁移清单。
 *
 * 这里只登记已经在当前 HyperOS 4 宿主 DEX 中确认到类或方法的目标；复杂签名校验、
 * 固定应用、主题、MiSound 与平板输入等需要单独验证调用链的功能不混入这一批。
 */
val SYSTEM_FRAMEWORK_FEATURES: List<HyperFeature> = listOf(
    // 核心破解：与西米露一致，单一总开关 +「常规 / 扩展」子项。
    // 总开关启用并重启一次后，各子项在 Hook 回调里实时读取，调整后即时生效；
    // 少数在安装期写静态字段的扩展项仍需重启（见各子项说明）。
    HyperFeature(
        id = "system_framework_core_patch",
        title = "核心破解",
        summary = "签名、降级、系统应用更新与包管理限制",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · CorePatch 系列（AllowUpdateSystemApp、AuthCreak、DigestCreak、Downgrade、ExactSign、SharedUser、VerificationAgent、DisableLowApi、DisablePersistent、BypassIsolation、UseAndroidPackageInstaller、DisableRemoveFingerprintSensorConfig）",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "启用总开关后需重启一次系统服务；之后常规子项调整即时生效，扩展里的静态项仍需重启",
        options = listOf(
            HyperOption(
                id = "system_framework_core_patch_downgrade",
                title = "允许应用降级",
                summary = "跳过安装器对 versionCode 降低的拦截",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "system_framework_core_patch_signature",
                title = "禁用软件包管理器签名验证",
                summary = "允许使用旧签名方案的应用继续安装",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "system_framework_core_patch_digest",
                title = "禁用系统完整性校验",
                summary = "允许修改过内容的 APK 继续通过部分摘要检查",
                defaultEnabled = false,
                group = "常规",
            ),
            // 上游把这条叫 digest_creak，原文是「允许直接覆盖安装同包名不同签名的应用」。
            // 它的靶点是 SigningDetails#checkCapability，与上面两项（都由上游 auth_creak 拆分而来）
            // 不是同一处判定：本模块此前只迁了 auth_creak 那一半，所以「开了核心破解仍然覆盖不了
            // 不同签名的包」—— 宿主先在 verifySignatures 里抛 -7，其余靶点根本轮不到。
            // 上面「禁用软件包管理器签名验证」的旧文案曾经承诺过这件事，但实现里没有，已改回准确说法。
            HyperOption(
                id = "system_framework_core_patch_override_signature",
                title = "允许覆盖安装不同签名",
                summary = "同包名不同签名时继续覆盖安装",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "system_framework_core_patch_shared_user",
                title = "共享 UID 签名兼容",
                summary = "放宽共享 UID 应用之间的签名祖先校验",
                defaultEnabled = false,
                group = "常规",
            ),
            // 上游把「覆盖安装不同签名」拆成 digest_creak 与 use_pre_signature 两个开关：
            // 前者放宽能力判定与 key-set，后者额外把已安装应用的签名（含其 signature 级权限）
            // 直接送给新包。风险高一档，所以这里同样拆成两个子项，不并到上一项里顺带打开。
            HyperOption(
                id = "system_framework_core_patch_use_pre_signature",
                title = "沿用已安装应用的签名",
                summary = "同包名时沿用已安装应用的签名与其签名级权限（仅用户主动安装生效）",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "system_framework_core_patch_exact_signature",
                title = "禁用分包签名匹配",
                summary = "允许不同签名的 split APK 组合安装",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "system_framework_use_android_package_installer",
                title = "使用原生应用包安装器",
                summary = "安装时优先选择 Android 原生包安装器",
                defaultEnabled = false,
                group = "常规",
            ),
            HyperOption(
                id = "system_framework_core_patch_disable_low_api_check",
                title = "禁用低 API 校验",
                summary = "允许安装 targetSdk 过低的应用",
                defaultEnabled = false,
                group = "扩展",
            ),
            HyperOption(
                id = "system_framework_core_patch_disable_persistent",
                title = "禁用持久性检查",
                summary = "安装时忽略 persistent 标记",
                defaultEnabled = false,
                group = "扩展",
            ),
            HyperOption(
                id = "system_framework_core_patch_verification_agent",
                title = "禁用安装包验证代理",
                summary = "安装应用时不再请求系统校验代理判定",
                defaultEnabled = false,
                group = "扩展",
            ),
            HyperOption(
                id = "system_framework_core_patch_bypass_isolation_violation",
                title = "禁用隔离应用检查",
                summary = "跳过包管理服务对安装隔离规则的校验",
                defaultEnabled = false,
                group = "扩展",
            ),
            HyperOption(
                id = "system_framework_core_patch_allow_update_system_app",
                title = "允许更新系统应用",
                summary = "绕过包管理服务对系统应用更新的额外限制",
                defaultEnabled = false,
                group = "扩展",
            ),
            HyperOption(
                id = "system_framework_core_patch_fingerprint_guard",
                title = "禁用重置指纹",
                summary = "阻止部分包管理兼容改动在重启后覆盖指纹配置",
                defaultEnabled = false,
                group = "扩展",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_CORE,
    ),

    // 小窗
    HyperFeature(
        id = "system_framework_freeform_count",
        title = "解除小窗数量限制",
        summary = "允许同时打开更多小窗，避免系统在固定数量后拒绝新窗口",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · FreeFormCount",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_FREEFORM,
    ),
    HyperFeature(
        id = "system_framework_freeform_bubble",
        title = "启用小窗气泡",
        summary = "让支持小窗的应用可以进入气泡形态",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · FreeformBubble",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_FREEFORM,
    ),
    HyperFeature(
        id = "system_framework_freeform_blacklist",
        title = "解除小窗黑名单",
        summary = "允许系统黑名单中的应用进入小窗或分屏",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · DisableFreeformBlackList；参考 XiaomiHelper",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "仅移植当前宿主已存在的分屏、可调整大小、黑名单 getter 与静态列表",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_FREEFORM,
    ),
    HyperFeature(
        id = "system_framework_freeform_foreground_pin",
        title = "小窗前台固定",
        summary = "保持小窗在前台固定状态，减少被系统自动收起",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · UnForegroundPin",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_FREEFORM,
    ),

    // 音量
    HyperFeature(
        id = "system_framework_volume_default_stream",
        title = "默认音量流",
        summary = "按音量键时优先调节指定类型的声音",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · VolumeDefaultStream",
        license = "AGPL-3.0",
        defaultEnabled = false,
        config = listOf(
            HyperChoice(
                key = SYSTEM_FRAMEWORK_DEFAULT_VOLUME_STREAM,
                title = "调节类型",
                entries = volumeStreamChoices,
                summary = "选择「跟随系统」时保持宿主原有判断",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_VOLUME,
    ),
    HyperFeature(
        id = "system_framework_volume_first_press",
        title = "首次按键直接调节",
        summary = "媒体音量面板尚未展开时，第一次按音量键就直接改变音量",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · VolumeFirstPress",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_VOLUME,
    ),
    HyperFeature(
        id = "system_framework_volume_steps",
        title = "音量级数倍率",
        summary = "扩大系统各音频流的最大级数，获得更细的音量档位",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · VolumeSteps",
        license = "AGPL-3.0",
        defaultEnabled = false,
        config = listOf(
            HyperSlider(
                key = SYSTEM_FRAMEWORK_VOLUME_STEPS,
                title = "最大音量倍率",
                min = 25,
                max = 500,
                default = 100,
                step = 25,
                divisor = 100,
                unit = " 倍",
                summary = "100% 保持原值；过高倍率可能被宿主限制",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_VOLUME,
    ),
    HyperFeature(
        id = "system_framework_volume_media_steps",
        title = "媒体音量级数",
        summary = "提高媒体音量最大档位，使每次按键的音量变化更细",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · VolumeMediaSteps",
        license = "AGPL-3.0",
        defaultEnabled = false,
        config = listOf(
            HyperSlider(
                key = SYSTEM_FRAMEWORK_VOLUME_MEDIA_STEPS,
                title = "媒体音量档位",
                min = 15,
                max = 29,
                default = 15,
                unit = " 级",
                summary = "只提高、不降低宿主原本声明的档位",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_VOLUME,
    ),
    HyperFeature(
        id = "system_framework_volume_disable_safe",
        title = "解除安全音量限制",
        summary = "避免系统把媒体音量限制在安全阈值以下",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · VolumeDisableSafe",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "长期在高音量下使用可能损伤听力，请谨慎开启",
        config = listOf(
            HyperChoice(
                key = SYSTEM_FRAMEWORK_VOLUME_SAFE_MODE,
                title = "处理方式",
                entries = volumeSafeChoices,
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_VOLUME,
    ),

    // 显示
    HyperFeature(
        id = "system_framework_all_dark_mode",
        title = "全部应用深色模式",
        summary = "让系统的深色模式应用列表包含普通第三方应用",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · AllDarkMode；参考 SetoHook",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY,
    ),
    HyperFeature(
        id = "system_framework_background_blur",
        title = "启用高级材质模糊",
        summary = "强制系统属性声明背景模糊支持并启用默认模糊",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · BackgroundBlur",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "会覆盖系统持久化属性；低性能设备可能增加渲染负担",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY,
    ),
    HyperFeature(
        id = "system_framework_enhance_recents_visibility",
        title = "增强最近任务可见性",
        summary = "移除 Intent 中「不进入最近任务」的标志",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · EnhanceRecentsVisibility",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY,
    ),
    HyperFeature(
        id = "system_framework_use_aosp_screenshot",
        title = "使用 AOSP 截屏服务",
        summary = "改用 Android 原生截屏服务",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · UseAOSPScreenShot",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "澎湃截屏扩展功能可能不再可用",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY,
    ),
    HyperFeature(
        id = "system_framework_hide_display_cutout",
        title = "隐藏屏幕挖孔",
        summary = "让系统按无挖孔布局计算状态栏与显示区域",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · DisplayCutout",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "会改变状态栏安全区，请确认不会遮挡挖孔附近内容",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY,
    ),
    HyperFeature(
        id = "system_framework_thermal_brightness",
        title = "关闭温控降亮度",
        summary = "阻止温控策略自动压低屏幕亮度",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · ThermalBrightness",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "只修改当前宿主存在的温控亮度入口，设备过热风险由用户自行承担",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY,
    ),
    HyperFeature(
        id = "system_framework_theme_provider",
        title = "允许使用第三方主题",
        summary = "绕过主题校验不合法拦截，背屏专用主题流程保持原样",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · ThemeProvider",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "只对当前宿主存在的主题接收器与 DRM 校验方法生效",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY,
    ),

    // 显示与通知
    HyperFeature(
        id = "system_framework_screen_all_rotations",
        title = "屏幕全方向旋转",
        summary = "允许屏幕按四个方向旋转，不再限制倒置方向",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · ScreenRotation",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),
    HyperFeature(
        id = "system_framework_quick_screenshot",
        title = "快速截屏",
        summary = "缩短电源键与音量键组合触发截屏的等待时间",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · QuickScreenshot；已适配 OS4 KeyGestureController",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),
    HyperFeature(
        id = "system_framework_anti_ques",
        title = "关闭设备名称违规词校验",
        summary = "允许热点蓝牙使用非违规设备名称",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · AntiQues",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),
    HyperFeature(
        id = "system_framework_disable_72h_verify",
        title = "关闭 72 小时密码验证",
        summary = "不再周期性要求重新输入锁屏密码以维持生物识别",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · DisablePinVerifyPer72h",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),
    HyperFeature(
        id = "system_framework_app_link_verify",
        title = "关闭应用链接验证",
        summary = "打开已声明链接时跳过系统的域名验证流程",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · AppLinkVerify",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),
    HyperFeature(
        id = "system_framework_untrusted_touch",
        title = "允许不可信触摸",
        summary = "允许系统在部分悬浮窗口和覆盖层中继续传递触摸",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · AllowUntrustedTouchForU",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),
    HyperFeature(
        id = "system_framework_delete_alert_window_notification",
        title = "删除悬浮窗通知",
        summary = "不再为后台悬浮窗显示系统提醒通知",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · DeleteOnPostNotification",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),
    HyperFeature(
        id = "system_framework_flag_secure",
        title = "允许截屏与录屏",
        summary = "忽略 FLAG_SECURE，允许截屏与录屏",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · FlagSecure",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "会暴露原本受保护的窗口内容，请只在可信环境使用",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),
    HyperFeature(
        id = "system_framework_no_access_device_logs",
        title = "跳过设备日志授权",
        summary = "应用请求读取系统日志时直接批准，不再弹出访问请求",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · NoAccessDeviceLogsRequest",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),
    HyperFeature(
        id = "system_framework_disable_link_turbo_toast",
        title = "关闭链路加速提示",
        summary = "链路加速状态变化时不再显示 Toast",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · LinkTurboToast",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),

    // 底层
    HyperFeature(
        id = "system_framework_disable_miui_watermark",
        title = "关闭 MIUI 水印",
        summary = "移除系统窗口层绘制的水印",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · DisableMiuiWatermark",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_posted_clipboard",
        title = "关闭剪贴板访问提示",
        summary = "应用读取剪贴板时不再显示系统访问通知",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · PstedClipboard",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "会降低剪贴板隐私访问的可见性",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_disable_cleaner",
        title = "关闭系统主动清理",
        summary = "阻止系统主动结束后台进程、裁剪近期任务和清理内存",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · DisableCleaner",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "可能增加内存与耗电压力，低内存设备请谨慎开启",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_bypass_force_mi_app_store",
        title = "绕过强制小米应用商店",
        summary = "打开应用市场链接时显示应用选择器",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · BypassForceMiAppStore",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_market_use_detailmini",
        title = "应用市场使用迷你详情页",
        summary = "应用市场改用迷你详情页",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · BypassForceMiAppStore",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_speed_install",
        title = "启用快速安装",
        summary = "让系统声明支持 V2 快速安装能力",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · SpeedInstall",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_disable_miui_lite",
        title = "关闭 MIUI Lite 判定",
        summary = "让系统按完整版加载组件",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · DisableMiuiLite",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_notification_channel_blockable",
        title = "通知渠道可屏蔽",
        summary = "解除系统对部分通知渠道的屏蔽限制",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · AllowManageAllNotifications",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_DISPLAY_NOTIFY,
    ),
    HyperFeature(
        id = "system_framework_native_file_picker",
        title = "使用原生文件选择器",
        summary = "优先使用 Android 原生文件选择器",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · NativeFilePicker",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_clipboard_whitelist",
        title = "剪贴板白名单",
        summary = "允许指定应用在后台读取剪贴板",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · ClipboardWhitelist",
        license = "AGPL-3.0",
        defaultEnabled = false,
        config = listOf(
            HyperAppSelection(
                key = SYSTEM_FRAMEWORK_CLIPBOARD_PACKAGES,
                summary = "只对选中的应用放行；未选择应用时不安装白名单 Hook",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_allow_disable_protected_package",
        title = "允许停用受保护应用",
        summary = "绕过包管理服务对受保护应用停用状态的拦截",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · AllowDisableProtectedPackage",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_bypass_unknown_sources",
        title = "绕过未知来源限制",
        summary = "让应用无需逐次授权即可请求安装其他应用",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · BypassUnknownSourcesRestrictions",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "会降低安装来源确认强度，请只安装可信应用",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_bypass_force_downloadui",
        title = "强制下载器选择器",
        summary = "查看下载内容时显示应用选择器，而不是固定跳转到下载界面",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · BypassForceDownloadui",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_bypass_wake_path_checker",
        title = "绕过链式跳转检测",
        summary = "绕过 WakePath 规则的应用跳转拦截",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · BypassWakePathChecker",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_disable_gesture_monitor",
        title = "关闭手势监听",
        summary = "阻止 MIUI 手势监听器注册额外的指针事件回调",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · DisableGestureMonitor",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_disable_thermal",
        title = "关闭系统热控事件",
        summary = "阻止热控管理器继续投递系统热状态事件",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · DisableThermal",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "会削弱设备温度保护，长时间高负载时请谨慎开启",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_gms_doze",
        title = "允许 Google Play 服务随时联网",
        summary = "避免澎湃省电策略延迟 GMS 推送广播与联网",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · GMSDozeFixFramework",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),
    HyperFeature(
        id = "system_framework_hook_entry",
        title = "解除导航断充保护限制",
        summary = "允许导航自动断充保护在非导航场景下使用",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · HookEntry",
        license = "AGPL-3.0",
        defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_UNDERLYING,
    ),

    // 快捷菜单清理
    HyperFeature(
        id = "system_framework_clean_share_menu",
        title = "清理分享菜单",
        summary = "从系统分享面板中隐藏指定应用",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · CleanShareMenu",
        license = "AGPL-3.0",
        defaultEnabled = false,
        config = listOf(
            HyperAppSelection(
                key = "system_framework_clean_share_menu.packages",
                summary = "被选中的应用会从分享目标列表中移除",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_SHORTCUTS,
    ),
    HyperFeature(
        id = "system_framework_clean_open_menu",
        title = "清理打开方式菜单",
        summary = "从链接和文件的打开方式列表中隐藏指定应用",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · CleanOpenMenu",
        license = "AGPL-3.0",
        defaultEnabled = false,
        config = listOf(
            HyperAppSelection(
                key = "system_framework_clean_open_menu.packages",
                summary = "被选中的应用会从 VIEW 解析结果中移除",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_SHORTCUTS,
    ),
    HyperFeature(
        id = "system_framework_clean_process_text_menu",
        title = "清理文本处理菜单",
        summary = "从划词处理菜单中隐藏指定应用",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · CleanProcessTextMenu",
        license = "AGPL-3.0",
        defaultEnabled = false,
        config = listOf(
            HyperAppSelection(
                key = "system_framework_clean_process_text_menu.packages",
                summary = "从文本处理菜单移除所选应用",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_SHORTCUTS,
    ),

    // 引导式访问
    HyperFeature(
        id = "system_framework_guided_access",
        title = "引导式访问增强",
        summary = "三秒电源键退出，可屏蔽系统栏",
        scopes = listOf("system_server", "systemui", "launcher"),
        origin = "西米露 / HyperCeiler · SystemLockApp；UiLockApp；GuidedAccessHome",
        license = "AGPL-3.0",
        defaultEnabled = false,
        options = listOf(
            HyperOption(
                id = "system_framework_guided_access_status",
                title = "屏蔽状态栏与导航条",
                summary = "固定应用期间隐藏状态栏、手势条和临时系统栏",
                defaultEnabled = false,
            ),
            HyperOption(
                id = "system_framework_guided_access_exit_lockscreen",
                title = "退出后立即锁屏",
                summary = "结束固定应用时要求重新解锁",
                defaultEnabled = false,
            ),
            HyperOption(
                id = "system_framework_guided_access_block_dialog",
                title = "跳过系统固定确认弹窗",
        summary = "发起固定时直接进入，不显示确认弹窗",
                defaultEnabled = false,
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_LOCK_TASK,
    ),

    // 输入法
    HyperFeature(
        id = "system_framework_miui_ime_unlock",
        title = "解锁输入法列表权限",
        summary = "允许指定小米输入法读取并展示完整输入法切换列表",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · MiuiImeUnlockSystem",
        license = "AGPL-3.0",
        defaultEnabled = false,
        config = listOf(
            HyperAppSelection(
                key = SYSTEM_FRAMEWORK_MIUI_IME_PACKAGES,
                summary = "只有选中的输入法会绕过应用可见性查询限制",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_INPUT,
    ),
    HyperFeature(
        id = "system_framework_aosp_ime",
        title = "AOSP 输入法导航栏兼容",
        summary = "补齐 AOSP 输入法手势导航栏状态",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · MiAospImeSystem",
        license = "AGPL-3.0",
        defaultEnabled = false,
        config = listOf(
            HyperAppSelection(
                key = SYSTEM_FRAMEWORK_AOSP_IME_PACKAGES,
                summary = "只对选中的输入法应用导航栏兼容逻辑",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_INPUT,
    ),

    // 平板输入
    HyperFeature(
        id = "system_framework_mipad_stylus_gesture",
        title = "忽略触控笔按键手势",
        summary = "阻止触控笔按键长按触发系统手势",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · IgnoreStylusKeyGesture",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "只在 smallestScreenWidthDp 不小于 600 的平板设备生效",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_MIPAD,
    ),
    HyperFeature(
        id = "system_framework_mipad_stylus_bluetooth",
        title = "移除触控笔蓝牙限制",
        summary = "让兼容触控笔无需连接官方触控笔蓝牙即可工作",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · RemoveStylusBluetoothRestriction",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "只在平板设备且宿主存在触控笔监听器时生效",
        config = listOf(
            HyperChoice(
                key = SYSTEM_FRAMEWORK_MIPAD_STYLUS_VERSION,
                title = "触控驱动版本",
                entries = stylusVersionChoices,
                summary = "默认按上游使用版本 2；旧硬件可尝试版本 1",
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_MIPAD,
    ),
    HyperFeature(
        id = "system_framework_mipad_restore_esc",
        title = "恢复 ESC 键功能",
        summary = "阻止平板系统把 ESC 键强制映射为返回",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · RestoreEsc",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "只在平板设备生效",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_MIPAD,
    ),
    HyperFeature(
        id = "system_framework_mipad_gesture_fingers",
        title = "交换手势所需手指数",
        summary = "将截屏与分屏手势改为四指，将切换应用手势改为三指",
        scopes = listOf("system_server"),
        origin = "西米露 / HyperCeiler · SetGestureNeedFingerNum",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "只在平板设备生效",
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_MIPAD,
    ),

    // 音效
    HyperFeature(
        id = "system_framework_misound_effect",
        title = "耳机音效自动切换",
        summary = "连接耳机时自动切换到指定音效，并可记忆每个设备的设置",
        scopes = listOf("system_server", "systemui", "misound"),
        origin = "西米露 / HyperCeiler · AutoEffectSwitchForSystem；NewAutoSEffSwitch",
        license = "AGPL-3.0",
        defaultEnabled = false,
        requirement = "需要同时启用系统框架、系统界面与音质音效作用域",
        config = listOf(
            HyperChoice(
                key = MISOUND_LOCK_SELECTION_KEY,
                title = "连接耳机时锁定选择",
                summary = "避免音效磁贴或设置页在播放期间意外切换",
                entries = MISOUND_LOCK_SELECTION_CHOICES,
            ),
            HyperChoice(
                key = MISOUND_REMEMBER_DEVICE_KEY,
                title = "按设备记忆音效",
                summary = "再次连接同一副耳机时恢复上次选择的音效",
                entries = MISOUND_REMEMBER_DEVICE_CHOICES,
            ),
            HyperChoice(
                key = MISOUND_DEFAULT_EFFECT_KEY,
                title = "默认音效",
                summary = "没有设备记忆时应用；需要设备支持对应音效",
                entries = MISOUND_DEFAULT_EFFECT_CHOICES,
            ),
        ),
        group = ScopeFeatureGroup.SYSTEM_FRAMEWORK_AUDIO,
    ),
)
