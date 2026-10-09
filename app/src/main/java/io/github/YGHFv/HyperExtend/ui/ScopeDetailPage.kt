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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.YGHFv.HyperExtend.core.HyperFeature
import io.github.YGHFv.HyperExtend.core.HyperScope
import io.github.YGHFv.HyperExtend.core.ProcessRestarter
import io.github.YGHFv.HyperExtend.core.RestartKind
import io.github.YGHFv.HyperExtend.core.RestartResult
import io.github.YGHFv.HyperExtend.core.ScopeFeatureGroup
import io.github.YGHFv.HyperExtend.core.featureGroupsOfScope
import io.github.YGHFv.HyperExtend.core.featuresOfScopePage
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.core.isFeatureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh

/**
 * 「让改动生效」的状态机。
 *
 * 用独立类型而不是几个 `Boolean`：这些状态**互斥**（不能在「执行中」同时显示上一次的失败），
 * 用布尔量表达就得靠 if 顺序去保证互斥，条件一多必然漏掉一种组合。
 *
 * [ConfirmReboot] 只对设备级重启出现 —— 它是全模块唯一会打断用户所有操作的动作，
 * 值得一个独立状态，而不是靠「再点一次」这种含糊提示。它带上 [ConfirmReboot.reason]：
 * 走到这一步的原因是「热重载没成功」，而那句话说清楚之前，用户不会理解为什么突然要重启整机。
 */
private sealed interface RestartUi {
    data object Idle : RestartUi
    data object Running : RestartUi
    data class Done(val message: String) : RestartUi
    data class Failed(val message: String) : RestartUi
    data class ConfirmReboot(val reason: String) : RestartUi
}

/**
 * 一个作用域的页面：它归属的功能 + 右上角「让改动生效」。
 *
 * ## 右上角那一个按钮做什么
 *
 * 顺序是**先热重载，走不通才结束进程**（见 [applyChanges]）：
 *
 * - 热重载（libxposed API 102）在宿主进程内部把模块代码换掉并重新挂载，进程自始至终不死，
 *   状态栏不会消失 —— 这是首选，也是「重启系统界面时界面闪一下」那个问题的正解；
 * - 结束进程是任何框架版本都能用的兜底，但它对 SystemUI 有可见副作用（状态栏窗口消失，
 *   边到边的界面会整块顶上去）。所以它只在热重载不可用/失败时发生，并且**把原因一并显示出来**。
 *
 * 按钮只有一个，因为它作用的对象也只有一个（这个进程）。页面里不再放第二个，
 * 也不在平时占一块说明区 —— 结果只在真的发生了一件事之后才出现（见 [RestartUi]）。
 *
 * ## 功能只列归属于这里的
 *
 * `featuresOfScope` 只认主宿主：通行密钥虽然也在设置、安全中心、扫描器里生效，
 * 但它只出现在系统框架下面，不会在四个入口里各显示一遍。
 */
