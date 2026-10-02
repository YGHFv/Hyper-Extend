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

package io.github.YGHFv.HyperExtend.core

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 框架服务的**唯一持有者**。
 *
 * ## 它持有的是什么
 *
 * 模块 App 进程里唯一的框架句柄：由 `XposedServiceHelper` 在框架把 binder 递过来时回调交出
 * （见 `HyperExtendApplication`）。拿到它之后有三件事能做，三件事彼此无关，但都需要这同一个对象：
 *
 * - `getRemotePreferences` —— 把界面上的开关推给被注入的进程（`config/HyperSettings`）；
 * - `getRunningTargets` / `hotReloadModule` —— 让改动在**不结束宿主进程**的前提下生效
 *   （`core/HotReloader`），这是「重启系统界面时状态栏闪一下」的正解；
 * - `apiVersion` —— 判断上面那条路本机框架支不支持（hot reload 是 API 102 才有的）。
 *
 * 所以句柄放在这里、由所有需要它的人来取，而不是各存一份：一份句柄散成两三处，
 * 「服务断开」时漏掉任何一处的清空，都会留下一个指向已死 binder 的引用。
 *
 * ## 为什么连接状态必须是可观察的流
 *
 * 早先的版本只提供 `isConnected()` 这个「读一下就完」的普通函数。界面在**组合期**读它一次，
 * 看上去没问题，实际上有两个坑：
 *
 * 1. **读不到变化**。框架 binder 是异步递过来的，界面若在它到达之前组合，就会永远停在
 *    「未连接」（红色），直到下一次重组才纠正 —— 用户看到的就是「一进来红、过一下绿」。
 * 2. **绑定会在进程生命周期里短暂断开再回来**。`onServiceDied` → `onServiceBind` 之间有一个
 *    真空期，此时读到的必然是 false；界面正好在这一刻重组，就会红一下。
 *
 * 所以这里给出一个 [connected] 状态流供界面收集；并且**断线不立刻降级**，
 * 而是等一小段宽限期，若期间重新绑定就当作没断过（见 [attach]）。两次机制合起来，
 * 首页那行状态才不会再「未连接 / 已连接」来回跳。
 *
 * ## 为什么可以直接写类型
 *
 * `io.github.libxposed:service` 是 `implementation` 依赖，它的类一定会打进模块 APK，
 * 所以模块 App 进程里引用它是安全的（与 `io.github.libxposed.api` 相反 ——
 * 那是 `compileOnly`，只在被注入的进程里由框架提供）。
 *
 * ⚠️ 本类**只在模块 App 进程里可用**：被注入的宿主进程里没有 `service` 这个包。
 * 挂 hook 的那半边（`hook/`）任何一处都不要引用它。
 */
object FrameworkBridge {

    /**
     * 断线后延迟多久才把状态降级成「未连接」。
     *
     * 取值要大到覆盖「断开→重绑」的真空期（通常几十毫秒到一两秒），
     * 又要小到让真正断线时用户不至于盯着一个假的「已连接」太久。两秒是个折中。
     */
    private const val DOWNGRADE_GRACE_MS = 2_000L

    @Volatile
    private var service: XposedService? = null

    /** 降级定时器所在的协程作用域。默认调度器即可 —— 它只做一个 `delay`。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connected = MutableStateFlow(false)

    /** 供界面观察的连接状态。只在「绑定 / 真正断开」时变化，不会在组合期抖动。 */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var everConnected = false

    private var downgradeJob: Job? = null

    /**
     * 绑定 / 断开框架服务。传 null 表示断开。
     *
     * 由 `HyperExtendApplication` 在两个回调里调用，别处不要调 —— 句柄的来源只有那一处。
     */
    @Synchronized
    fun attach(xposedService: XposedService?) {
        service = xposedService
        downgradeJob?.cancel()
        downgradeJob = null
        if (xposedService != null) {
            everConnected = true
            _connected.value = true
            return
        }
        // 断线不立刻降级（理由见类注释）：宽限期内若重新绑定，[attach] 会把定时器取消掉，
        // 界面于是从头到尾都显示「已连接」，不会红一下。
        downgradeJob = scope.launch {
            delay(DOWNGRADE_GRACE_MS)
            if (service == null) _connected.value = false
        }
    }

    /** 当前是否持有框架句柄。**只用于同步判断逻辑**，界面请收集 [connected]。 */
    fun isConnected(): Boolean = service != null

    /**
     * 本次进程里是否成功连上过框架。
     *
     * 用来把「从未连上」（多半是没在 LSPosed 里启用）与「连上过、只是当前断了」
     * 这两种情况分开表述 —— 前者要用户去 LSPosed 里操作，后者只要等框架重绑。
     */
    fun hasEverConnected(): Boolean = everConnected

    /** 当前框架句柄；未连接时为 null。需要调用框架能力的地方先判空。 */
    fun serviceOrNull(): XposedService? = service

    /**
     * 框架报告的 API 版本；未连接时为 0。
     *
     * 用它来做「这个能力本机有没有」的判断，而不是去猜 LSPosed 的版本号：
     * 同一代 LSPosed 在不同 ROM 上暴露的能力并不一致。
     */
    fun apiVersion(): Int = runCatching { service?.apiVersion ?: 0 }.getOrDefault(0)

    /**
     * 取一组跨进程共享的设置。
     *
     * 失败返回 null（框架断线、或没有 `PROP_CAP_REMOTE` 能力）—— 调用方负责降级，
     * 这里不抛异常：设置读写在「同步不了」和「整个模块崩掉」之间必须选前者。
     */
    fun remotePreferences(group: String): SharedPreferences? = runCatching {
        service?.getRemotePreferences(group)
    }.getOrNull()
}
