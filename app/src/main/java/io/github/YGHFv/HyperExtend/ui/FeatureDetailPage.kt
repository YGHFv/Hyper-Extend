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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.YGHFv.HyperExtend.core.FeatureExtra
import io.github.YGHFv.HyperExtend.core.featureDetailPanels
import io.github.YGHFv.HyperExtend.core.HyperChoice
import io.github.YGHFv.HyperExtend.core.HyperFeature
import io.github.YGHFv.HyperExtend.core.HyperSlider
import io.github.YGHFv.HyperExtend.core.HyperText
import io.github.YGHFv.HyperExtend.core.HyperAppSelection
import io.github.YGHFv.HyperExtend.core.HyperColor
import io.github.YGHFv.HyperExtend.core.MobileSignalSettings
import io.github.YGHFv.HyperExtend.core.DualRowSignalSettings
import io.github.YGHFv.HyperExtend.core.MobileTypeTextSettings
import io.github.YGHFv.HyperExtend.core.MONET_SCHEMES
import io.github.YGHFv.HyperExtend.core.MONET_SCHEME_KEY
import io.github.YGHFv.HyperExtend.core.NFC_IMAGE_KEY
import io.github.YGHFv.HyperExtend.core.CardFaceMappings
import io.github.YGHFv.HyperExtend.core.NfcCardImage
import io.github.YGHFv.HyperExtend.core.NOTIFY_ICON_AUTO_TIMES
import io.github.YGHFv.HyperExtend.core.NOTIFY_ICON_AUTO_TIME_KEY
import io.github.YGHFv.HyperExtend.core.NOTIFY_ICON_SOURCE_KEY
import io.github.YGHFv.HyperExtend.core.NotifyIconLibrary
import io.github.YGHFv.HyperExtend.core.NotifyIconSyncSource
import io.github.YGHFv.HyperExtend.core.PasskeyProvider
import io.github.YGHFv.HyperExtend.core.WalletCardFace
import io.github.YGHFv.HyperExtend.core.WalletCardFaceCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/** 卡面预览格的高度。两列等宽、等高，左格是图、右格是入口。 */
private val SLOT_HEIGHT = 96.dp

private val SLOT_SHAPE = RoundedCornerShape(12.dp)

/** 「钱包自带卡面」那一排缩略图的尺寸。 */
private val THUMB_HEIGHT = 64.dp
private val THUMB_WIDTH = 104.dp

/**
 * 功能详情页（三级）。
 *
 * 作用域页是「拨开关」，这里是「看清这个开关到底是什么」—— 主开关、子项、以及这个功能
 * 自己的配置区。三者都是数据驱动的：主开关与子项来自 `HyperFeature.options`，
 * 配置区由 `HyperFeature.configKey` 决定，附加的选择器由 `HyperFeature.extra` 决定。
 * **这里没有一处写死功能 id**。
 *
 * ## 为什么顶部不再有「来源 / 许可 / 生效范围」卡片
 *
 * 那三行是给「这个功能从哪抄来的」用的，而用户打开这一页是来**拨开关 / 换卡面**的。
 * 每次进来先读三段与自己无关的字，只会把真正要动的东西挤到屏幕外。
 * 来源与许可保留在源码及文档，「关于」页统一展示开源项目致谢，
 * 生效范围则由作用域页的入口与右下角的提示表达。
 *
 * 功能前提说明保留在目录元数据中，不在设置界面重复展示。
 *
 * ## 配置型功能为什么把开关放在最上面
 *
 * 「没有主开关」不代表「没有开关」：NFC 卡面这类功能的子项（超级岛卡面）本身就是开关，
 * 而它管的是「这一页下面那张图作用到哪」。放在最上面才符合「先决定范围、再选图」的顺序。
 */
