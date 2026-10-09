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

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.YGHFv.HyperExtend.config.HyperSettings
import io.github.YGHFv.HyperExtend.core.FEATURES
import io.github.YGHFv.HyperExtend.core.HyperScope
import io.github.YGHFv.HyperExtend.core.NfcCardImage
import io.github.YGHFv.HyperExtend.core.ScopeFeatureGroup
import io.github.YGHFv.HyperExtend.core.enabledCountInScope
import io.github.YGHFv.HyperExtend.core.entryScopes
import io.github.YGHFv.HyperExtend.core.entryScope
import io.github.YGHFv.HyperExtend.core.entryGroup
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.featuresOfScope
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.core.scopeById
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块主界面。
 *
 * ## 它承担的三件事
 *
 * 1. **让模块脱离 stopped 状态**。模块常年不开，一旦被系统置为 stopped，
 *    它自己注册的广播/服务就收不到任何东西。用户打开一次界面是唯一稳定可靠的解法。
 * 2. **开关面板**。功能、作用域、搜索全都从 `FeatureCatalog` / `ScopeCatalog` 派生 ——
 *    界面里没有任何一个写死的开关 id。
 * 3. **让改动立刻生效**。作用域页面右上角能直接让宿主重新加载模块（见 `core/HotReloader`），
 *    走不通时才退回结束进程（见 `core/ProcessRestarter`）。
 *
 * ## 深浅色与底栏样式
 *
 * 主题三档（跟随系统 / 浅色 / 深色）与底栏样式（毛玻璃 / 悬浮 / 液态玻璃）都是**模块界面自身**
 * 的外观，存在 `ui/UiPrefs`，与功能开关那份设置完全分开（理由见 `UiPrefs` 的注释）。
 * 主题必须在这里就决定 —— `MiuixTheme` 的配色表要在整棵树之上给定，
 * 所以它在 `setContent` 这一层算，再往下传。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边：miuix 的顶栏/底栏/滚动行为就是按「内容铺到屏幕边缘、各组自己垫 inset」设计的，
        // 不开的话顶栏下面会多出一条与系统栏等高的空带。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 系统默认给导航栏叠的一层对比度 scrim，就是「底栏下面那条更亮白带」的真身。
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            // 主题三档在整棵树之上算：改了主题要连带重算配色表，所以它必须待在最外层。
            var themeMode by remember { mutableIntStateOf(UiPrefs.themeMode(this@SettingsActivity)) }
            val dark = when (themeMode) {
                UiPrefs.THEME_LIGHT -> false
                UiPrefs.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            SystemBars(dark)
            // 配色表用 remember 固定住：`darkColorScheme()` / `lightColorScheme()` 每次调用都会
            // 造一张新表，而 `MiuixTheme` 会把它当成「主题变了」从而重组整棵子树（本模块界面
            // 就是整棵树）。只有深浅色真的切换时才重新构造。
            val colors = remember(dark) {
                if (dark) darkColorScheme() else lightColorScheme()
            }
            MiuixTheme(colors = colors) {
                HyperApp(themeMode = themeMode, onThemeMode = { themeMode = it })
            }
        }
    }

    /** 系统栏图标的明暗跟随主题。不跟着改的话浅色主题下状态栏图标是白的，等于看不见。 */
    @Composable
    private fun SystemBars(dark: Boolean) {
        LaunchedEffect(dark) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@LaunchedEffect
            runCatching {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
            }
        }
    }

    // ---- 隐藏后台卡片（最近任务）----
    //
    // 「在最近任务隐藏本应用」是运行时行为：离开界面时把任务设为 excludeFromRecents，
    // 回来时恢复。偏好直接读 [UiPrefs] —— 它属于模块自身外观，不走功能开关那套投影。

    override fun onResume() {
        super.onResume()
        if (UiPrefs.hideRecentTask(this)) setTaskExcludedFromRecents(false)
    }

    override fun onUserLeaveHint() {
        if (UiPrefs.hideRecentTask(this)) setTaskExcludedFromRecents(true)
    }

    override fun onStop() {
        if (!isChangingConfigurations && UiPrefs.hideRecentTask(this)) setTaskExcludedFromRecents(true)
        super.onStop()
    }

    private fun setTaskExcludedFromRecents(excluded: Boolean) {
        runCatching {
            getSystemService(android.app.ActivityManager::class.java)?.appTasks
                ?.firstOrNull { it.taskInfo?.taskId == taskId }
                ?.setExcludeFromRecents(excluded)
        }
    }
}

