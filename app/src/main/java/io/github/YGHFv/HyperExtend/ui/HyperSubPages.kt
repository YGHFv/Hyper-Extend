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

package io.github.YGHFv.HyperExtend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import android.content.Context
import androidx.compose.ui.unit.dp
import io.github.YGHFv.HyperExtend.BuildConfig
import io.github.YGHFv.HyperExtend.config.HyperSettings
import io.github.YGHFv.HyperExtend.core.OPEN_SOURCE_PROJECTS
import io.github.YGHFv.HyperExtend.core.FrameworkBridge
import io.github.YGHFv.HyperExtend.core.HyperFeature
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.RootAccess
import io.github.YGHFv.HyperExtend.core.SCOPES
import io.github.YGHFv.HyperExtend.core.entryScope
import io.github.YGHFv.HyperExtend.core.entryGroup
import io.github.YGHFv.HyperExtend.core.isFeatureActive
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.core.searchFeatures
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 日志页最多显示多少行。300 条的环形缓冲全画出来只会让长按选择都变得难以操作。 */
private const val MAX_LOG_ROWS = 120

/** 宿主日志文件名。与注入侧 `ModuleLog.attachFileSink` 的落点严格对应。 */
private const val HOST_LOG_NAME = "hyperextend.log"

/**
 * 读取全部作用域宿主的日志文件。**必须在后台线程调用**（root 调用会阻塞）。
 *
 * 每个宿主最多显示最后 80 行 —— 排查注入与挂载问题只需要「最近发生了什么」，
 * 500KB 全文塞进 Compose 只会让列表卡住。
 */
internal fun readHostLogs(context: Context): List<Pair<String, String>> = SCOPES.map { scope ->
    val dir: String? = if (scope.id == "system_server") {
        // system_server 没有应用意义上的私有目录，它的「数据目录」就是 /data/system 本身。
        "/data/system"
    } else {
        runCatching {
            context.packageManager.getApplicationInfo(scope.iconPackage, 0).dataDir
        }.getOrNull() ?: "/data/user/0/${scope.process}"
    }
    val text = if (scope.id == "system_server") {
        RootAccess.catText("$dir/$HOST_LOG_NAME")
    } else {
        RootAccess.catText("$dir/files/$HOST_LOG_NAME")
    }.orEmpty()
    Pair(
        scope.title,
        text.lines().takeLast(80).joinToString("\n"),
    )
}

// ------------------------------------------------------------------ 搜索

/**
 * 搜索页（从首页右上角进入，整页替换）。
 *
 * 结果里显示的是**功能**而不是子项：用户搜到「通知」时想去的是那个功能的开关页，
 * 而不是一个孤立的小开关（它的总开关可能还关着，那样搜到了也白搭）。
 *
 * 副标题里报的是**作用域名**而不是别的分类：作用域是首页上的字，也是「改完要生效谁」的答案，
 * 用它做定位信息，用户搜到之后能立刻知道该去哪一页。
 * 「已启用 / 已关闭」的判据走 [isFeatureActive]：配置型功能（NFC 卡面）没有开关，
 * 它是不是生效取决于「配没配」，这里若写成 `switches[...]` 会永远显示「已关闭」。
 */