@Composable
internal fun FeatureDetailPage(
    feature: HyperFeature,
    switches: Map<String, Boolean>,
    strings: Map<String, String>,
    onSwitch: (String, Boolean) -> Unit,
    onString: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val configKey = feature.configKey
    val dualCompatible = feature.id != DualRowSignalSettings.FEATURE || DualRowSignalSettings.compatible(
        switches["status_bar_mobile"] == true, strings[MobileSignalSettings.MODE],
        switches[MobileSignalSettings.HIDE_SIM_1] == true, switches[MobileSignalSettings.HIDE_SIM_2] == true,
    )

    // 系统返回键等同于顶栏的返回箭头。不挂的话返回键会直接退出 Activity，
    // 用户以为只是「上一步」，其实是把整个模块界面关了。
    BackHandler(onBack = onBack)

    // 下拉刷新：重读钱包自带卡面（以及下面那份自己选的图）。**页面不重建** ——
    // 用户不该为了刷新一次数据而退出再进来，那正是「读取还要退出重进」的根源。
    var reloadTick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    val context = LocalContext.current
    DisposableEffect(context, configKey) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reloadTick++
            }
        }
        val registered = configKey != null && runCatching {
            context.contentResolver.registerContentObserver(WalletCardFaceCapture.URI, true, observer)
            true
        }.getOrDefault(false)
        onDispose {
            if (registered) context.contentResolver.unregisterContentObserver(observer)
        }
    }
    val walletFaces by produceState(
        initialValue = WalletCardFace.Result(emptyList(), ""),
        reloadTick,
        configKey,
    ) {
        if (configKey == null) {
            refreshing = false
            return@produceState
        }
        value = withContext(Dispatchers.IO) { WalletCardFace.read(context) }
        refreshing = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = feature.title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack, backgroundColor = Color.Transparent) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                reloadTick++
            },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding(),
            ),
            topAppBarScrollBehavior = scrollBehavior,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    // miuix 0.9.4 的 PullToRefresh 只把 contentPadding 用于「刷新头的偏移」，
                    // 内容本身不吃它（反编译确认：calculateTopPadding 只出现在 RefreshHeader
                    // 的 offset 上）—— 顶部 padding 必须自己垫，否则整页内容画进顶栏底下，
                    // 表现为「开关都被标题挡住了」。
                    .padding(top = padding.calculateTopPadding())
                    .padding(bottom = padding.calculateBottomPadding())
                    .padding(vertical = 4.dp),
            ) {
                if (configKey == null) {
                    GroupTitle("总开关")
                    SettingsCard {
                        SwitchPreference(
                            title = "启用此功能",
                            summary = if (!dualCompatible) {
                                "请先将移动信号显示逻辑设为默认，并取消隐藏 SIM 卡"
                            } else if (switches[feature.id] == true) {
                                "已启用"
                            } else {
                                "已关闭"
                            },
                            checked = switches[feature.id] == true,
                            enabled = dualCompatible || switches[feature.id] == true,
                            onCheckedChange = { on -> onSwitch(feature.id, on) },
                        )
                    }
                }

                val detailPanels = remember(feature) { featureDetailPanels(feature) }
                detailPanels.forEach { panel ->
                    GroupTitle(panel.title)
                    SettingsCard {
                        panel.options.forEachIndexed { index, option ->
                            if (index > 0) RowDivider()
                            val available = !MobileSignalSettings.isHideCardOption(option.id) ||
                                MobileSignalSettings.allowsHiddenCards(
                                    MobileSignalSettings.mode(strings[MobileSignalSettings.MODE]),
                                )
                            SwitchPreference(
                                title = option.title,
                                summary = if (available) option.summary else "当前显示逻辑下不生效",
                                enabled = available,
                                checked = available && switches[option.id] == true,
                                onCheckedChange = { on -> onSwitch(option.id, on) },
                            )
                        }
                        panel.config.forEachIndexed { index, row ->
                            if (index > 0 || panel.options.isNotEmpty()) RowDivider()
                            when (row) {
                                is HyperAppSelection -> AppSelectionRow(row, strings[row.key].orEmpty()) { onString(row.key, it) }
                                is HyperColor -> ColorSelectionRow(row, strings[row.key].orEmpty()) { onString(row.key, it) }
                                is HyperChoice -> {
                                    val current = strings[row.key].orEmpty()
                                        .ifBlank { row.default }
                                    ChoiceRow(
                                        title = row.title,
                                        summary = row.summary,
                                        currentLabel = row.labelOf(current),
                                        options = row.entries.map { it.label },
                                        selectedIndex = row.entries
                                            .indexOfFirst { it.variant == current }
                                            .coerceAtLeast(0),
                                        onSelect = { picked ->
                                            row.entries.getOrNull(picked)?.let {
                                                onString(row.key, it.variant)
                                            }
                                        },
                                    )
                                }

                                is HyperSlider -> {
                                    val stored = strings[row.key].orEmpty()
                                        .toIntOrNull() ?: row.default
                                    NumberRow(
                                        title = row.title,
                                        summary = row.summary,
                                        value = stored.toFloat(),
                                        valueRange = row.min.toFloat()..row.max.toFloat(),
                                        steps = ((row.max - row.min) / row.step - 1)
                                            .coerceAtLeast(0),
                                        format = { row.display(it.roundToInt()) },
                                        onFinished = { onString(row.key, it.roundToInt().toString()) },
                                    )
                                }

                                is HyperText -> {
                                    TextInputRow(
                                        title = row.title,
                                        summary = row.summary,
                                        value = strings[row.key].orEmpty(),
                                        placeholder = row.placeholder,
                                        onCommit = { onString(row.key, it) },
                                        validate = { draft ->
                                            if (row.key == MobileTypeTextSettings.TEXT && draft.isNotBlank() &&
                                                MobileTypeTextSettings.text(draft) == null) "最多 8 个字符，不能包含换行或方向控制符"
                                            else null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (configKey != null) {
                    GroupTitle("卡面图片")
                    val mappingText by rememberUpdatedState(strings[configKey].orEmpty())
                    val mappings = CardFaceMappings.read(mappingText)
                    walletFaces.items.forEach { item ->
                        key(item.address) {
                            SettingsCard {
                                CardFacePicker(
                                    stored = mappings[item.address].orEmpty(),
                                    walletFace = item.bitmap,
                                    revision = reloadTick,
                                    onPick = { uri -> onString(configKey, CardFaceMappings.update(mappingText, item.address, uri)) },
                                    onClear = { onString(configKey, CardFaceMappings.update(mappingText, item.address, null)) },
                                )
                            }
                        }
                    }
                    HintText(walletFaces.hint.ifEmpty { "正在读取本机卡面…" }, horizontalPadding = 28.dp)
                }

                feature.extra?.let { extra ->
                    when (extra) {
                        FeatureExtra.MONET_SCHEME -> MonetSchemeSection(
                            selected = strings[MONET_SCHEME_KEY].orEmpty(),
                            onSelect = { onString(MONET_SCHEME_KEY, it) },
                        )

                        FeatureExtra.PASSKEY_DEFAULT_APP -> PasskeyAppSection()

                        FeatureExtra.NOTIFY_ICON_LIBRARY -> NotifyIconLibrarySection(
                            switches = switches,
                            strings = strings,
                            onString = onString,
                        )
                    }
                }

                // 「怎么让它生效」不在这里重复：那是作用域页右上角那个按钮的事。
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}


// ------------------------------------------------------------------ 卡面配置区

/**
 * 卡面配置区：**左边是当前卡面，右边是替换入口**，两列等宽。
 *
 * ## 为什么并排而不是上下
 *
 * 「现在的」和「换成什么」是同一个决定的两面，并排才能一眼比出来；上下摞起来时，
 * 中间那段说明文字会把两者隔开，看起来像两个不相干的设置项。
 *
 * ## 左格显示什么
 *
 * 显示钱包自带的卡面（由注入侧捕获回来，见 [WalletCardFace]），右格显示独立保存的替换图。
 * 点击右格选图；已有替换图时长按右格，仅清除此卡的映射，不影响钱包原图或其他卡片。
 *
 * ## 为什么用系统相册选择器而不是自己写文件浏览
 *
 * `PickVisualMedia` 是系统提供的相册选择界面，**不需要任何存储权限**，
 * 也不需要本模块申请 `READ_MEDIA_IMAGES`：用户选中的那个 Uri 由系统临时授权给本应用，
 * 我们读它一次、把字节复制进自己的私有目录（见 [NfcCardImage.import]），之后就与相册无关了。
 *
 */
@Composable
private fun CardFacePicker(
    stored: String,
    walletFace: ImageBitmap?,
    revision: Int,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Explicit revisions also refresh previews when the backing image changes during a reload.
    var pickRevision by remember { mutableIntStateOf(0) }
    val effectiveRevision = pickRevision + revision

    val file = remember(stored, effectiveRevision) {
        if (stored.isBlank()) null else NfcCardImage.mappedFile(context, stored)
    }
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        file?.absolutePath,
        file?.lastModified(),
        effectiveRevision,
    ) {
        value = withContext(Dispatchers.IO) { file?.let { decodeScaled(it) } }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        coroutineScope.launch {
            val outcome = withContext(Dispatchers.IO) { NfcCardImage.importForCard(context, picked) }
            busy = false
            outcome.onSuccess { uri ->
                pickRevision++
                onPick(uri)
                message = "已保存替换卡面。"
            }.onFailure {
                message = "选图失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }
    val launchPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val current = walletFace
    val hasReplacement = stored.isNotBlank()
    val clearReplacement: (() -> Unit)? = if (hasReplacement && !busy) {
        {
            onClear()
            pickRevision++
            message = "已清除替换卡面。"
        }
    } else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SLOT_HEIGHT)
                    .clip(SLOT_SHAPE)
                    .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                if (current != null) {
                    Image(
                        bitmap = current,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    SecondaryText(
                        text = when {
                            stored.isBlank() -> "钱包自带"
                            else -> "图片不可读"
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            SecondaryText(
                text = when {
                    walletFace != null -> "钱包自带"
                    else -> "当前卡面"
                },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SLOT_HEIGHT)
                    .clip(SLOT_SHAPE)
                    .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                    .combinedClickable(
                        enabled = !busy,
                        role = Role.Button,
                        onClickLabel = "选择替换卡面",
                        onLongClickLabel = if (clearReplacement != null) "清除此卡的替换卡面" else null,
                        onLongClick = clearReplacement,
                        onClick = launchPicker,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (hasReplacement && bitmap != null) {
                    Image(bitmap = bitmap!!, contentDescription = "此卡自定义卡面",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = MiuixIcons.Photos,
                        contentDescription = null,
                        tint = if (busy) {
                            MiuixTheme.colorScheme.disabledPrimary
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            busy -> "处理中…"
                            stored.isBlank() -> "选择图片"
                            else -> "换一张"
                        },
                        color = if (busy) {
                            MiuixTheme.colorScheme.disabledPrimary
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            SecondaryText(
                text = if (hasReplacement) "点击替换 · 长按清除" else "点击替换",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }

    message?.let { HintText(it, color = MiuixTheme.colorScheme.primary) }
}

// ------------------------------------------------------------------ 附加选择器

/**
 * 莫奈取色风格。
 *
 * 存的是 **libmonet 的 Variant 名**（`TONAL_SPOT` 等），不是宿主那个 style 整数 ——
 * 整数各版本未必一致，名字才是稳定的口径。注入侧拿到名字之后在运行时反查本机宿主
 * 用哪个整数表示它（见 `hook/feature/WallpaperMonetFix`）。
 */
@Composable
private fun MonetSchemeSection(selected: String, onSelect: (String) -> Unit) {
    GroupTitle("取色风格")
    SettingsCard {
        val labels = remember { MONET_SCHEMES.map { it.label } }
        val index = remember(selected) {
            MONET_SCHEMES.indexOfFirst { it.variant == selected }.coerceAtLeast(0)
        }
        OverlayDropdownPreference(
            title = "取色风格",
            summary = MONET_SCHEMES.getOrNull(index)?.summary,
            items = labels,
            selectedIndex = index,
            onSelectedIndexChange = { picked ->
                onSelect(MONET_SCHEMES.getOrNull(picked)?.variant.orEmpty())
            },
        )
        HintText("重启系统界面后生效")
    }
}

/**
 * 默认通行密钥应用。
 *
 * 枚举的是**实现了 `CredentialProviderService` 的应用** —— 那才是系统认可的凭据提供方，
 * 而不是「所有装了密码管理器的应用」。写的是 `Settings.Secure.credential_service`，
 * 那个键属于受保护设置，普通应用没有写权限，所以这一步走 root。
 */
@Composable
private fun PasskeyAppSection() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val providers by produceState(initialValue = emptyList<PasskeyProvider.Provider>(), reload) {
        value = withContext(Dispatchers.IO) { PasskeyProvider.installed(context) }
    }
    // current() 走 root（`settings get secure`），所以它同样必须离开主线程。
    val current by produceState(initialValue = "", reload) {
        value = withContext(Dispatchers.IO) { PasskeyProvider.current() }
    }

    GroupTitle("默认通行密钥应用")
    SettingsCard {
        if (providers.isEmpty()) {
            HintText("没有找到任何凭据提供方。通行密钥需要设备已启用「谷歌基础服务」。")
        } else {
            val labels = providers.map { it.label }
            val index = providers.indexOfFirst { it.component == current }.coerceAtLeast(0)
            OverlayDropdownPreference(
                title = "默认应用",
                summary = if (current.isBlank()) {
                    "使用系统默认"
                } else {
                    "当前：${providers.firstOrNull { it.component == current }?.label ?: current}"
                },
                items = labels,
                selectedIndex = index,
                enabled = !applying,
                onSelectedIndexChange = { picked ->
                    val target = providers.getOrNull(picked) ?: return@OverlayDropdownPreference
                    applying = true
                    message = null
                    coroutineScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            PasskeyProvider.apply(target.component)
                        }
                        applying = false
                        reload++
                        message = if (ok) {
                            "已设为「${target.label}」。"
                        } else {
                            "写入失败：这一步需要 root（凭据提供方是受保护设置）。"
                        }
                    }
                },
            )
        }
        message?.let { HintText(it, color = MiuixTheme.colorScheme.primary) }
        HintText("修改默认应用需要 root 权限。")
    }
}

/** 按最大边 1024 解码，避免把一张几千万像素的原图整个读进内存。 */
private fun decodeScaled(file: File): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap: Bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    bitmap.asImageBitmap()
}.getOrNull()

// ------------------------------------------------------------------ 通知图标库

/**
 * 通知图标库的「同步与更新」区（`native_notify_icon` 的附加配置）。
 *
 * 结构是三段：状态（本进程缓存了多少图标）、同步来源（GitHub 在国内的三条路）、
 * 自动同步时刻（配合「自动更新图标库」子开关）。手动同步之后会向 SystemUI
 * 发一条同步广播 —— SystemUI 侧有自己的私有缓存（两个 uid 互不可见），它收到
 * 广播后自己去拉一份新的。
 */
@Composable
private fun NotifyIconLibrarySection(
    switches: Map<String, Boolean>,
    strings: Map<String, String>,
    onString: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val autoEnabled = switches["native_notify_icon.icon_fix_auto"] == true
    val source = NotifyIconSyncSource.fromVariant(strings[NOTIFY_ICON_SOURCE_KEY])
    val autoTime = strings[NOTIFY_ICON_AUTO_TIME_KEY].orEmpty().ifBlank { "03:00" }
    val sourceLabels = remember { NotifyIconSyncSource.entries.map { it.label } }

    val status by produceState(initialValue = "正在读取…", busy) {
        value = withContext(Dispatchers.IO) {
            if (NotifyIconLibrary.hasSnapshot()) {
                "本进程已缓存 ${NotifyIconLibrary.iconCount()} 个适配图标。"
            } else {
                "本进程还没有同步过图标库（SystemUI 有它自己的一份）。"
            }
        }
    }

    GroupTitle("同步与更新")
    SettingsCard {
        HintText(status)
        ChoiceRow(
            title = "同步来源",
            summary = "图标库托管在 GitHub，国内直连不稳定",
            currentLabel = source.label,
            options = sourceLabels,
            selectedIndex = NotifyIconSyncSource.entries.indexOf(source),
            onSelect = { picked ->
                NotifyIconSyncSource.entries.getOrNull(picked)?.let { onString(NOTIFY_ICON_SOURCE_KEY, it.variant) }
            },
        )
        if (autoEnabled) {
            RowDivider()
            ChoiceRow(
                title = "自动同步时刻",
                summary = "SystemUI 每天在这个时刻自己同步一次",
                currentLabel = autoTime,
                options = NOTIFY_ICON_AUTO_TIMES,
                selectedIndex = NOTIFY_ICON_AUTO_TIMES.indexOf(autoTime).coerceAtLeast(0),
                onSelect = { picked ->
                    NOTIFY_ICON_AUTO_TIMES.getOrNull(picked)?.let { onString(NOTIFY_ICON_AUTO_TIME_KEY, it) }
                },
            )
        }
        RowDivider()
        CardActionRow(
            label = if (busy) "同步中…" else "立即同步图标库",
            enabled = !busy,
            onClick = {
                busy = true
                message = null
                NotifyIconLibrary.refreshAsync(context, source) { outcome ->
                    busy = false
                    message = outcome.message
                    if (outcome.success) {
                        // 让 SystemUI 侧也去拉一份：它是按 uid 隔离的独立缓存，
                        // 不发广播的话，新图标要等 SystemUI 下次重启/到点同步才可用。
                        runCatching {
                            context.sendBroadcast(
                                Intent(NotifyIconLibrary.SYNC_ACTION).setPackage(SYSTEMUI_PACKAGE),
                            )
                        }
                    }
                }
            },
        )
    }
    HintText(
        message ?: "更换来源后请重新同步。",
        color = if (message != null) MiuixTheme.colorScheme.primary else null,
    )
}

private const val SYSTEMUI_PACKAGE = "com.android.systemui"