@Composable
internal fun ScopeDetailPage(
    scope: HyperScope,
    switches: Map<String, Boolean>,
    strings: Map<String, String>,
    onSwitch: (String, Boolean) -> Unit,
    onOpenFeature: (HyperFeature) -> Unit,
    onOpenGroup: (ScopeFeatureGroup) -> Unit,
    onBack: () -> Unit,
    group: ScopeFeatureGroup? = null,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val features = remember(scope.id, group) { featuresOfScopePage(scope.id, group) }
    val groups = remember(scope.id, group) {
        if (group == null) featureGroupsOfScope(scope.id) else emptyList()
    }
    val (detailFeatures, switchFeatures) = remember(features) { features.partition { it.hasDetailPage } }
    val hasEntries = groups.isNotEmpty() || detailFeatures.isNotEmpty()
    val mixed = hasEntries && switchFeatures.isNotEmpty()
    // 走共用助手而不是在这里直接查 PackageManager：它是跨进程调用，不该发生在组合阶段
    // （理由见 HyperUiKit.rememberInstalledScopes）。只有一个宿主也照样用它 ——
    // 两套写法并存的结果是「哪天有人给这一页加了第二个宿主」时又得改一次结构。
    val installed = rememberInstalledScopes(remember(scope.id) { listOf(scope) })[scope.id] == true

    // 重启结果不需要活过进程重建：它描述的是「刚刚发生的一件事」，几分钟后再看到反而是误导。
    var restart by remember(scope.id) { mutableStateOf<RestartUi>(RestartUi.Idle) }

    val performApply: () -> Unit = {
        restart = RestartUi.Running
        coroutineScope.launch { restart = applyChanges(context, scope) }
    }

    // 真正重启设备。与 [performApply] 分开是必需的：applyChanges 对系统框架会**返回**
    // 「要不要重启」这个问题，把确认按钮接到它自己身上就成了一个永远在问的循环。
    val performReboot: () -> Unit = {
        restart = RestartUi.Running
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { ProcessRestarter.reboot() }
            restart = when (result) {
                is RestartResult.Done -> RestartUi.Done(result.message)
                is RestartResult.Failed -> RestartUi.Failed(result.message)
            }
        }
    }

    // 右上角图标的触发逻辑：执行中、或已经在等确认时忽略重复点击 —— 其余一律重新走一遍流程。
    val triggerApply: () -> Unit = {
        when {
            restart == RestartUi.Running -> Unit
            restart is RestartUi.ConfirmReboot -> Unit
            else -> performApply()
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = group?.title ?: scope.title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack, backgroundColor = Color.Transparent) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = triggerApply, backgroundColor = Color.Transparent) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "让 ${scope.title} 的改动生效",
                        )
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
                if (group != null) {
                    InfoRow(label = "作用域", value = scope.title)
                } else {
                    InfoRow(label = "进程", value = scope.process)
                }
                if (!installed) {
                    CardDivider()
                    HintText("未安装此应用，相关功能暂不可用。")
                }
            }

            // 结果只在真的发生了一件事之后才占位：平时这一页不多一块空说明区。
            RestartStatusCard(
                state = restart,
                onConfirmReboot = performReboot,
                onCancel = { restart = RestartUi.Idle },
            )

            if (hasEntries) {
                GroupTitle(if (mixed) "功能设置" else "功能")
                SettingsCard {
                    groups.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        val children = remember(scope.id, item) { featuresOfScopePage(scope.id, item) }
                        val enabled = children.count { isFeatureActive(it, switches, strings) }
                        FeatureRow(
                            title = item.title,
                            summary = item.summary,
                            statusText = "已启用 $enabled / ${children.size}",
                            onClick = { onOpenGroup(item) },
                        )
                    }
                    detailFeatures.forEachIndexed { index, item ->
                        if (index > 0 || groups.isNotEmpty()) RowDivider()
                        FeaturePreference(
                            feature = item,
                            switches = switches,
                            strings = strings,
                            onSwitch = onSwitch,
                            onOpenFeature = onOpenFeature,
                        )
                    }
                }
            }
            if (switchFeatures.isNotEmpty()) {
                GroupTitle(if (mixed) "快捷开关" else "功能")
                SettingsCard {
                    switchFeatures.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        FeaturePreference(
                            feature = item,
                            switches = switches,
                            strings = strings,
                            onSwitch = onSwitch,
                            onOpenFeature = onOpenFeature,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 让这个作用域的改动生效。
 *
 * 直接结束宿主进程（[ProcessRestarter]），这是唯一**可观测**的生效方式：
 * 进程死了、系统把它拉起来、新设置被读进内存 —— 三件事用户都看得到。
 * 热重载（框架报成功但宿主里是否真的重新挂载，界面拿不到证据）不再作为默认路径，
 * 它在这个设备上的表现就是「报了成功但什么都没变」。
 *
 * 系统框架（[RestartKind.REBOOT]）不退到结束进程：那个进程就是整机本身，
 * 结束它等于关机。所以它进入 [RestartUi.ConfirmReboot]，由用户明确点第二次。
 */
private suspend fun applyChanges(context: Context, scope: HyperScope): RestartUi {
    if (scope.restartKind == RestartKind.REBOOT) {
        return RestartUi.ConfirmReboot("系统服务无法单独结束进程，需要重启设备才能让改动生效。")
    }

    val fallback = withContext(Dispatchers.IO) { ProcessRestarter.restart(scope) }
    return when (fallback) {
        is RestartResult.Done -> RestartUi.Done(fallback.message)
        is RestartResult.Failed -> RestartUi.Failed(fallback.message)
    }
}

/**
 * 结果卡。`Idle` 时完全不出现 —— 页面默认状态下不该有自己的内容。
 *
 * 「确认重启设备」和「取消」放在这里而不是做成弹窗：它是从右上角那个图标触发的，
 * 用户视线本来就停在顶部，再弹一层窗口反而要多一次「这个弹窗是哪来的」的判断。
 */
@Composable
private fun RestartStatusCard(
    state: RestartUi,
    onConfirmReboot: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state == RestartUi.Idle) return

    SettingsCard {
        when (state) {
            RestartUi.Idle -> Unit

            RestartUi.Running -> HintText("正在执行…如果设备弹出 root 授权请求，需要你先允许。")

            is RestartUi.Done -> HintText(state.message)

            is RestartUi.Failed -> HintText(state.message)

            is RestartUi.ConfirmReboot -> {
                if (state.reason.isNotBlank()) {
                    HintText(state.reason)
                }
                HintText(
                    "系统框架只能随设备重启生效。重启会立刻中断当前所有操作，确认要继续吗？",
                )
                CardActionRow(label = "确认重启设备", danger = true, onClick = onConfirmReboot)
                CardDivider()
                CardActionRow(label = "取消", onClick = onCancel)
            }
        }
    }
}
