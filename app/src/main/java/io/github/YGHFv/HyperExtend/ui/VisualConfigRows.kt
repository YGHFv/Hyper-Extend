/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import io.github.YGHFv.HyperExtend.core.HyperAppSelection
import io.github.YGHFv.HyperExtend.core.HyperColor
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class SelectableApp(val pkg: String, val label: String, val info: ApplicationInfo?)

@Composable
internal fun AppSelectionRow(row: HyperAppSelection, value: String, onCommit: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selected = remember(value) { SystemUiCustomSettings.packages(value) }
    CardActionRow("${row.title} · 已选 ${selected.size} 个") { open = true }
    if (selected.isEmpty()) HintText("尚未选择应用，此功能暂不影响任何通知。")
    if (open) AppSelectionDialog(value, onDismiss = { open = false }) {
        onCommit(it)
        open = false
    }
}

@Composable
private fun AppSelectionDialog(value: String, onDismiss: () -> Unit, onCommit: (String) -> Unit) {
    val context = LocalContext.current
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val selected = remember(draft) { SystemUiCustomSettings.packages(draft) }
    val initial = remember(value) { SystemUiCustomSettings.packages(value) }
    var query by rememberSaveable { mutableStateOf("") }
    var systemApps by rememberSaveable { mutableStateOf(false) }
    var onlySelected by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val apps by produceState<Result<List<SelectableApp>>?>(null, context, reload, value) {
        this.value = null
        this.value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val installed = pm.getInstalledApplications(0).map { info ->
                    SelectableApp(info.packageName, runCatching { info.loadLabel(pm).toString() }
                        .getOrDefault(info.packageName), info)
                }
                val missing = initial - installed.map { it.pkg }.toSet()
                (installed + missing.map { SelectableApp(it, "未安装或当前用户不可用", null) })
                    .sortedWith(compareByDescending<SelectableApp> { it.pkg in initial }
                        .thenBy(java.text.Collator.getInstance()) { it.label }.thenBy { it.pkg })
            }
        }
    }
    val filtered = remember(apps, query, systemApps, onlySelected, selected) {
        apps?.getOrNull().orEmpty().filter {
            (!onlySelected || it.pkg in selected) &&
                (systemApps || it.info == null || it.info.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.pkg in selected) &&
                (it.label.contains(query.trim(), true) || it.pkg.contains(query.trim(), true))
        }
    }
    PickerDialog("选择应用", onDismiss) {
        BasicTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.secondaryContainer).padding(14.dp)
                .semantics { contentDescription = "搜索应用名称或包名" },
            decorationBox = { inner -> Box { if (query.isEmpty()) SecondaryText("搜索应用名称或包名"); inner() } },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                SecondaryText("已选 ${selected.size} 个 · 仅作用于当前用户可选择的应用", modifier = Modifier.padding(16.dp))
                SwitchPreference(title = "显示系统应用", checked = systemApps, onCheckedChange = { systemApps = it })
                SwitchPreference(title = "只看已选", checked = onlySelected, onCheckedChange = { onlySelected = it })
            }
            when {
                apps == null -> item { HintText("正在读取应用列表…") }
                apps?.isFailure == true -> item { CardActionRow("读取失败，点击重试") { reload++ } }
                filtered.isEmpty() -> item { HintText("没有匹配的应用") }
            }
            items(filtered, key = { it.pkg }) { app ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.pkg, reload) {
                        this.value = withContext(Dispatchers.IO) {
                            runCatching { app.info?.loadIcon(context.packageManager)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
                        }
                    }
                    Box(Modifier.padding(start = 16.dp).size(36.dp), contentAlignment = Alignment.Center) {
                        icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                    }
                    Box(Modifier.weight(1f)) {
                        SwitchPreference(title = app.label, summary = app.pkg, checked = app.pkg in selected,
                            onCheckedChange = { checked ->
                                draft = SystemUiCustomSettings.serializePackages(if (checked) selected + app.pkg else selected - app.pkg)
                            })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { CardActionRow("清空选择") { draft = "" } }
            Box(Modifier.weight(1f)) { CardActionRow("保存 (${selected.size})") { onCommit(SystemUiCustomSettings.serializePackages(selected)) } }
        }
        CardActionRow("取消", onClick = onDismiss)
    }
}

@Composable
internal fun ColorSelectionRow(row: HyperColor, value: String, onCommit: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val color = SystemUiCustomSettings.color(value)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { CardActionRow("${row.title} · ${if (color == null) "跟随系统" else "已自定义"}") { open = true } }
        if (color != null) Box(Modifier.padding(end = 20.dp).size(28.dp).clip(CircleShape).background(Color(color)))
    }
    if (!open) return
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val initial = remember(value) { FloatArray(3).also { android.graphics.Color.colorToHSV(color ?: 0xff4285f4.toInt(), it) } }
    var hue by rememberSaveable(value) { mutableFloatStateOf(initial[0]) }
    var saturation by rememberSaveable(value) { mutableFloatStateOf(initial[1]) }
    var brightness by rememberSaveable(value) { mutableFloatStateOf(initial[2]) }
    fun update() { draft = SystemUiCustomSettings.colorText(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))) }
    PickerDialog("选择主题色", { open = false }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                val preview = SystemUiCustomSettings.color(draft)
                Box(Modifier.padding(16.dp).fillMaxWidth().height(90.dp).clip(RoundedCornerShape(18.dp))
                    .background(preview?.let { Color(it) } ?: MiuixTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    val light = preview != null && (android.graphics.Color.red(preview) * 299 +
                        android.graphics.Color.green(preview) * 587 + android.graphics.Color.blue(preview) * 114) > 150000
                    Text(if (preview == null) "跟随系统" else "主题种子色预览", color = if (preview == null) MiuixTheme.colorScheme.onSurface else if (light) Color.Black else Color.White)
                }
                listOf("#4285F4", "#009688", "#43A047", "#F9AB00", "#F4511E", "#D81B60", "#795548", "#607D8B")
                    .chunked(4).forEach { colors ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            colors.forEach { swatch ->
                                val picked = draft.equals(swatch, true)
                                Box(Modifier.size(48.dp).border(if (picked) 3.dp else 0.dp, MiuixTheme.colorScheme.primary, CircleShape)
                                    .padding(5.dp).clip(CircleShape).background(Color(SystemUiCustomSettings.color(swatch)!!))
                                    .semantics { contentDescription = "预设颜色 $swatch${if (picked) "，已选中" else ""}" }
                                    .clickable(role = Role.Button) {
                                        draft = swatch
                                        val hsv = FloatArray(3)
                                        android.graphics.Color.colorToHSV(SystemUiCustomSettings.color(swatch)!!, hsv)
                                        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                                    })
                            }
                        }
                    }
                ColorSlider("色相", hue, 0f..360f) { hue = it; update() }
                ColorSlider("饱和度", saturation, 0f..1f) { saturation = it; update() }
                ColorSlider("明亮度", brightness, 0f..1f) { brightness = it; update() }
                HintText("预览仅展示种子色，实际系统主题会按深浅模式生成。保存后重启系统界面生效。")
                CardActionRow("恢复跟随系统") { draft = "" }
            }
        }
        CardActionRow("保存") { onCommit(draft); open = false }
        CardActionRow("取消") { open = false }
    }
}

@Composable
private fun ColorSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title)
        top.yukonga.miuix.kmp.basic.Slider(value = value, onValueChange = onChange, valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title })
    }
}

@Composable
private fun PickerDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f).imePadding().clip(RoundedCornerShape(24.dp))
            .background(MiuixTheme.colorScheme.surface)) {
            Text(title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            content()
        }
    }
}
