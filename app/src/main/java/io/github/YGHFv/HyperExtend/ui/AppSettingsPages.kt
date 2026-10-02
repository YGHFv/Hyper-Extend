/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 *
 * ---------------------------------------------------------------------------
 * 设置页的三个二级页面：界面 / 个性化 / 备份恢复。
 *
 * 设置页本身只有入口行（与作用域页同构），开关与动作全部住在这里。
 * 「界面」一页的条目文案直接沿用参考项目（阅微补全计划）的定案：
 * 主题 / 模糊 / 澎湃水底栏 / 液态玻璃，含义与实现一一对应，不另造词。
 */

package io.github.YGHFv.HyperExtend.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.YGHFv.HyperExtend.BuildConfig
import io.github.YGHFv.HyperExtend.R
import io.github.YGHFv.HyperExtend.config.HyperSettings
import io.github.YGHFv.HyperExtend.core.LauncherIconState
import io.github.YGHFv.HyperExtend.core.LauncherIconStyle
import io.github.YGHFv.HyperExtend.core.LauncherIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置页的二级页面清单。id 是 rememberSaveable 的存档键，定了就不要改。 */
enum class AppSettingsPage(val id: String, val title: String) {
    INTERFACE("interface", "界面"),
    PERSONALIZATION("personalization", "个性化"),
    BACKUP("backup", "备份恢复"),
    LOGS("logs", "日志"),
    ;

