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

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.YGHFv.HyperExtend.core.HostApps
import io.github.YGHFv.HyperExtend.core.HyperFeature
import io.github.YGHFv.HyperExtend.core.HyperScope
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 界面小件 —— 全模块唯一一套排版词汇。
 *
 * 硬规矩（写在最前面，因为违背它不会报错、只会让界面慢慢变乱）：
 *
 * - 主文本一律用 miuix `Text` 的默认字号；小字**只能**走 [SecondaryText]（12sp + 次要色）。
 *   层级靠颜色和位置表达，不要在调用点自己写 `fontSize`。
 * - 卡片的 12dp 外边距只由 [SettingsCard] / [RecordCard] 给，调用点不要再套 `padding`。
 * - [GroupTitle] 不要传 `insideMargin`：默认的 28dp 才与卡片内文字左对齐。
 * - miuix 的 `Text` 不解析 Markdown，星号会原样显示 —— 要强调就用词序和分行。
 */

/** 副文本 / 说明文字的字号。全模块只有这一个「小字」。 */
private val SECONDARY = 13.sp

/** 卡片外边距。写在一处，避免十几处里漏掉一处导致整屏看起来「乱」。 */
internal val CARD_MARGIN = 16.dp

/** 分组标题。不要传 insideMargin：默认值才是与卡片内文字对齐的那个。 */
@Composable
internal fun GroupTitle(text: String) {
    SmallTitle(text = text)
}

/**
 * 设置 / 关于页的标准卡片。
 *
 * `insideMargin` 默认 [PaddingValues] 全 0 是刻意的：行内边距由各行组件自给（16dp），
 * miuix 原生偏好行（`SwitchPreference` 等）才能撑满整行、分隔线才能顶到卡片边缘。
 * 只有「卡片里装的不是标准行」（空状态、日志正文之类）才需要传。
 */
@Composable
internal fun SettingsCard(
    insideMargin: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        cornerRadius = 24.dp,
        modifier = Modifier
            .padding(horizontal = CARD_MARGIN)
            .padding(bottom = CARD_MARGIN),
        insideMargin = insideMargin,
        content = content,
    )
}

/**
 * 一个功能入口行：左侧标题 + 说明，右侧箭头，**整行可点**。
 *
 * 用于分组和多选项功能；单开关功能由 [FeaturePreference] 直接呈现开关。
 * 配置型功能（NFC 卡面）的「已配置」状态用 [statusText] 表达。
 */