/**
 * 底栏的三个页面。
 *
 * 「首页」放功能入口，「设置」放模块自身的外观与数据入口，「关于」放来源、日志与应急手段 ——
 * 三者的**访问频率**差一个量级，平铺在底栏上是为了让每天都用的那个（首页）永远只有一次点击。
 */
private enum class MainTab(val title: String) {
    HOME("首页"),
    SETTINGS("设置"),
    ABOUT("关于"),
}

/**
 * 界面的根。**开关状态的唯一持有者**就在这里。
 *
 * ## 为什么状态要提到这一层
 *
 * 首页显示每个作用域下的启用数、作用域页要改开关、功能详情页要读最新值、搜索命中后跳过去。
 * 四处各自 `remember` 一份必然不一致（改完返回上一层还是旧值），所以只有一份 [switches]，
 * 改完立刻回写并替换快照。
 *
 * ## 页面位置必须活过进程被杀
 *
 * 用户在作用域页改完开关、切出去重启宿主、再切回来 —— 这中间模块界面**很可能已被系统回收**
 * （它不在前台、又不常驻）。如果当前位置只存在 `remember` 里，回来的必然是第一页，
 * 用户得从头点进去。所以这里的位置一律用 [rememberSaveable]（它走 saved instance state，
 * 能活过进程重建），且**只存 id 不存对象**：`HyperScope` / `HyperFeature` 不是可序列化类型，
 * 而 id 是稳定的。
 *
 * ## 写入路径
 *
 * 所有功能开关的写入都走 [HyperSettings.set] / [setString] / [resetToDefaults]：它们负责
 * 「落本地 + 尽力投影给框架」。界面从不直接写 SharedPreferences —— 那样会绕过投影，
 * 表现为「改了开关但注入侧读到的还是旧的」。界面外观（[UiPrefs]）是例外：它不投影，只给本进程看。
 */