    companion object {
        fun fromId(id: String): AppSettingsPage? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 二级页面的公共骨架：返回顶栏 + 各页内容。
 *
 * 与作用域页同构（Scaffold + TopAppBar + 返回），整页替换、不复用底栏的 Scaffold ——
 * 二级页属于「从设置页暂时离开」，返回键与顶栏箭头都回设置页。
 */
@Composable
internal fun AppSettingsPageHost(
    page: AppSettingsPage,
    ui: UiPrefs.UiState,
    onUi: (UiPrefs.UiState) -> Unit,
    onReloadSettings: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    if (page == AppSettingsPage.LOGS) {
        LogPage(onBack)
        return
    }
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberScrollState()

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = page.title,
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
            when (page) {
                AppSettingsPage.INTERFACE -> InterfacePage(ui, onUi)
                AppSettingsPage.PERSONALIZATION -> PersonalizationPage(ui, onUi)
                AppSettingsPage.BACKUP -> BackupPage(onReloadSettings, onReset)
                AppSettingsPage.LOGS -> Unit
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ------------------------------------------------------------------ 界面

/** 主题三档在界面里显示的名字。逐字沿用参考项目（阅微补全计划）的 strings.xml。 */
private val THEME_LABELS = listOf("跟随系统", "浅色主题", "深色主题")

/**
 * 「界面」页：主题 / 模糊 / 悬浮底栏 / 液态玻璃。
 *
 * 文案与参考项目 strings.xml 逐字一致 —— 用户在两个模块之间切换时看到同一套说法，
 * 认知成本最低，这里一个词都不许自造。
 * 「液态玻璃」只对悬浮底栏有意义：非悬浮时它没有「玻璃」可谈，只在悬浮开启时出现。
 */
@Composable
private fun InterfacePage(ui: UiPrefs.UiState, onUi: (UiPrefs.UiState) -> Unit) {
    GroupTitle("界面")
    SettingsCard {
        ChoiceRow(
            title = "主题",
            summary = null,
            currentLabel = THEME_LABELS.getOrElse(ui.themeMode) { THEME_LABELS[0] },
            options = THEME_LABELS,
            selectedIndex = ui.themeMode,
            onSelect = { onUi(ui.copy(themeMode = it)) },
        )
        RowDivider()
        SwitchPreference(
            title = "模糊",
            summary = null,
            checked = ui.blurBars,
            onCheckedChange = { onUi(ui.copy(blurBars = it)) },
        )
        SwitchPreference(
            title = "悬浮底栏",
            summary = null,
            checked = ui.floatingBar,
            onCheckedChange = { onUi(ui.copy(floatingBar = it)) },
        )
        if (ui.floatingBar) {
            SwitchPreference(
                title = "液态玻璃",
                summary = null,
                checked = ui.liquidGlass,
                onCheckedChange = { onUi(ui.copy(liquidGlass = it)) },
            )
        }
    }
}

// ------------------------------------------------------------------ 个性化

/** 预览格的尺寸与圆角。参考项目同款横排选择器，一格一套配色。 */
private val ICON_PREVIEW_SIZE = 52.dp
private val ICON_PREVIEW_SHAPE = RoundedCornerShape(14.dp)

/**
 * 「个性化」页：切换应用图标 / 隐藏后台卡片 / 隐藏桌面图标。
 *
 * 三件事共用同一批 launcher 组件（见 [LauncherIcons]），所以这里不各自改组件，
 * 全部经 [LauncherIcons.apply] 一个出口；偏好落 [UiPrefs.writePersonalization]。
 * 组件操作失败时把开关回拨回实际状态并给一行错误提示 —— 静默失败会让用户
 * 以为「隐藏了」，实际图标还在桌面上。
 */
@Composable
private fun PersonalizationPage(ui: UiPrefs.UiState, onUi: (UiPrefs.UiState) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    // 组件的实际状态（IO 里查 PackageManager，组合期不做跨进程调用 —— 见 rememberInstalledScopes）。
    val actual by produceState<LauncherIconState?>(
        initialValue = null,
        ui.hideLauncherIcon,
        ui.iconStyle,
    ) {
        value = withContext(Dispatchers.IO) {
            LauncherIcons.readState(context, ui.iconStyle)
        }
    }

    GroupTitle("应用图标")
    SettingsCard {
        Column(modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 10.dp)) {
            SecondaryText("切换应用图标")
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LauncherIconStyle.entries.forEach { style ->
                    IconStylePreview(
                        style = style,
                        selected = !ui.hideLauncherIcon && ui.iconStyle == style.key,
                        enabled = !ui.hideLauncherIcon,
                        onClick = {
                            if (ui.hideLauncherIcon) return@IconStylePreview
                            // 崩溃恢复的落点：偏好与组件不一致时，进这一页就会按 preferred 修回。
                            coroutineScope.launch(Dispatchers.IO) {
                                val fallback = LauncherIconState(LauncherIconStyle.fromKey(ui.iconStyle), false)
                                val ok = LauncherIcons.apply(context, style, fallback)
                                withContext(Dispatchers.Main) {
                                if (ok) {
                                    onUi(ui.copy(iconStyle = style.key))
                                    message = "图标已切换为「${style.label}」，请等待桌面图标刷新"
                                } else {
                                    message = "切换图标失败，当前系统可能限制了组件管理"
                                }
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        SwitchPreference(
            title = "隐藏桌面图标",
            summary = "隐藏后仍可从 LSPosed 打开",
            checked = ui.hideLauncherIcon,
            onCheckedChange = { enabled ->
                coroutineScope.launch(Dispatchers.IO) {
                    val fallback = LauncherIconState(LauncherIconStyle.fromKey(ui.iconStyle), !enabled)
                    val ok = LauncherIcons.apply(
                        context,
                        if (enabled) null else LauncherIconStyle.fromKey(ui.iconStyle),
                        fallback,
                    )
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            onUi(ui.copy(hideLauncherIcon = enabled))
                            message = if (enabled) {
                                "桌面图标已隐藏；adb 恢复：am start -n ${context.packageName}/" +
                                    "${context.packageName}.ui.Home"
                            } else {
                                "桌面图标已恢复"
                            }
                        } else {
                            message = "切换图标失败，当前系统可能限制了组件管理"
                        }
                    }
                }
            },
        )
    }

    GroupTitle("后台")
    SettingsCard {
        SwitchPreference(
            title = "隐藏后台卡片",
            summary = null,
            checked = ui.hideRecentTask,
            onCheckedChange = { onUi(ui.copy(hideRecentTask = it)) },
        )
    }

    // 实际状态与偏好不一致时的提示（比如别的工具动过组件）。
    val actualState = actual
    if (actualState != null && !actualState.hidden && actualState.style.key != ui.iconStyle) {
        HintText(
            "实际生效的配色是「${actualState.style.label}」，与这里的记录不一致 —— 重新选一次即可修正。",
            color = MiuixTheme.colorScheme.error,
        )
    }
    HintText(
        message ?: "更改即时生效",
        color = if (message != null) MiuixTheme.colorScheme.primary else null,
    )
}

/** 一格配色预览：底色 + 居中的「+」前景，选中时描一圈主色边。 */
@Composable
private fun IconStylePreview(
    style: LauncherIconStyle,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = Color(style.background.toInt())
    val foreground = Color(style.foreground.toInt())
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "图标配色：${style.label}",
            tint = foreground,
            modifier = Modifier
                .size(ICON_PREVIEW_SIZE)
                .clip(ICON_PREVIEW_SHAPE)
                .background(background)
                .border(
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = when {
                            selected -> MiuixTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
                        },
                    ),
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(6.dp),
        )
        Spacer(Modifier.height(6.dp))
        SecondaryText(
            text = style.label,
            color = if (selected) MiuixTheme.colorScheme.primary else null,
        )
    }
}

// ------------------------------------------------------------------ 备份恢复

/**
 * 「备份恢复」页。从设置页原样搬过来的三个动作 —— 逻辑没有任何变化，
 * 只是入口从「和外观开关挤在一页」变成了独立页面。
 */
@Composable
private fun BackupPage(
    onReloadSettings: () -> Unit,
    onReset: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = HyperSettings.exportJson(context)
                    context.contentResolver.openOutputStream(target)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }.isSuccess
            }
            message = if (ok) "已导出到所选文件。" else "导出失败：文件打不开或没有写入权限。"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(source)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("打不开所选文件")
                    HyperSettings.importJson(context, text)
                }
            }
            outcome.onSuccess { count ->
                message = "已恢复 $count 项设置。"
                onReloadSettings()
            }.onFailure {
                message = "恢复失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    GroupTitle("功能设置")
    SettingsCard {
        CardActionRow(
            label = "备份到文件",
            onClick = { exportLauncher.launch("hyperextend-settings-${BuildConfig.VERSION_NAME}.json") },
        )
        RowDivider()
        CardActionRow(
            label = "从文件恢复",
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
        CardDivider()
        CardActionRow(label = "恢复默认设置", danger = true, onClick = { confirmReset = true })
    }
    HintText(
        message ?: "备份功能设置，不包含图片文件和界面外观。",
        color = if (message != null) MiuixTheme.colorScheme.primary else null,
    )
    top.yukonga.miuix.kmp.overlay.OverlayDialog(show = confirmReset, title = "恢复默认设置？",
        summary = "功能设置与自定义卡面图片将被清除，此操作不可撤销。",
        onDismissRequest = { confirmReset = false }) {
        CardActionRow("确认恢复", danger = true) { confirmReset = false; onReset() }
        CardActionRow("取消") { confirmReset = false }
    }
}