@Composable
internal fun FeatureRow(
    title: String,
    summary: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    statusText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                SecondaryText(summary)
            }
            if (!statusText.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                SecondaryText(
                    text = statusText,
                    color = if (enabled) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Scope lists and search results share the same control/entry decision. */
@Composable
internal fun FeaturePreference(
    feature: HyperFeature,
    switches: Map<String, Boolean>,
    strings: Map<String, String>,
    onSwitch: (String, Boolean) -> Unit,
    onOpenFeature: (HyperFeature) -> Unit,
    summary: String = feature.summary,
) {
    if (feature.hasDetailPage) {
        FeatureRow(
            title = feature.title,
            summary = summary,
            onClick = { onOpenFeature(feature) },
            statusText = feature.configKey?.let { key ->
                if (strings[key].isNullOrBlank()) "未配置" else "已配置"
            },
        )
    } else {
        SwitchPreference(
            title = feature.title,
            summary = summary,
            checked = switches[feature.id] == true,
            onCheckedChange = { onSwitch(feature.id, it) },
        )
    }
}

/** Miuix 原生浮层选择菜单：背景遮罩、圆角菜单、当前项主色和勾选标记。 */
@Composable
internal fun ChoiceRow(
    title: String,
    summary: String?,
    currentLabel: String,
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    top.yukonga.miuix.kmp.preference.OverlayDropdownPreference(
        title = title,
        summary = summary,
        items = options,
        selectedIndex = selectedIndex,
        enabled = enabled,
        onSelectedIndexChange = onSelect,
    )
}

/**
 * 一行「拖一个数」。
 *
 * 用标题 + 当前值的两栏排法（而不是把值塞进标题）：值是这一行唯一会变的东西，
 * 固定在右边才能让用户拖动时眼睛不用跟着数字跑。
 *
 * 显示的字由 [format] 从**当前拖动值**算出来（而不是由调用方传一个固定字符串），
 * 否则拖的时候数字不动，看起来像卡住了。[onFinished] 才写设置：每移动一格写一次 prefs
 * 并投影给框架，是几十次毫无意义的跨进程握手。
 */
@Composable
internal fun NumberRow(
    title: String,
    summary: String?,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onFinished: (Float) -> Unit,
) {
    var draft by remember(value, valueRange) { mutableStateOf(value.coerceIn(valueRange)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title)
                if (!summary.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    SecondaryText(summary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(format(draft))
        }
        Spacer(Modifier.height(4.dp))
        top.yukonga.miuix.kmp.basic.Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onFinished(draft) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 一行「填一串文本」。
 *
 * ## 为什么不是「边打字边存」
 *
 * 设置每写一次都会投影一遍给框架（见 `config/HyperSettings.syncToFramework`），
 * 那是跨进程的。空格、退格、拼音候选都会触发一次改动，边打边存等于把一次输入放大成
 * 十几次跨进程握手。所以这里编辑的是**草稿**，真正的写入只发生在两处：
 * 焦点离开这一行，或点右边的「保存」。
 *
 * ## 为什么草稿用 `remember(value)` 而不是 `remember(key)`
 *
 * 键用 `value`：外部把值改掉（导入备份、恢复默认）时草稿要跟着走，否则界面上显示的还是
 * 用户上一次敲进去的那串、而设置里已经是另一个值 —— 这种「显示的和实际的不一致」
 * 是用户最难自己发现的一类问题。
 */
@Composable
internal fun TextInputRow(
    title: String,
    summary: String?,
    value: String,
    placeholder: String,
    onCommit: (String) -> Unit,
    validate: (String) -> String? = { null },
) {
    var draft by remember(value) { mutableStateOf(value) }
    var committed by remember(value) { mutableStateOf(value) }
    val dirty = draft != committed
    val error = validate(draft)
    val commit = {
        if (dirty && error == null) {
            committed = draft
            onCommit(draft)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title)
                if (!summary.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    SecondaryText(summary)
                }
            }
            if (dirty) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "保存",
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = error == null, onClick = commit)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            textStyle = TextStyle(
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 15.sp,
            ),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                // 焦点离开这一行就是「我改完了」的自然信号，不需要用户再去找按钮。
                .onFocusChanged { state -> if (!state.isFocused) commit() },
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) SecondaryText(placeholder)
                    inner()
                }
            },
        )
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            SecondaryText(error)
        }
    }
}
@Composable
internal fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    val color = valueColor ?: MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Measure the label first so a long value cannot squeeze it into vertical text.
            Text(label)
            if (value.length <= LONG_VALUE_THRESHOLD) {
                Spacer(Modifier.width(12.dp))
                Text(value, modifier = Modifier.weight(1f), color = color, textAlign = TextAlign.End)
            }
        }
        if (value.length > LONG_VALUE_THRESHOLD) {
            Spacer(Modifier.height(4.dp))
            SecondaryText(value, color = color)
        }
    }
}

private const val LONG_VALUE_THRESHOLD = 24

/**
 * 全模块唯一的小字（12sp + 次要色）。需要小字就找它，别另起一种字号。
 *
 * `textAlign` 只在「小字要给某个图形界面当标题」时传（例如卡面预览格下面的
 * 「当前卡面」）—— 那种场合文字居中才对得上下面的方块，而不是齐左。
 */
@Composable
internal fun SecondaryText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = SECONDARY,
        color = color ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = textAlign ?: TextAlign.Unspecified,
    )
}

/**
 * 卡片内的说明文字。
 *
 * 默认使用次要文字颜色，操作反馈可用主题主色强调，不将说明渲染成红字。
 */
@Composable
internal fun HintText(text: String, color: Color? = null, horizontalPadding: Dp = 16.dp) {
    SecondaryText(
        text = text,
        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 6.dp),
        color = color,
    )
}

/**
 * 卡片里的一个动作行：整行可点、文字居中。
 * 用文字居中而不是靠右的小按钮 —— 靠右按钮的左边距与卡片里其它行对不齐，看起来像没对齐的 bug。
 * [danger] 给破坏性动作用错误色。
 */