@Composable
private fun HyperApp(themeMode: Int, onThemeMode: (Int) -> Unit) {
    val context = LocalContext.current

    // 快照而不是「按 id 现读」：现读会在每次重组时命中十几遍 prefs，且拿不到「写入了什么」的原子视图。
    var switches by remember { mutableStateOf(HyperSettings.read(context)) }
    var strings by remember { mutableStateOf(HyperSettings.readStrings(context)) }
    var ui by remember { mutableStateOf(UiPrefs.read(context)) }

    // 位置状态：空串代表「不在这一层」。用空串而不是 null，是为了让保存逻辑不必处理 null 值。
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var openScopeId by rememberSaveable { mutableStateOf("") }
    var openGroupId by rememberSaveable { mutableStateOf("") }
    var openFeatureId by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    // 设置页的二级页面（AppSettingsPage 枚举名）。与作用域页同级的「整页替换」层。
    var openAppPage by rememberSaveable { mutableStateOf("") }

    val tab = MainTab.entries.getOrElse(tabIndex) { MainTab.HOME }

    // 无论写入是否成功都更新界面：失败时 HyperSettings 已经记了日志，
    // 而这里若保持旧值，用户会以为自己没点中，反复点同一下。
    val setSwitch: (String, Boolean) -> Unit = { id, on ->
        HyperSettings.set(context, id, on)
        switches = switches + (id to on)
    }
    val setString: (String, String) -> Unit = { key, value ->
        HyperSettings.setString(context, key, value)
        strings = strings + (key to value)
    }
    val reloadSettings: () -> Unit = {
        switches = HyperSettings.read(context)
        strings = HyperSettings.readStrings(context)
    }
    val applyUi: (UiPrefs.UiState) -> Unit = { next ->
        UiPrefs.setThemeMode(context, next.themeMode)
        UiPrefs.setBlurBars(context, next.blurBars)
        UiPrefs.setFloatingBar(context, next.floatingBar)
        UiPrefs.setLiquidGlass(context, next.liquidGlass)
        // 个性化三项（隐藏后台卡片 / 隐藏桌面图标 / 图标配色）也走 onUi 这一条路，
        // 必须在这里一并落库 —— 否则重启后全部回默认，onResume 读到的还是 false。
        UiPrefs.writePersonalization(context, next)
        ui = next
        onThemeMode(next.themeMode)
    }

    // ---- 二三层：整页替换 ----
    // 顺序即层级：设置页的二级页面在最前（它没有更深的层），其次是功能详情、作用域页、搜索。

    AppSettingsPage.fromId(openAppPage)?.let { page ->
        AppSettingsPageHost(
            page = page,
            ui = ui,
            onUi = applyUi,
            onReloadSettings = reloadSettings,
            onReset = {
                // 先清掉卡面副本再清设置：只清设置会留下一张谁都不再引用的图，
                // 下次选图时它才被顺手删掉 —— 那等于把「已恢复默认」变成了一句不准确的话。
                NfcCardImage.clear(context)
                HyperSettings.resetToDefaults(context)
                reloadSettings()
            },
            onBack = { openAppPage = "" },
        )
        return
    }

    val feature = featureById(openFeatureId)
    if (feature != null && !feature.hasDetailPage) {
        // Saved navigation from an older build may still point at a now-inline switch.
        LaunchedEffect(feature.id) {
            openScopeId = feature.entryScope?.id.orEmpty()
            openGroupId = feature.entryGroup?.name.orEmpty()
            openFeatureId = ""
        }
        return
    }
    if (feature != null) {
        FeatureDetailPage(
            feature = feature,
            switches = switches,
            strings = strings,
            onSwitch = setSwitch,
            onString = setString,
            onBack = { openFeatureId = "" },
        )
        return
    }

    val scope = scopeById(openScopeId)
    if (scope != null) {
        val group = ScopeFeatureGroup.entries.firstOrNull {
            it.name == openGroupId && it.scopeId == scope.id
        }
        // Separate page state: entering a submenu must not inherit its parent's scroll offset.
        key(scope.id, group) {
            ScopeDetailPage(
                scope = scope,
                switches = switches,
                strings = strings,
                onSwitch = setSwitch,
                group = group,
                onOpenFeature = { openFeatureId = it.id },
                onOpenGroup = { openGroupId = it.name },
                onBack = {
                    if (group != null) openGroupId = ""
                    else {
                        openGroupId = ""
                        openScopeId = ""
                    }
                },
            )
        }
        return
    }

    if (searching) {
        SearchPage(
            switches = switches,
            strings = strings,
            onSwitch = setSwitch,
            onOpenFeature = { hit ->
                searching = false
                openFeatureId = hit.id
            },
            onBack = { searching = false },
        )
        return
    }

    MainTabs(
        tab = tab,
        onTab = { tabIndex = it.ordinal },
        switches = switches,
        strings = strings,
        ui = ui,
        onOpenScope = {
            openGroupId = ""
            openScopeId = it.id
        },
        onOpenAppPage = { openAppPage = it.id },
        onSearch = { searching = true },
    )
}

/**
 * 底栏骨架 —— 整体结构照抄参考项目（阅微补全计划）的 ModuleApp：
 *
 * - 「模糊」= 顶栏 / 底栏用 miuix-blur 的 `textureBlur`（surface 色 87% 叠色，半径 25f），
 *   顶栏底栏自身透明，模糊的是 [backdrop] 图层里垫了一层 surface 的整页内容；
 * - 「悬浮底栏」= [FloatingBottomBar]（Apple 风格胶囊，开启液态玻璃时带折射镜片效果）；
 * - 两个开关都不开时走原样 `NavigationBar`，像素与改动前一致。
 *
 * ## 液态玻璃的门禁
 *
 * `isRuntimeShaderSupported()`（Android 13+ 的 RuntimeShader）不满足时「模糊」「液态玻璃」
 * 都自动退化为不透明底色 —— 与参考项目一致，低版本只是没有效果，不会崩。
 */
