/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.YGHFv.HyperExtend.core.FrameworkBridge
import io.github.YGHFv.HyperExtend.core.HyperScope
import io.github.YGHFv.HyperExtend.core.RestartKind
import io.github.YGHFv.HyperExtend.core.SafeModeSettings
import io.github.YGHFv.HyperExtend.core.SafeModeStatus
import io.github.YGHFv.HyperExtend.core.scopeById
import io.github.YGHFv.HyperExtend.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SafeModePage() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val statuses by SafeModeSettings.state.collectAsState()
    val connected by FrameworkBridge.connected.collectAsState()
    var recoveryId by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { SafeModeSettings.refreshNative(context) }
    }

    fun save(scope: HyperScope, disabled: Boolean) {
        saving = true
        coroutineScope.launch {
            val ok = withContext(Dispatchers.IO) { SafeModeSettings.setDisabled(context, scope, disabled) }
            saving = false
            message = if (ok && scope.process == "com.miui.home" && BuildConfig.LAUNCHER_NATIVE_PROTOTYPE) {
                if (disabled) "桌面安全模式已开启并重启，原功能配置保留。"
                else "已读回确认桌面原生保护恢复，并已重启桌面。原功能配置保留。"
            } else if (ok) {
                "${scope.title}：${if (disabled) "已开启安全模式，原配置保留" else "已关闭安全模式，原配置保留"}。${scope.restartHint()}"
            } else "操作未完成，未确认保护已解除。请查看桌面行的实际状态说明。"
        }
    }

    SettingsCard {
        statuses.forEach { status ->
            Row(
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 80.dp).padding(start = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(status.scope.iconPackage, size = 40.dp)
                SwitchPreference(
                    modifier = Modifier.weight(1f),
                    title = status.scope.title,
                    summary = status.detail ?: status.scope.iconPackage,
                    checked = status.disabled,
                    enabled = !saving && !status.loading,
                    onCheckedChange = { disabled ->
                        if (disabled) save(status.scope, true) else recoveryId = status.scope.id
                    },
                )
            }
        }
    }
    HintText("开启后仅停用对应宿主的模块功能，不清空原配置。桌面会自动重启并核对保护状态；其他宿主需重启生效，系统框架需重启设备。自动防砖保护始终保留。")
    if (!connected) HintText("框架未连接：更改暂存本地，待框架连接并同步后，再重启宿主生效。")
    message?.let { HintText(it) }

    val recovery = scopeById(recoveryId)
    OverlayDialog(
        show = recovery != null,
        title = "关闭安全模式？",
        summary = recovery?.let {
            val action = if (it.process == "com.miui.home" && BuildConfig.LAUNCHER_NATIVE_PROTOTYPE)
                "确认后会通过原生恢复事务重启桌面，并读回实际保护状态。" else it.restartHint()
            "将恢复「${it.title}」原有的功能配置。请先关闭可疑功能，再确认恢复。${action}如果问题再次出现，自动保护仍会停用模块功能。"
        },
        onDismissRequest = { recoveryId = "" },
    ) {
        SafetyDialogButtons(
            cancelText = "取消", confirmText = "确认恢复",
            onCancel = { recoveryId = "" },
            onConfirm = {
                recovery?.let { save(it, false) }
                recoveryId = ""
            },
        )
    }
}

/** 与项目其它对话框一致：使用卡片动作行，不另造一套按钮排版。 */
@Composable
private fun SafetyDialogButtons(cancelText: String, confirmText: String,
    onCancel: () -> Unit, onConfirm: () -> Unit) {
    CardActionRow(confirmText, onClick = onConfirm)
    CardDivider()
    CardActionRow(cancelText, onClick = onCancel)
}

/** Only acknowledge a displayed batch on user action, never on backgrounding or rotation. */
@Composable
internal fun SafeModeIncidentDialog(onOpenSafeMode: () -> Unit) {
    val context = LocalContext.current
    val statuses by SafeModeSettings.state.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var displayed by remember { mutableStateOf<List<SafeModeStatus>>(emptyList()) }
    LaunchedEffect(resumed) {
        if (resumed) withContext(Dispatchers.IO) { SafeModeSettings.refreshNative(context) }
    }

    LaunchedEffect(resumed, statuses) {
        if (resumed) displayed = statuses.filter { it.incident != null && !it.acknowledged }
    }
    fun acknowledge() {
        SafeModeSettings.acknowledge(context, displayed)
        displayed = emptyList()
    }

    OverlayDialog(
        show = resumed && displayed.isNotEmpty(),
        title = "已触发安全模式",
        summary = "检测到模块执行异常或启动保护触发，以下宿主的模块功能已停用。原有功能配置没有清空。",
        onDismissRequest = { if (resumed) acknowledge() },
    ) {
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            displayed.forEach { status ->
                HintText("${status.scope.title}（${status.scope.iconPackage}）\n${status.incident?.reason.orEmpty()}")
            }
            HintText("请先关闭可疑功能，再到「设置 → 安全模式」关闭对应开关并确认恢复。桌面会自动重启并核对原生状态；其他宿主需要重启，系统框架需重启设备。")
        }
        SafetyDialogButtons(
            cancelText = "知道了", confirmText = "前往安全模式",
            onCancel = { acknowledge() }, onConfirm = { acknowledge(); onOpenSafeMode() },
        )
    }
}

private fun HyperScope.restartHint(): String = when (restartKind) {
    RestartKind.REBOOT -> "请手动重启设备生效。"
    else -> "请重启「$title」生效。"
}
