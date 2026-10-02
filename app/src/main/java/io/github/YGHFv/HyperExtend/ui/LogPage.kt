package io.github.YGHFv.HyperExtend.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.YGHFv.HyperExtend.core.LogLevel
import io.github.YGHFv.HyperExtend.core.LogRecord
import io.github.YGHFv.HyperExtend.core.LogRecords
import io.github.YGHFv.HyperExtend.core.ModuleLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LogPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    var records by remember { mutableStateOf(LogRecords.parse("模块", ModuleLog.snapshot())) }
    var hostRecords by remember { mutableStateOf<List<LogRecord>>(emptyList()) }
    var minimumName by rememberSaveable { mutableStateOf(UiPrefs.logLevel(context)) }
    var cards by rememberSaveable { mutableStateOf(UiPrefs.logCards(context)) }
    var reversed by rememberSaveable { mutableStateOf(UiPrefs.logReversed(context)) }
    var source by rememberSaveable { mutableStateOf("模块") }
    var filterMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val visible = remember(records, hostRecords, minimumName, reversed, source) {
        LogRecords.visible(if (source == "模块") records else hostRecords, LogLevel.valueOf(minimumName), reversed)
    }
    val refresh: () -> Unit = {
        if (!loading) {
            status = null
            if (source == "模块") {
                records = LogRecords.parse("模块", ModuleLog.snapshot())
            } else {
                loading = true
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { readHostLogs(context).flatMap { (name, text) -> LogRecords.parse(name, text.lines()) } }
                    }
                    result.onSuccess { hostRecords = it }.onFailure { status = "读取失败：${it.javaClass.simpleName}" }
                    if (result.isSuccess && hostRecords.isEmpty()) status = "未读到宿主日志，请检查 root 授权。"
                    loading = false
                }
            }
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = "日志", scrollBehavior = scrollBehavior,
            navigationIcon = { LogAction(MiuixIcons.Back, "返回", onBack) },
            actions = {
                LogAction(MiuixIcons.Refresh, "刷新日志", refresh)
                Box {
                    LogAction(MiuixIcons.Sort, "筛选与排序", { filterMenu = true })
                    OverlayListPopup(show = filterMenu, alignment = PopupPositionProvider.Align.TopEnd,
                        onDismissRequest = { filterMenu = false }) {
                        ListPopupColumn {
                            LogLevel.entries.forEach { level ->
                                LogMenuRow(level.name, minimumName == level.name) {
                                    minimumName = level.name
                                    UiPrefs.setLogDisplay(context, minimumName, cards, reversed)
                                    filterMenu = false
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                            LogMenuRow("逐条卡片", cards) {
                                cards = !cards
                                UiPrefs.setLogDisplay(context, minimumName, cards, reversed)
                                filterMenu = false
                            }
                            LogMenuRow("倒序", reversed) {
                                reversed = !reversed
                                UiPrefs.setLogDisplay(context, minimumName, cards, reversed)
                                filterMenu = false
                            }
                        }
                    }
                }
                LogAction(MiuixIcons.Delete, "清空模块日志", { confirmClear = true })
                Box {
                    LogAction(MiuixIcons.MoreCircle, "更多日志操作", { moreMenu = true })
                    OverlayListPopup(show = moreMenu, alignment = PopupPositionProvider.Align.TopEnd,
                        onDismissRequest = { moreMenu = false }) {
                        ListPopupColumn {
                            LogMenuRow("模块日志", source == "模块") { source = "模块"; moreMenu = false; refresh() }
                            LogMenuRow("宿主日志 · 需要 root", source == "宿主") { source = "宿主"; moreMenu = false; refresh() }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                            LogMenuRow("复制当前结果") {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("HyperExtend logs", visible.joinToString("\n") { it.asText() }))
                                status = "已复制 ${visible.size} 条"
                                moreMenu = false
                            }
                        }
                    }
                }
            })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                HintText(if (loading) "正在读取宿主日志…" else status ?: "$source · ${visible.size} 条 · ${minimumName} 及以上", horizontalPadding = 32.dp)
            }
            if (visible.isEmpty()) item {
                SettingsCard { HintText(if (loading) "请稍候" else "暂无符合条件的日志") }
            }
            itemsIndexed(visible) { index, record ->
                if (cards) LogEntryCard(record, "$source/$index/${record.hashCode()}")
                else SelectionContainer {
                    Text(record.asText(), modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
        OverlayDialog(show = confirmClear, title = "清空模块日志？", summary = "仅清空本次模块进程记录，宿主文件不受影响。",
            onDismissRequest = { confirmClear = false }) {
            CardActionRow("清空", danger = true) { ModuleLog.clear(); records = emptyList(); confirmClear = false }
            CardActionRow("取消") { confirmClear = false }
        }
    }
}

@Composable
private fun LogAction(image: ImageVector, label: String, action: () -> Unit) {
    IconButton(onClick = action, backgroundColor = Color.Transparent) {
        Icon(imageVector = image, contentDescription = label, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun LogMenuRow(label: String, selected: Boolean = false, action: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 22.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
        Text(label, modifier = Modifier.weight(1f), color = color, fontWeight = FontWeight.Medium)
        if (selected) Text("✓", color = color, modifier = Modifier.padding(start = 20.dp))
    }
}

@Composable
private fun LogEntryCard(record: LogRecord, identity: String) {
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    val color = when (record.level) {
        LogLevel.DEBUG -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        LogLevel.INFO -> MiuixTheme.colorScheme.primary
        LogLevel.WARN -> Color(0xFFA16C16)
        LogLevel.ERROR -> MiuixTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = CARD_MARGIN),
        insideMargin = PaddingValues(16.dp), cornerRadius = 24.dp,
        onClick = { expanded = !expanded }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(record.level.name, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
            Text(record.timestamp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp).weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Spacer(Modifier.height(10.dp))
        if (record.source != "模块") SecondaryText(record.source)
        if (expanded) SelectionContainer { Text(record.message, fontSize = 14.sp) }
        else Text(record.message, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}