/** 顶栏 / 底栏毛玻璃参数。与参考项目 ModuleMainActivity 的常量逐字一致。 */
private const val BAR_BLUR_RADIUS = 25f
private const val BAR_TINT_ALPHA = 0.87f

@Composable
private fun MainTabs(
    tab: MainTab,
    onTab: (MainTab) -> Unit,
    switches: Map<String, Boolean>,
    strings: Map<String, String>,
    ui: UiPrefs.UiState,
    onOpenScope: (HyperScope) -> Unit,
    onOpenAppPage: (AppSettingsPage) -> Unit,
    onSearch: () -> Unit,
) {
    val homeScroll = MiuixScrollBehavior()
    val settingsScroll = MiuixScrollBehavior()
    val aboutScroll = MiuixScrollBehavior()
    val scrollBehavior = when (tab) {
        MainTab.HOME -> homeScroll
        MainTab.SETTINGS -> settingsScroll
        MainTab.ABOUT -> aboutScroll
    }

    // 毛玻璃 / 悬浮 / 液态玻璃的判定与配色，照抄参考项目 ModuleApp。
    val blurSupported = remember { isRuntimeShaderSupported() }
    val blurred = ui.blurBars && blurSupported
    val floating = ui.floatingBar
    val liquid = floating && ui.liquidGlass && blurSupported
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val barBlurColors = BlurColors(
        blendColors = listOf(BlendColorEntry(surface.copy(alpha = BAR_TINT_ALPHA))),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = tab.title,
                largeTitle = tab.title,
                scrollBehavior = scrollBehavior,
                color = if (blurred) Color.Transparent else MiuixTheme.colorScheme.surface,
                modifier = if (blurred) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = BAR_BLUR_RADIUS,
                        colors = barBlurColors,
                    )
                } else {
                    Modifier
                },
                actions = {
                    if (tab == MainTab.HOME) {
                        IconButton(onClick = onSearch, backgroundColor = Color.Transparent) {
                            Icon(imageVector = MiuixIcons.Search, contentDescription = "搜索功能")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (floating) {
                val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                        .padding(bottom = if (navInset > 0.dp) 8.dp + navInset else 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FloatingBottomBar(
                        // 拦住落在悬浮底栏本体上的点击/手势，避免穿透到底层内容。
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
                        selectedIndex = tab.ordinal,
                        onSelected = { index -> MainTab.entries.getOrNull(index)?.let(onTab) },
                        backdrop = backdrop,
                        tabsCount = MainTab.entries.size,
                        isBlurEnabled = liquid,
                    ) { activateTab ->
                        MainTab.entries.forEachIndexed { index, item ->
                            FloatingBottomBarItem(
                                selected = tab == item,
                                onClick = { activateTab(index) },
                                // 每个 tab 最小 76dp：配合底栏的 IntrinsicSize.Min，
                                // 让悬浮胶囊按内容收成窄胶囊居中，而非拉满整行。
                                modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                            ) {
                                Icon(
                                    imageVector = when (item) {
                                        MainTab.HOME -> MiuixIcons.Home
                                        MainTab.SETTINGS -> MiuixIcons.Settings
                                        MainTab.ABOUT -> MiuixIcons.Info
                                    },
                                    contentDescription = item.title,
                                    tint = LocalContentColor.current,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = item.title,
                                    color = LocalContentColor.current,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible,
                                )
                            }
                        }
                    }
                }
            } else {
                NavigationBar(
                    color = if (blurred) Color.Transparent else MiuixTheme.colorScheme.surface,
                    modifier = if (blurred) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            blurRadius = BAR_BLUR_RADIUS,
                            colors = barBlurColors,
                        )
                    } else {
                        Modifier
                    },
                ) {
                    MainTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { onTab(item) },
                            icon = when (item) {
                                MainTab.HOME -> MiuixIcons.Home
                                MainTab.SETTINGS -> MiuixIcons.Settings
                                MainTab.ABOUT -> MiuixIcons.Info
                            },
                            label = item.title,
                        )
                    }
                }
            }
        },
    ) { padding ->
        // 内容铺进图层，顶栏 / 底栏的毛玻璃才有东西可模糊（参考项目同款做法：
        // 图层里先垫一层 surface，保证透出的不是纯白）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurred || floating) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            MainTabsContent(tab, scrollBehavior, padding, switches, strings, onOpenScope, onOpenAppPage)
        }
    }
}

