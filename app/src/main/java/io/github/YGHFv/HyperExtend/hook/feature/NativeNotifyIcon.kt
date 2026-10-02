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
 *
 * ---------------------------------------------------------------------------
 * 功能来源：MIUI 原生通知图标（fankes，AGPL-3.0）。
 *
 * 本模块整体采用 AGPL-3.0，正是为了与这个来源（以及 GPL-3.0 的两个来源）相容。
 * 挂载点取自上游 `SystemUIHooker` 里 `NotificationUtilClass` 那一段：
 *
 *   shouldSubstituteSmallIcon / shouldSubstituteSmallIconForStatusBarNotification → false
 *   getCustomAppIcon(Notification, Context) → after，把结果换成通知自己的 smallIcon
 *   getSmallIcon(...) → after，「图标库修复」在这一层替换彩色小图标（见 installIconLibrary）
 *
 * 第三条 getSmallIcon 是「图标库修复」的载体（上游用它接 ANIP 快照做替换），
 * 它在不同 HyperOS 版本上有三种参数形态 —— 本模块按形态逐个挂，找不到就整条忽略。
 * 没开「图标库修复」时这条不装，行为与只做前两条的历史版本一致。
 */

package io.github.YGHFv.HyperExtend.hook.feature

import android.app.Application
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.NotifyIconLibrary
import io.github.YGHFv.HyperExtend.core.NotifyIconSyncSource
import io.github.YGHFv.HyperExtend.hook.DexScan
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「原生通知图标」。作用域：com.android.systemui。 */
internal object NativeNotifyIcon {

    private const val FEATURE = "native_notify_icon"
    private const val OPT_NO_SUBSTITUTE = "native_notify_icon.no_substitute"
    private const val OPT_CUSTOM_APP_ICON = "native_notify_icon.custom_app_icon"
    private const val OPT_ICON_FIX = "native_notify_icon.icon_fix"
    private const val OPT_ICON_FIX_PLACEHOLDER = "native_notify_icon.icon_fix_placeholder"
    private const val OPT_ICON_FIX_AUTO = "native_notify_icon.icon_fix_auto"

    /** 上次自动同步的时间标记（精确到分钟）。同一天同一分钟只发一次。 */
    private const val AUTO_TIME_FORMAT = "yyyy-MM-dd HH:mm"

    /**
     * MIUI 自己加的工具类。三个候选对应三种不同年代的包路径 —— 上游也是这么列的，
     * 因为同一个类在不同 HyperOS 版本之间搬过家。
     */
    private val UTIL_CANDIDATES = arrayOf(
        "com.android.systemui.statusbar.notification.NotificationUtil",
        "com.android.systemui.miui.statusbar.notification.NotificationUtil",
        "com.android.systemui.statusbar.notification.utils.NotifImageUtil",
    )

    /** 通知里声明小图标所属应用的 extra 键（MIUI 专有）。 */
    private const val EXTRA_OP_PKG = "miui.opPkg"