@Composable
internal fun CardActionRow(
    label: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MiuixTheme.colorScheme.disabledPrimary
        danger -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, textAlign = TextAlign.Center)
    }
}

/** 卡片内的段落分隔线：0.5dp、outline 半透明，上下各留 8dp。 */
@Composable
internal fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        thickness = 0.5.dp,
        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
    )
}

/**
 * 相邻同类条目之间的分隔线：两侧留 16dp、不留上下空白。
 * 密排列表（日志行、功能行）用它；每行都塞 8dp 空白会把十几条撑成一屏半。
 */
@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.6f),
    )
}

/**
 * 首页的一个作用域入口行：桌面图标 + 名称 + 状态 + 右箭头，整行可点。
 *
 * ## 为什么放真实的应用图标
 *
 * 「系统界面」「小米智能卡」这些名字是抽象的，用户不一定知道对应桌面上哪个图标；
 * 而**没装的应用**放上图标后一眼就能看出是「这里没有」，不用去读 [installed] 那行小字。
 * 这是唯一一处把宿主身份视觉化的地方。
 *
 * ## 状态行只报事实
 *
 * 「已启用 2 / 3」用的是**生效中**的功能数（见 `enabledCountInScope`），
 * 与用户在这个入口里能看到的那几行严格对应，不乱加子项。
 */
@Composable
internal fun ScopeEntryRow(
    scope: HyperScope,
    enabledCount: Int,
    totalCount: Int,
    installed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = scope.iconPackage)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(scope.title)
            Spacer(Modifier.height(3.dp))
            SecondaryText(
                text = if (installed) {
                    "已启用 $enabledCount / $totalCount"
                } else {
                    "未安装 —— 这台设备上没有它"
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 一次查清这些作用域宿主装了没有。
 *
 * ## 为什么单独抽出来
 *
 * `PackageManager.getApplicationInfo` 是一次跨进程调用，几个宿主叠加起来是几十毫秒级的
 * 主线程阻塞 —— 放在组合函数里直接跑，首帧就会肉眼可见地慢一拍。
 * 这里统一丢到 IO 线程去查，界面先用「都没装」占位，查完再刷新（`produceState`）。
 * 与 `remember { }` 的区别正在于此：`remember` 的 lambda 仍然在**组合阶段**执行。
 *
 * ## 为什么用 [HyperScope.id] 做键
 *
 * 调用点拿到的是作用域列表，而显示状态时手头往往只有 scope 对象；
 * 用 id 做键，`installed[scope.id]` 在任何一处都读得到同一个结论。
 */
@Composable
internal fun rememberInstalledScopes(scopes: List<HyperScope>): Map<String, Boolean> {
    val context = LocalContext.current
    val installed by produceState(initialValue = emptyMap(), scopes) {
        value = withContext(Dispatchers.IO) {
            scopes.associate { it.id to HostApps.isInstalled(context, it.iconPackage) }
        }
    }
    return installed
}

/**
 * 宿主应用的桌面图标。
 *
 * 图标在**组合期之外**加载（见 [rememberInstalledScopes] 里同样的理由）：
 * `PackageManager.getApplicationIcon` 要走一次跨进程查询，放进组合体里每次重组都会查一遍。
 *
 * 取不到图标（应用没装，或被裁剪过的 ROM 拒绝查询）时退化成一个同尺寸的空位方块 ——
 * 不画任何东西会让行高塌掉半截，看起来像布局坏了。
 */
@Composable
internal fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName, size) {
        // 画 bitmap 放后台线程：它是纯 CPU 工作，而这里是列表行 —— 主线程只该负责贴图。
        value = withContext(Dispatchers.IO) { loadAppIcon(context, packageName, size) }
    }
    val loaded = icon
    val shape = RoundedCornerShape(percent = 24)
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(shape),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
        )
    }
}

private fun loadAppIcon(context: Context, packageName: String, size: Dp): ImageBitmap? = runCatching {
    val density = context.resources.displayMetrics.density
    val px = (size.value * density).toInt().coerceAtLeast(1)
    val drawable = context.packageManager.getApplicationIcon(packageName)
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    drawable.setBounds(0, 0, px, px)
    drawable.draw(canvas)
    bitmap.asImageBitmap()
}.getOrNull()