/** 底栏三个页面的内容。抽出来只是为了让 [MainTabs] 里那两个（进图层 / 不进图层）分支共用同一份。 */
@Composable
private fun MainTabsContent(
    tab: MainTab,
    scrollBehavior: ScrollBehavior,
    padding: PaddingValues,
    switches: Map<String, Boolean>,
    strings: Map<String, String>,
    onOpenScope: (HyperScope) -> Unit,
    onOpenAppPage: (AppSettingsPage) -> Unit,
) {
    when (tab) {
        MainTab.HOME -> HomeTab(scrollBehavior, padding, switches, strings, onOpenScope)
        MainTab.SETTINGS -> SettingsTab(scrollBehavior, padding, onOpenAppPage)
        MainTab.ABOUT -> AboutTab(scrollBehavior, padding) { onOpenAppPage(AppSettingsPage.LOGS) }
    }
}

/**
 * 首页：按作用域收纳的入口行；模块状态仅在关于页展示。
 *
 * 入口行的顺序、标题、图标、启用数全部从 [SCOPES] / [FEATURES] 派生 ——
 * 这个文件里没有任何一个功能名、应用名或开关 id 是写死的。
 * 加功能 = 在目录里加一条；加宿主 = 在 [SCOPES] 里加一条。
 */
@Composable
private fun HomeTab(
    scrollBehavior: ScrollBehavior,
    padding: PaddingValues,
    switches: Map<String, Boolean>,
    strings: Map<String, String>,
    onOpenScope: (HyperScope) -> Unit,
) {
    val scrollState = rememberScrollState()
    val entries = remember { entryScopes() }
    val installed = rememberInstalledScopes(entries)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(scrollState)
            .padding(top = padding.calculateTopPadding())
            .padding(bottom = padding.calculateBottomPadding())
            .padding(vertical = 4.dp),
    ) {
        GroupTitle("作用域")
        SettingsCard {
            entries.forEachIndexed { index, scope ->
                if (index > 0) RowDivider()
                ScopeEntryRow(
                    scope = scope,
                    enabledCount = enabledCountInScope(scope.id, switches, strings),
                    totalCount = featuresOfScope(scope.id).size,
                    installed = installed[scope.id] == true,
                    onClick = { onOpenScope(scope) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 设置页：**入口行**，没有开关。
 *
 * 与作用域页同一条规矩：列表页只负责「进入」，拨开关/做动作的事全部住进二级页面
 * （见 [AppSettingsPageHost]）。这样每一级只有一种交互，也更接近参考项目
 * （阅微补全计划）的设置页结构：界面 / 个性化 / 备份恢复各自一页。
 */
@Composable
private fun SettingsTab(
    scrollBehavior: ScrollBehavior,
    padding: PaddingValues,
    onOpenAppPage: (AppSettingsPage) -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(scrollState)
            .padding(top = padding.calculateTopPadding())
            .padding(bottom = padding.calculateBottomPadding())
            .padding(vertical = 4.dp),
    ) {
        GroupTitle("界面外观")
        SettingsCard {
            FeatureRow(
                title = "界面",
                summary = "主题与底栏",
                onClick = { onOpenAppPage(AppSettingsPage.INTERFACE) },
            )
            RowDivider()
            FeatureRow(
                title = "个性化",
                summary = "图标与最近任务",
                onClick = { onOpenAppPage(AppSettingsPage.PERSONALIZATION) },
            )
        }

        GroupTitle("数据")
        SettingsCard {
            FeatureRow(
                title = "备份恢复",
                summary = "导入、导出功能设置",
                onClick = { onOpenAppPage(AppSettingsPage.BACKUP) },
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
