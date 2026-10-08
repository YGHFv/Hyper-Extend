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

package io.github.YGHFv.HyperExtend.hook

import android.content.SharedPreferences
import io.github.YGHFv.HyperExtend.config.HyperSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 被注入进程的运行时上下文。
 *
 * 存在的理由有三个：
 *
 * 1. **框架引用要有一个进程级的落点**。libxposed 的 `XposedModule` 实例由框架在进程内创建，
 *    各 feature 需要它才能挂 hook、读 RemotePreferences、写框架日志。逐个 feature 传参会把
 *    签名搞得很脏，且 feature 之间还要互相调用（通行密钥的 system_server 侧就同时挂三处）。
 * 2. **日志出口要能在框架可用与不可用两种情况下都工作**。日志本身不能依赖框架 ——
 *    框架没挂上恰恰是最需要看日志的时候，而那时 `XposedModule.log` 还不存在。
 * 3. **热重载要有一个跨代的落点**。框架热重载时不会重放 `onPackageReady`（见
 *    `HyperXposedEntry#onHotReloaded`），重新挂载所需的三样东西 —— 宿主包名、它的
 *    ClassLoader、它的 ApplicationInfo —— 必须被记下来，否则重载之后一个 hook 都装不上。
 *
 * ⚠️ 本类引用 `io.github.libxposed.api`，**只能在被注入的进程里加载**。
 * 模块 App 进程绝不触碰它（那个进程里这些类由 `compileOnly` 保证不存在，一碰就是
 * NoClassDefFoundError）。
 */
internal object HookRuntime {

    /** 当前进程的模块实例。进程内唯一。 */
    @Volatile
    private var module: XposedModule? = null

    /** 本次会话已装成功的 hook 数量。界面「关于」页显示它做「到底装上没有」的快速判据。 */
    private val installedHooks = AtomicInteger(0)

    /** 累计的失败次数。大于 0 就该去看日志了。 */
    private val failedHooks = AtomicInteger(0)

    /**
     * 宿主 APK 的 dex 路径（主包 + split）。
     *
     * 由 [HookDispatcher] 在分派时填一次，供 [DexScan] 在「写死的类名找不到」时兜底扫描。
     * 之所以放在运行时上下文而不是逐个 feature 传参：它跟 feature 本身无关，
     * 是「这个进程里宿主是谁」的属性，两种获取途径（PackageReadyParam / hot reload 的 extras）
     * 都天然拿得到，传参会把每个 feature 的签名都污染一遍。
     */
    @Volatile
    private var hostCodePaths: List<String> = emptyList()

    /** 宿主身份两件套。热重载后靠它们重新分派 —— 见类注释第 3 条。 */
    @Volatile
    private var hostPackageName: String? = null

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    /**
     * 宿主的数据目录（`/data/data/<包名>`）。
     *
     * 存在的理由是**跨进程把东西交出去**：注入侧能写、模块侧也能读到的位置只有宿主的
     * 私有目录（模块侧用 root 读它，见 `core/RootAccess.catBytes`）。
     * 宿主 App 自己的目录里不需要 Context —— 路径直接拼得出来，
     * 而在这个进程里拿一个 Context 反而要碰 `ActivityThread` 那套隐藏 API（各版本都可能被拦）。
     */
    @Volatile
    private var hostDataDir: String? = null

    /**
     * 进程内唯一的 RemotePreferences。
     *
     * **必须缓存**：`getRemotePreferences()` 每次调用都是一次跨进程握手，而 [settings] 会被
     * 装 hook 的过程反复调用（每个 feature、每个子项一次）。缓存的是**句柄**不是值 ——
     * 每次 `getBoolean` 仍然会读到最新值，所以「界面改完开关要重载/重启才生效」这件事
     * 与它无关（那是 hook 本身只在安装时读一次设置造成的，不是这里）。
     *
     * 读失败时置 null（不缓存失败结果）：框架偶尔晚一步就绪，缓存一次失败会让整个进程
     * 从此读不到任何开关。
     */
    @Volatile
    private var remotePreferences: SharedPreferences? = null

    fun setHostCodePaths(paths: List<String>) {
        hostCodePaths = paths
    }

    val codePaths: List<String> get() = hostCodePaths

    fun rememberHost(packageName: String, loader: ClassLoader) {
        hostPackageName = packageName
        hostClassLoader = loader
    }

    /** 记下宿主数据目录（`ApplicationInfo.dataDir`）。热重载后仍要能用，所以要缓存。 */
    fun setHostDataDir(dir: String?) {
        if (!dir.isNullOrBlank()) hostDataDir = dir
    }

    val dataDir: String? get() = hostDataDir

    val hostPackage: String? get() = hostPackageName
    val hostLoader: ClassLoader? get() = hostClassLoader

    fun attach(instance: XposedModule) {
        module = instance
    }

    /**
     * 本次分派装上了几个 / 失败几个。
     *
     * 「改完开关、点应用改动、看结果」是本模块最常见的操作闭环，而注入侧的失败（宿主的类改名了、
     * 框架拒绝了）在界面上是看不见的 —— 用户唯一能自查的地方就是日志。把这两个数字**并进
     * 那一条分派摘要**（而不是单开一行），是为了让 `dispatch[...]` 这一行自己就够用：
     * 装了 0 个和装了 3 个，是完全不同的两件事。
     */
    fun hookSummary(): String = "hooks=${installedHooks.get()}, failed=${failedHooks.get()}"