@Composable
internal fun SearchPage(
    switches: Map<String, Boolean>,
    strings: Map<String, String>,
    onSwitch: (String, Boolean) -> Unit,
    onOpenFeature: (HyperFeature) -> Unit,
    onBack: () -> Unit,
) {
    // 关键词与「展开全部」都走 rememberSaveable：用户在搜索页查到一半被叫走、
    // 回来时输入框还应该是他刚打的那几个字（`remember` 活不过 Activity 重建）。
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(query) { searchFeatures(query) }
    val hitSections = remember(hits) {
        val (details, switches) = hits.partition { it.feature.hasDetailPage }
        listOf("功能设置" to details, "快捷开关" to switches).filter { it.second.isNotEmpty() }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberScrollState()


    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = "搜索",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack, backgroundColor = Color.Transparent) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState)
                .padding(top = padding.calculateTopPadding())
                .padding(bottom = padding.calculateBottomPadding())
                .padding(vertical = 4.dp),
        ) {
            SettingsCard {
                TextInputField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索功能或作用域",
                )
            }

            when {
                query.isBlank() -> {
                    HintText(
                        "搜索功能名称、来源或作用域",
                        horizontalPadding = 28.dp,
                    )
                }

                hits.isEmpty() -> {
                    HintText(
                        "未找到相关功能",
                        horizontalPadding = 28.dp,
                    )
                }

                else -> {
                    hitSections.forEach { (title, sectionHits) ->
                        GroupTitle(if (hitSections.size > 1) title else "命中 ${hits.size} 项")
                        SettingsCard {
                            sectionHits.forEachIndexed { index, hit ->
                                if (index > 0) RowDivider()
                                val matched = hit.matchedOption
                                FeaturePreference(
                                    feature = hit.feature,
                                    switches = switches,
                                    strings = strings,
                                    onSwitch = onSwitch,
                                    onOpenFeature = onOpenFeature,
                                    summary = buildString {
                                        // 报**归属入口**而不是全部宿主：作用域是首页上的字，
                                        // 也是「改动生效要作用到谁」的答案。四个宿主全列出来反而没人读。
                                        append(hit.feature.entryScope?.title.orEmpty())
                                        hit.feature.entryGroup?.let { append(" / ${it.title}") }
                                        append(
                                            if (isFeatureActive(hit.feature, switches, strings)) {
                                                " · 已启用"
                                            } else {
                                                " · 未启用"
                                            },
                                        )
                                        if (matched != null) {
                                            append(" · 命中：")
                                            append(matched.title)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ------------------------------------------------------------------ 关于

/**
 * 关于页（底栏第三个 tab）。
 *
 * **诊断信息与日志都放在这里**，因为模块常年无界面：一旦用户打开它，
 * 多半就是「某个开关按了没反应」，此时这一页是他唯一能自查的地方。
 *
 * 日志只覆盖**模块 App 进程**：被注入进程（SystemUI、system_server）各有各的缓冲，
 * 跨进程搬运的代价远大于价值。要查注入侧的问题得看 logcat（tag `HyperExtend`）——
 * 这一句必须写在界面上，否则用户会以为「日志是空的 = 什么都没发生」。
 *
 * 作为 tab 而不是子页面：它不接收返回键，也不需要返回按钮 —— 底栏就是退出方式，
 * 多一个「返回」会和底栏的语义打架（返回到哪？）。
 */
@Composable
internal fun AboutTab(
    scrollBehavior: ScrollBehavior,
    padding: PaddingValues,
    onOpenLogs: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()
    var showRecovery by rememberSaveable { mutableStateOf(false) }
    var fuseResetMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(scrollState)
            .padding(top = padding.calculateTopPadding())
            .padding(bottom = padding.calculateBottomPadding())
            .padding(vertical = 4.dp),
    ) {
        GroupTitle("模块状态")
        SettingsCard {
            // 构建模式并进版本行而不是单占一行：排查「这个包到底装的是哪个」时，
            // 它和版本号是同一个问题的两个部分。
            InfoRow(
                label = "构建版本",
                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                    if (BuildConfig.DEBUG) "debug" else "release",
            )
            InfoRow(label = "构建时间", value = BUILD_TIME_DISPLAY)
            InfoRow(label = "设置摘要", value = HyperSettings.describe(context))
            // 收集状态流，确保框架稍后绑定时状态同步更新。
            val connected by FrameworkBridge.connected.collectAsState()
            InfoRow(
                label = "框架服务",
                value = if (connected) "已连接" else "未连接",
            )
        }

        GroupTitle("开源致谢")
        SettingsCard {
            OPEN_SOURCE_PROJECTS.forEachIndexed { index, project ->
                if (index > 0) RowDivider()
                ArrowPreference(
                    title = project.name,
                    onClick = {
                        runCatching { uriHandler.openUri(project.url) }
                            .onFailure { ModuleLog.warn("Cannot open project link: ${project.name}") }
                    },
                )
            }
        }

        GroupTitle("诊断")
        SettingsCard {
            ArrowPreference(title = "日志", summary = "${ModuleLog.errorCount} 条异常", onClick = onOpenLogs)
        }

        // 这一节刻意写得像操作手册而不是功能介绍：它是「系统已经不正常了」时才用到的，
        // 那种情况下用户没有耐心读解释，需要的是能照着敲的命令。
        GroupTitle("紧急停用")
        SettingsCard {
            CardActionRow(
                label = if (showRecovery) "收起恢复说明" else "停用与恢复说明",
                onClick = { showRecovery = !showRecovery },
            )
            if (showRecovery) {
                HintText("开机异常时，可通过以下任一方式停用所有 Hook。")
                HintText("方式一（需 root）：adb shell su -c 'setprop persist.sys.hyperextend.disabled 1'")
                HintText("方式二：adb shell touch /data/local/tmp/hyperextend.disabled")
                HintText("系统框架、系统界面分别记录启动；五分钟内三次重启触发持久熔断。系统界面独立熔断只停用其自身 Hook。")
                HintText(
                    "恢复：属性设回 0，删除停用标记 /data/local/tmp/hyperextend.disabled" +
                        "（或 /sdcard/HyperExtend/disable）；解除自动熔断后重启设备。",
                )
                HintText(
                    "无法进入模块时：将 persist.sys.hyperextend.framework_disabled 设为 0，" +
                        "删除 /data/system/hyperextend_bootguard 和系统界面私有 files/hyperextend_systemui_bootguard 后重启。先关闭可疑功能再解除熔断。",
                )
            }
            CardDivider()
            CardActionRow(
                label = "解除自动熔断",
                onClick = {
                    if (!HyperSettings.requestFrameworkFuseReset(context)) {
                        fuseResetMessage = "解除请求写入失败，请查看模块日志。"
                        return@CardActionRow
                    }
                    fuseResetMessage = "已保存，请手动重启设备；自动防砖仍开启。"
                },
            )
            fuseResetMessage?.let { HintText(it) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 构建时间。`BuildConfig.BUILD_TIME` 是编译期写死的毫秒时间戳，格式化一次即可。 */
private val BUILD_TIME_DISPLAY: String = java.text.SimpleDateFormat(
    "yyyy-MM-dd HH:mm",
    java.util.Locale.CHINA,
).format(java.util.Date(BuildConfig.BUILD_TIME))

// ------------------------------------------------------------------ 共用输入框

/**
 * 一个朴素的文本输入框。
 *
 * 用 Compose 原生的 `BasicTextField` 而不是 miuix 的 `TextField`：
 * 后者带浮动标签、前后置图标、动画状态机等一整套「表单」语义，而本模块只剩搜索这一处输入
 * （卡面图片已经改成系统相册选择器），为它引一整套表单约束不值当，
 * 也更容易在升级 miuix 时被连带改坏。颜色全部取自 `MiuixTheme`，所以外观仍是 miuix 的。
 */
@Composable
internal fun TextInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    top.yukonga.miuix.kmp.basic.TextField(
        value = value,
        onValueChange = onValueChange,
        label = placeholder,
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    )
}