    /** 图标库同步 / 生命周期相关的协程作用域。SystemUI 进程内常驻，量级很小。 */
    private val libraryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            ModuleLog.error("$FEATURE: background icon library task failed; keeping current icons", failure)
        },
    )

    /** 被注入进程（SystemUI）的 Application 上下文。`callApplicationOnCreate` 时刻才有。 */
    @Volatile
    private var hostContext: Context? = null

    /** 自动同步的「同分钟只发一次」标记。 */
    @Volatile
    private var lastAutoMarker = ""

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val util = Reflect.loadClass(loader, *UTIL_CANDIDATES)
            ?: DexScan.findBySimpleName(loader, HookRuntime.codePaths, "NotificationUtil")
        if (util == null) {
            ModuleLog.warn("$FEATURE: NotificationUtil not found in ${UTIL_CANDIDATES.joinToString()}")
            return 0
        }
        ModuleLog.info("$FEATURE: resolved ${util.name}")

        var installed = 0
        if (settings.isOn(OPT_NO_SUBSTITUTE)) {
            installed += installNoSubstitute(util)
            installed += installIconStyleProvider(loader)
        }
        if (settings.isOn(OPT_CUSTOM_APP_ICON)) {
            installed += installCustomAppIcon(util)
        }
        if (settings.isOn(OPT_ICON_FIX)) {
            val libraryHooks = installIconLibrary(util, settings)
            installed += libraryHooks
            if (libraryHooks > 0) installed += installLibraryLifecycle(loader)
        }
        if (installed == 0) {
            ModuleLog.warn("$FEATURE: nothing installed (all sub-switches off or targets missing)")
        }
        return installed
    }

    /**
     * 关掉「用 App 图标替换通知小图标」的判断。
     *
     * 这是症状的**源头**：MIUI 在某个版本起，把这个判断的默认结果改成了 true，
     * 于是所有通知都不再看 `Notification.smallIcon`，一律显示 App 图标。
     * 把判断结果按成 false，就等于把决定权还给通知自己。
     *
     * 两个方法名都挂：老版本叫 `shouldSubstituteSmallIcon`，
     * 新版本加了 `ForStatusBarNotification` 后缀的变体。同一个 ROM 上通常只有一个存在，
     * 找不到的那个只会记一行 warn，无害。
     */
    private fun installNoSubstitute(util: Class<*>): Int {
        var count = 0
        for (name in arrayOf(
            "shouldSubstituteSmallIcon",
            "shouldSubstituteSmallIconForStatusBarNotification",
        )) {
            val methods = Reflect.findMethods(util, name).filter {
                it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.firstOrNull()?.let(StatusBarNotification::class.java::isAssignableFrom) == true
            }
            if (methods.isEmpty()) {
                ModuleLog.warn("$FEATURE: ${util.simpleName}#$name not found")
                continue
            }
            count += methods.count { method ->
                val signature = method.parameterTypes.joinToString(",") { it.simpleName }
                HookRuntime.hookReturning(method, "$FEATURE/${util.simpleName}#$name($signature)", false)
            }
        }
        return count
    }

    private fun installIconStyleProvider(loader: ClassLoader): Int {
        val provider = Reflect.loadClass(
            loader,
            "com.android.systemui.statusbar.notification.row.icon.NotificationIconStyleProviderImpl",
        ) ?: return 0
        val method = Reflect.firstMethod(provider, "shouldShowAppIcon") {
            it.parameterCount == 2 && it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.any(Context::class.java::isAssignableFrom) &&
                it.parameterTypes.any(StatusBarNotification::class.java::isAssignableFrom)
        } ?: return 0
        return if (HookRuntime.hookReturning(method, "$FEATURE/${provider.simpleName}#shouldShowAppIcon", false)) 1 else 0
    }

    /**
     * 把「自定义 App 图标」的返回值换成通知自己的 smallIcon。
     *
     * 兜住的是另一条路：即便替换判断被关掉，部分路径（焦点通知、折叠模板）仍会走到
     * `getCustomAppIcon` 直接要一个 App 图标。这里在它返回之后把结果顶掉，
     * 效果等于「无论系统问什么，答的都是通知里声明的那个小图标」。
     *
     * 声明为 `Drawable` 的方法也必须返回 `BitmapDrawable`：本机 IconManager 调用处
     * 会直接强转并取 bitmap，上游同样先转位图再包装。仅支持已确认的 Drawable / Bitmap
     * 返回形态；缺图、转换失败或实际类型不兼容时保留原结果。
     */
    private fun installCustomAppIcon(util: Class<*>): Int {
        val method = Reflect.firstMethod(util, "getCustomAppIcon") {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == Notification::class.java &&
                it.parameterTypes[1] == Context::class.java
        } ?: run {
            // 有些系统根本没有这个方法 —— 上游同样是「忽略即可」，不是错误。
            ModuleLog.info("$FEATURE: ${util.simpleName}#getCustomAppIcon(Notification,Context) absent")
            return 0
        }
        val returnType = method.returnType
        return if (HookRuntime.hookAfter(method, "$FEATURE/${util.simpleName}#getCustomAppIcon") { chain, original ->
                val notification = chain.args.getOrNull(0) as? Notification ?: return@hookAfter original
                val context = chain.args.getOrNull(1) as? Context ?: return@hookAfter original
                val smallIcon = notification.smallIcon ?: return@hookAfter original
                NotificationIconCompat.customAppIcon(
                    loadSmallIconDrawable(smallIcon, notification, context),
                    context.resources,
                    returnType,
                    original,
                )
            }
        ) 1 else 0
    }

    /**
     * 把 `Notification.smallIcon` 变成可用的 Drawable。
     *
     * 为什么要换 context：小图标是**发通知那个 App** 的资源 id，而这里是 SystemUI 的 context。
     * 对 `Icon.createWithResource(packageName, resId)` 形式来说 SystemUI 的 context 够用
     * （图标自带包名），但如果是裸 resId，就必须用那个 App 自己的 context 才解得出来 ——
     * 这正是 MIUI 塞 `miui.opPkg` extra 的用途。
     *
     * 两条路都试，都在 try 里：图标解不出来只是这一个通知回退成原样，不该影响别的通知。
     */
    private fun loadSmallIconDrawable(
        icon: android.graphics.drawable.Icon,
        notification: Notification,
        context: Context,
    ): Drawable? {
        Reflect.attempt { icon.loadDrawable(context) }?.let { return it }
        val opPkg = Reflect.attempt { notification.extras?.getString(EXTRA_OP_PKG) } ?: return null
        val appContext = Reflect.attempt {
            context.createPackageContext(opPkg, Context.CONTEXT_IGNORE_SECURITY)
        } ?: return null
        return Reflect.attempt { icon.loadDrawable(appContext) }
    }

    // ------------------------------------------------------------------ 图标库修复

    /**
     * 「图标库修复」的替换层：挂 `getSmallIcon` 的三种历史形态。
     *
     * 上游的对应逻辑（`compatCustomIcon`）就在这一层：应用没发规范单色小图标
     * （即 smallIcon 是**彩色**的）而图标库里适配过它时，改用库里的图标；
     * 库里没有、又开了「占位图标」时给统一占位图。**规范的单色小图标永远不动** ——
     * 那本来就是系统的正确答案，替换它反而把功能做成了「换皮」。
     *
     * @return 挂上的 hook 个数
     */
    private fun installIconLibrary(util: Class<*>, settings: HookSettings): Int {
        val methods = util.declaredMethods.filter { method ->
            if (method.name != "getSmallIcon") return@filter false
            val parameters = method.parameterTypes
            val supportedParameters = when (parameters.size) {
                1 -> StatusBarNotification::class.java.isAssignableFrom(parameters[0])
                2 -> (StatusBarNotification::class.java.isAssignableFrom(parameters[0]) &&
                    parameters[1] == Int::class.javaPrimitiveType) ||
                    (Context::class.java.isAssignableFrom(parameters[0]) &&
                        StatusBarNotification::class.java.isAssignableFrom(parameters[1]))
                else -> false
            }
            supportedParameters && (method.returnType == Icon::class.java ||
                method.returnType == Bitmap::class.java || Drawable::class.java.isAssignableFrom(method.returnType))
        }
        if (methods.isEmpty()) {
            // 上游同样「没有这个方法就忽略」—— 有些版本根本不走这条路。
            ModuleLog.info("$FEATURE: ${util.simpleName}#getSmallIcon absent — library layer skipped")
            return 0
        }
        var installed = 0
        for (method in methods) {
            val signature = method.parameterTypes.joinToString(",") { it.simpleName }
            val ok = HookRuntime.hookAfter(method, "$FEATURE/${util.simpleName}#getSmallIcon($signature)") { chain, original ->
                substituteFromLibrary(chain.args, method.returnType, original, settings)
            }
            if (ok) installed++
        }
        ModuleLog.info("$FEATURE: icon library layer on $installed getSmallIcon variant(s)")
        return installed
    }

    /**
     * `getSmallIcon` after 的替换判定。
     *
     * 三种历史形态的「StatusBarNotification 在第几个参数」不一样 —— 统一在这里按参数
     * 类型找（含 MIUI 的 ExpandedNotification 子类，它有 `getNotification()`）。
     */
    private fun substituteFromLibrary(
        args: List<Any?>,
        returnType: Class<*>,
        original: Any?,
        settings: HookSettings,
    ): Any? {
        if (settings.isOn(OPT_ICON_FIX).not()) return original
        val context = hostContext ?: args.filterIsInstance<Context>().firstOrNull() ?: return original
        val identity = resolveNotification(args) ?: return original

        val small = Reflect.attempt { identity.notification.smallIcon?.loadDrawable(context) } ?: return original
        val colored = NotificationIconCompat.isGrayscale(small, context.resources) == false

        // 彩色小图标才值得替换；规范的单色图标保持原样（见方法注释）。
        if (!colored) return original

        val libraryBitmap = NotifyIconLibrary.bitmapFor(identity.packageName)
        if (libraryBitmap != null) return wrapIcon(libraryBitmap, context, returnType, original)

        if (settings.isOn(OPT_ICON_FIX_PLACEHOLDER)) {
            return wrapIcon(placeholderBitmap(context), context, returnType, original)
        }
        return original
    }

    /** 把位图包成宿主要求的返回类型（`Icon` 优先；`Drawable` 形态的旧版本给 BitmapDrawable）。 */
    private fun wrapIcon(bitmap: Bitmap, context: Context, returnType: Class<*>, original: Any?): Any? {
        if (bitmap.isRecycled) return original
        return if (returnType == Icon::class.java) {
            Reflect.attempt { Icon.createWithBitmap(bitmap) } ?: original
        } else {
            NotificationIconCompat.customAppIcon(BitmapDrawable(context.resources, bitmap), context.resources, returnType, original)
        }
    }

    private class NotificationIdentity(val packageName: String, val notification: Notification)

    /**
     * 在 getSmallIcon 的参数里找通知身份：包名 + 通知实例。
     *
     * - `StatusBarNotification` 包含 Notification；MIUI 的 ExpandedNotification 继承前者；
     * - 应用声明的 `app_package` 优先，其次取 StatusBarNotification 的包名，
     *   最后回退到通知 extra 的 `miui.opPkg`；
     * - 全都拿不到包名的参数直接跳过 —— 宁可不替换，也不对着空包名乱查库。
     */
    private fun resolveNotification(args: List<Any?>): NotificationIdentity? {
        for (arg in args) {
            val notification = when (arg) {
                is StatusBarNotification -> arg.notification
                is Notification -> arg
                else -> continue
            }
            val pkg = notification.extras?.getString(EXTRA_APP_PKG)?.takeIf { it.isNotBlank() }
                ?: (arg as? StatusBarNotification)?.packageName
                ?: notification.extras?.getString(EXTRA_OP_PKG)
            if (pkg.isNullOrBlank()) continue
            return NotificationIdentity(pkg, notification)
        }
        return null
    }

    /**
     * 占位图：一个白色圆角「对话气泡」，现场画而不是引入模块资源。
     *
     * 上游用的是自己 APK 里的一张消息图标（需要资源注入）。本模块不往 SystemUI
     * 注入资源 —— 那是一整条容易碎的路 —— 而占位图本来就是一个「聊胜于无」的东西，
     * 现场画一个不失语义。
     */
    private fun placeholderBitmap(context: Context): Bitmap {
        val size = context.resources.displayMetrics.densityDpi / 4
        val side = size.coerceIn(24, 96)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val body = RectF(side * 0.10f, side * 0.16f, side * 0.90f, side * 0.70f)
        val radius = side * 0.16f
        canvas.drawRoundRect(body, radius, radius, paint)
        // 气泡尾巴：一个左下角的小三角。
        val tail = android.graphics.Path().apply {
            moveTo(side * 0.28f, side * 0.66f)
            lineTo(side * 0.28f, side * 0.86f)
            lineTo(side * 0.46f, side * 0.66f)
            close()
        }
        canvas.drawPath(tail, paint)
        return bitmap
    }

    /**
     * 图标库生命周期：拿宿主 Application 上下文、恢复/同步快照、注册广播。
     *
     * ## 为什么要等 `callApplicationOnCreate`
     *
     * API 102 没有「给我宿主 Context」的接口，而 ANIP 的缓存目录、SystemUI 的广播
     * 注册都需要一个真 Context。`Instrumentation#callApplicationOnCreate` 是所有应用
     * 进程的共同必经点，此刻 Application 已可用 —— 在这里只做「记住 context」这一件事，
     * 后续动作全部转交 [startLibrary]。
     *
     * ## 自动更新
     *
     * 上游的做法一样：SystemUI 进程注册 TIME_TICK，到点自己 fetch（缓存是按 uid
     * 隔离的，SystemUI 必须自给自足）。模块 App 侧的「立即同步」通过 [NotifyIconLibrary.SYNC_ACTION]
     * 广播叫醒本进程重刷。
     */
    private fun installLibraryLifecycle(loader: ClassLoader): Int {
        val instrumentation = Reflect.loadClass(loader, "android.app.Instrumentation") ?: run {
            ModuleLog.warn("$FEATURE: Instrumentation not found — icon library lifecycle skipped")
            return 0
        }
        val method = instrumentation.declaredMethods.firstOrNull {
            it.name == "callApplicationOnCreate" &&
                it.parameterTypes.size == 1 &&
                Application::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        if (method == null) {
            ModuleLog.warn("$FEATURE: callApplicationOnCreate not found — icon library lifecycle skipped")
            return 0
        }
        val ok = HookRuntime.hookAfter(method, "$FEATURE/Instrumentation#callApplicationOnCreate") { chain, original ->
            val app = chain.args.getOrNull(0) as? Application
            if (app != null) {
                hostContext = app
                startLibrary(app)
            }
            original
        }
        return if (ok) 1 else 0
    }

    /** 上下文就绪后的一次性启动：恢复缓存 → 网络同步 → 注册广播。 */
    private fun startLibrary(app: Application) {
        // 幂等：热重载或多次 install 时 startLibrary 可能被再次走到。
        if (libraryStarted.getAndSet(true)) return
        libraryScope.launch {
            val source = NotifyIconSyncSource.fromVariant(
                HookRuntime.settings().string(NOTIFY_ICON_SOURCE_KEY),
            )
            // 先从私有缓存恢复（离线也有图标可用），随后无论如何都同步一次 ——
            // restore 成功只说明「有一份旧资源」，不代表它是新的。
            NotifyIconLibrary.restore(app, source)
            NotifyIconLibrary.refresh(app, source)
        }
        registerLibraryReceivers(app)
    }

    private val libraryStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** TIME_TICK（到点自动同步）+ SYNC_ACTION（模块 App 点了「立即同步」）。 */
    private fun registerLibraryReceivers(app: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    when (intent.action) {
                        Intent.ACTION_TIME_TICK -> maybeAutoRefresh(context)
                        NotifyIconLibrary.SYNC_ACTION -> libraryScope.launch {
                            val source = NotifyIconSyncSource.fromVariant(
                                HookRuntime.settings().string(NOTIFY_ICON_SOURCE_KEY),
                            )
                            NotifyIconLibrary.refresh(context, source)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(NotifyIconLibrary.SYNC_ACTION)
        }
        Reflect.attempt {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 同步广播来自模块 App（另一个 uid），接收器必须是 exported 的才能收到。
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
        } ?: ModuleLog.warn("$FEATURE: register icon library receivers failed")
    }

    /**
     * TIME_TICK 里的到点判定：本机时间（HH:mm）等于配置的时刻、且这一分钟没同步过。
     * 每次都重读设置 —— 用户改了时间/关了自动更新，下一分钟就生效，不需要重启。
     */
    private fun maybeAutoRefresh(context: Context) {
        val settings = HookRuntime.settings()
        if (!settings.isOn(FEATURE) || !settings.isOn(OPT_ICON_FIX) || !settings.isOn(OPT_ICON_FIX_AUTO)) return
        val configured = settings.string(NOTIFY_ICON_AUTO_TIME_KEY).ifBlank { DEFAULT_AUTO_TIME }
        val nowMarker = SimpleDateFormat(AUTO_TIME_FORMAT, Locale.US).format(Date())
        if (!nowMarker.endsWith(" $configured")) return
        if (nowMarker == lastAutoMarker) return
        lastAutoMarker = nowMarker
        val source = NotifyIconSyncSource.fromVariant(settings.string(NOTIFY_ICON_SOURCE_KEY))
        ModuleLog.info("$FEATURE: auto refreshing icon library at $configured")
        libraryScope.launch { NotifyIconLibrary.refresh(context, source, notifyPhase = true) }
    }

    private const val NOTIFY_ICON_SOURCE_KEY = "native_notify_icon.sync_source"
    private const val NOTIFY_ICON_AUTO_TIME_KEY = "native_notify_icon.auto_time"
    private const val DEFAULT_AUTO_TIME = "03:00"
    private const val EXTRA_APP_PKG = "app_package"
}