    /**
     * 读开关。
     *
     * 句柄缓存一次，取值不缓存：设置改完之后要生效仍然需要让宿主重载/重启（见
     * `HookDispatcher` 与 `core/HotReloader` 的关系），但「同一份设置在一次读取里前后不一致」
     * 这种问题不会有 —— 每个调用点拿到的是同一个 prefs 对象上的同一个键。
     *
     * 框架缺少 `PROP_CAP_REMOTE` 时返回一份全关的设置，而不是抛异常。
     */
    fun settings(): HookSettings {
        val instance = module ?: return HookSettings(null)
        remotePreferences?.let { return HookSettings(it) }
        val prefs: SharedPreferences? = try {
            instance.getRemotePreferences(HyperSettings.GROUP)
        } catch (t: Throwable) {
            ModuleLog.error("getRemotePreferences failed (framework lacks PROP_CAP_REMOTE?)", t)
            null
        }
        if (prefs != null) remotePreferences = prefs
        return HookSettings(prefs)
    }

    /**
     * 丢弃缓存的句柄，下一次 [settings] 重新握手。
     *
     * 热重载时调用：新的一代模块代码可能与框架重新协商过这个 prefs 对象。
     */
    fun refreshSettingsHandle() {
        remotePreferences = null
    }

    /** A caller may already contain an inlined copy of a method we are about to hook. */
    fun deoptimize(target: Executable, label: String): Boolean {
        val success = Reflect.attempt { module?.deoptimize(target) } == true
        if (!success) ModuleLog.warn("deoptimize failed: $label")
        return success
    }

    /**
     * 装一个 hook。方法（[Method]）与构造函数（[Constructor]）都收 ——
     * libxposed 的 `hook` / `deoptimize` 本来就只接受 [Executable] 这一个类型，两者通吃。
     *
     * 三件事必须一起成立，所以不能直接用 `module.hook(...)`：
     *
     * 1. **失败不能抛**。[target] 为 null（宿主里没有这个方法）时只记日志；
     *    `hook()` 本身失败（框架拒绝、方法已被内联）也只记日志。
     * 2. **拦截体里不能抛**。宿主进程里任何冒泡的异常都可能让 SystemUI 重启或
     *    system_server 进入开机循环，所以即使 [block] 抛了也要兜住并让原方法正常跑完。
     * 3. **要能数清楚装上了几个**。这是「改完开关看效果」唯一可自查的量化信号，
     *    由 [hookSummary] 并进分派摘要那一行。句柄不在这里记 —— 撤销上一代 hook 由框架
     *    交回的 `oldHookHandles` 负责，而 `setId` 让「重复挂同一个方法」变成替换语义（见下）。
     *
     * @param label 日志里用的名字，建议写成 `功能/类#方法`，便于在长日志里定位。
     * @return 是否装上
     */
    fun hook(target: Executable?, label: String, block: (XposedInterface.Chain) -> Any?): Boolean {
        if (target == null) {
            ModuleLog.warn("hook target missing: $label")
            return false
        }
        val instance = module
        if (instance == null) {
            ModuleLog.error("hook skipped (module not attached): $label")
            return false
        }
        return try {
            target.isAccessible = true
            // deoptimize 让内联过的方法重新可挂；失败不致命（有的框架/ROM 不支持），
            // 但**必须记下来** —— 「hook 装上了却不触发」十有八九就是这个原因。
            if (Reflect.attempt { instance.deoptimize(target) } == false) {
                ModuleLog.warn("deoptimize returned false: $label")
            }
            // 链式里的每一步都必须在 hook 之前设完（见 setId 的注释），intercept 是最后一步。
            instance.hook(target)
                // API 102 scopes IDs by module and executable. Reusing the same ID
                // atomically replaces that hook; distinct IDs can coexist on one method.
                // Stable labels let hot reload replace the previous generation.
                .setId(label)
                // We contain module failures ourselves. Host exceptions must not trigger framework replay.
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val invocation = HookInvocation()
                    val guarded = object : XposedInterface.Chain by chain {
                        override fun proceed(): Any? = invocation.proceed { chain.proceed() }
                        override fun proceed(args: Array<out Any?>): Any? = invocation.proceed { chain.proceed(args) }
                        override fun proceedWith(receiver: Any): Any? = invocation.proceed { chain.proceedWith(receiver) }
                        override fun proceedWith(receiver: Any, args: Array<out Any?>): Any? =
                            invocation.proceed { chain.proceedWith(receiver, args) }
                    }
                    invocation.protect({ block(guarded) }, { chain.proceed() }) {
                        ModuleLog.error("interceptor failed; preserving single host invocation: $label", it)
                    }
                }
            installedHooks.incrementAndGet()
            ModuleLog.info("hook installed: $label")
            true
        } catch (t: Throwable) {
            failedHooks.incrementAndGet()
            ModuleLog.error("hook install failed: $label", t)
            false
        }
    }

    fun hookAfter(
        target: Executable?,
        label: String,
        block: (XposedInterface.Chain, Any?) -> Any?,
    ): Boolean = hook(target, label) { chain ->
        val original = chain.proceed()
        try {
            block(chain, original)
        } catch (failure: Throwable) {
            ModuleLog.error("postprocessor threw, keeping original result: $label", failure)
            original
        }
    }

    /** 装一个类的全部同名方法（重载都算）。 */
    fun hookAll(clazz: Class<*>?, name: String, label: String, block: (XposedInterface.Chain) -> Any?): Int {
        if (clazz == null) {
            ModuleLog.warn("hookAll target class missing: $label")
            return 0
        }
        return Reflect.findMethods(clazz, name).count { hook(it, "$label#$name", block) }
    }

    /** 只做「把返回值换成固定值」的 hook，覆盖绝大多数「关掉某个判断」的场景。 */
    fun hookReturning(method: Method?, label: String, value: Any?): Boolean =
        hook(method, label) { value }
}
