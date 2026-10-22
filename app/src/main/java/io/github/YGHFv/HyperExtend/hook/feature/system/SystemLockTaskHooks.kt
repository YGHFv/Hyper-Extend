/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.content.Context
import android.os.Binder
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 引导式访问（系统固定任务）。
 *
 * 系统框架侧负责三秒电源键退出、状态栏/导航栏屏蔽和退出后锁屏；系统界面侧负责
 * 隐藏状态栏与手势条，并在启用「阻止固定弹窗」时直接把任务交给系统固定。
 */
internal object SystemLockTaskHooks {
    private const val FEATURE = "system_framework_guided_access"
    private const val STATUS_OPTION = "system_framework_guided_access_status"
    private const val EXIT_LOCKSCREEN_OPTION = "system_framework_guided_access_exit_lockscreen"
    private const val BLOCK_DIALOG_OPTION = "system_framework_guided_access_block_dialog"

    private const val LOCK_APP_SETTING = "key_lock_app"
    private const val HIDE_GESTURE_LINE_SETTING = "hide_gesture_line"
    private const val HIDE_GESTURE_LINE_SHOW = 0
    private const val HIDE_GESTURE_LINE_HIDE = 1

    private const val POWER_HOLD_EXIT_MS = 3000L
    private const val SUPPRESS_POWER_MENU_AFTER_EXIT_MS = 1500L
    private const val ACTION_LONG_PRESS_POWER_KEY = "long_press_power_key"
    private const val ACTION_IMPERCEPTIBLE_PRESS_POWER_KEY = "imperceptible_press_power_key"

    @Volatile private var locked = false
    @Volatile private var lastPowerExitUptimeMs = 0L
    @Volatile private var gestureLineBeforeLock: Int? = null

    private val statusBarViews = mutableListOf<WeakReference<View>>()
    private val gestureHandles = mutableListOf<WeakReference<View>>()
    private val navigationBars = mutableListOf<WeakReference<Any>>()
    private val taskbarDelegates = mutableListOf<WeakReference<Any>>()
    private val handleVisibility = WeakHashMap<View, Int>()
    private val handleAlpha = WeakHashMap<View, Float>()
    @Volatile private var observerContext: Context? = null

    fun installServer(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(FEATURE)) return 0
        val statusShield = settings.isOn(STATUS_OPTION)
        var count = 0
        count += installTaskManager(loader, statusShield)
        count += installPowerKey(loader)
        if (settings.isOn(EXIT_LOCKSCREEN_OPTION)) count += installExitLockscreen(loader)
        if (statusShield) count += installTransientBarShield(loader)
        return count
    }

    fun installSystemUi(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(FEATURE)) return 0
        var count = 0
        if (settings.isOn(STATUS_OPTION)) {
            count += installSystemUiShield(loader)
        }
        if (settings.isOn(BLOCK_DIALOG_OPTION)) {
            count += installSystemUiDialogBlock(loader)
        }
        return count
    }

    fun installLauncher(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(FEATURE)) return 0
        var count = 0
        val navStub = Reflect.loadClass(loader, "com.miui.home.recents.NavStubView")
        if (navStub != null) {
            val pointerEvent = Reflect.findMethods(navStub, "onPointerEvent", paramCount = 1)
                .firstOrNull { it.parameterTypes[0] == MotionEvent::class.java }
            if (pointerEvent != null &&
                HookRuntime.hook(pointerEvent, "guided_access_home/NavStubView#onPointerEvent") { chain ->
                    if (isLocked(chain.thisObject)) false else chain.proceed()
                }
            ) count++
            val mistakeTouch = Reflect.findMethods(navStub, "isMistakeTouch")
                .firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
            if (mistakeTouch != null &&
                HookRuntime.hook(mistakeTouch, "guided_access_home/NavStubView#isMistakeTouch") { chain ->
                    if (isLocked(chain.thisObject)) true else chain.proceed()
                }
            ) count++
            val touchResolution = Reflect.findMethods(navStub, "screenPinTouchResolution", paramCount = 1)
                .firstOrNull { it.parameterTypes[0] == MotionEvent::class.java }
            if (touchResolution != null &&
                HookRuntime.hook(touchResolution, "guided_access_home/NavStubView#screenPinTouchResolution") { chain ->
                    if (isLocked(chain.thisObject)) null else chain.proceed()
                }
            ) count++
        }

        val recents = Reflect.loadClass(loader, "com.miui.home.recents.views.RecentsContainer")
        val landscape = recents?.let {
            Reflect.findMethods(it, "showLandscapeOverviewGestureView", paramCount = 1).firstOrNull()
        }
        if (landscape != null &&
            HookRuntime.hook(landscape, "guided_access_home/RecentsContainer#showLandscapeOverviewGestureView") { chain ->
                if (isLocked(chain.thisObject)) null else chain.proceed()
            }
        ) count++

        if (settings.isOn(BLOCK_DIALOG_OPTION)) {
            count += hookLauncherStartPinning(loader, "com.miui.home.recents.SystemUiProxyWrapper", true)
            count += hookLauncherStartPinning(loader, "com.miui.home.recents.ScreenPinnedHelper", false)
        }
        return count
    }

    private fun installTaskManager(loader: ClassLoader, statusShield: Boolean): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.wm.ActivityTaskManagerService")
            ?: return 0
        var count = 0
        val start = Reflect.findMethods(clazz, "startSystemLockTaskMode", paramCount = 1)
            .firstOrNull { it.returnType == Void.TYPE }
        if (start != null &&
            HookRuntime.hook(start, "guided_access/ActivityTaskManagerService#startSystemLockTaskMode") { chain ->
                val result = chain.proceed()
                val taskId = chain.args.getOrNull(0) as? Int ?: -1
                updateLockState(chain.thisObject, taskId, statusShield)
                result
            }
        ) count++

        for (name in listOf("stopSystemLockTaskMode", "stopLockTaskModeInternal")) {
            for (method in Reflect.findMethods(clazz, name)) {
                if (method.returnType != Void.TYPE) continue
                if (HookRuntime.hook(method, "guided_access/ActivityTaskManagerService#$name") { chain ->
                        val result = chain.proceed()
                        updateLockState(chain.thisObject, -1, statusShield)
                        result
                    }
                ) count++
            }
        }
        return count
    }

    private fun updateLockState(service: Any?, taskId: Int, statusShield: Boolean) {
        locked = taskId != -1
        lastPowerExitUptimeMs = 0L
        val context = Reflect.readField(service ?: return, "mContext") as? Context ?: return
        val identity = Binder.clearCallingIdentity()
        try {
            Settings.Global.putInt(context.contentResolver, LOCK_APP_SETTING, taskId)
            if (statusShield) applyGestureLine(context, locked)
        } catch (failure: Throwable) {
            ModuleLog.warn("guided_access: lock state write failed (${failure.javaClass.simpleName})")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun applyGestureLine(context: Context, entering: Boolean) {
        if (entering) {
            if (gestureLineBeforeLock == null) {
                gestureLineBeforeLock = Settings.Global.getInt(
                    context.contentResolver,
                    HIDE_GESTURE_LINE_SETTING,
                    HIDE_GESTURE_LINE_SHOW,
                )
            }
            Settings.Global.putInt(
                context.contentResolver,
                HIDE_GESTURE_LINE_SETTING,
                HIDE_GESTURE_LINE_HIDE,
            )
        } else {
            gestureLineBeforeLock?.let { previous ->
                Settings.Global.putInt(
                    context.contentResolver,
                    HIDE_GESTURE_LINE_SETTING,
                    previous,
                )
            }
            gestureLineBeforeLock = null
        }
    }

    private fun installPowerKey(loader: ClassLoader): Int {
        var count = 0
        val rule = Reflect.loadClass(loader, "com.android.server.input.shortcut.singlekeyrule.PowerKeyRule")
        val timeout = rule?.let {
            Reflect.findMethods(it, "getMiuiLongPressTimeoutMs", paramCount = 0)
                .firstOrNull { method -> method.returnType == Long::class.javaPrimitiveType }
        }
        if (timeout != null &&
            HookRuntime.hook(timeout, "guided_access/PowerKeyRule#getMiuiLongPressTimeoutMs") { chain ->
                if (locked) POWER_HOLD_EXIT_MS else chain.proceed()
            }
        ) count++

        val policy = Reflect.loadClass(loader, "com.android.server.policy.BaseMiuiPhoneWindowManager")
        val postKey = policy?.let {
            Reflect.findMethods(it, "postKeyFunction", paramCount = 3).firstOrNull()
        }
        if (postKey != null &&
            HookRuntime.hook(postKey, "guided_access/BaseMiuiPhoneWindowManager#postKeyFunction") { chain ->
                val action = chain.args.getOrNull(2) as? String
                if (action != ACTION_LONG_PRESS_POWER_KEY &&
                    action != ACTION_IMPERCEPTIBLE_PRESS_POWER_KEY
                ) {
                    return@hook chain.proceed()
                }
                if (shouldSuppressPowerAction()) return@hook null
                if (!locked) return@hook chain.proceed()
                val args = chain.args.toTypedArray()
                args[1] = POWER_HOLD_EXIT_MS.toInt()
                args[2] = ACTION_LONG_PRESS_POWER_KEY
                chain.proceed(args)
            }
        ) count++

        val shortcuts = Reflect.loadClass(loader, "com.miui.server.input.util.ShortCutActionsUtils")
        for (paramCount in intArrayOf(5, 4)) {
            val trigger = shortcuts?.let {
                Reflect.findMethods(it, "triggerFunction", paramCount = paramCount).firstOrNull()
            } ?: continue
            if (HookRuntime.hook(trigger, "guided_access/ShortCutActionsUtils#triggerFunction($paramCount)") {
                    chain ->
                    val action = chain.args.getOrNull(1) as? String
                    when {
                        action == ACTION_IMPERCEPTIBLE_PRESS_POWER_KEY -> true
                        action == ACTION_LONG_PRESS_POWER_KEY && (locked || shouldSuppressPowerAction()) -> {
                            markPowerExit()
                            stopSystemLockTaskMode()
                            true
                        }
                        else -> chain.proceed()
                    }
                }
            ) count++
        }

        count += installPowerMenuSuppression(loader)
        return count
    }

    private fun installPowerMenuSuppression(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.policy.PhoneWindowManager") ?: return 0
        var count = 0
        for (method in Reflect.findMethods(clazz, "powerLongPress", paramCount = 1)) {
            if (HookRuntime.hook(method, "guided_access/PhoneWindowManager#powerLongPress") { chain ->
                    if (locked) {
                        markPowerExit()
                        stopSystemLockTaskMode()
                        Reflect.writeField(chain.thisObject, "mPowerKeyHandled", true)
                        null
                    } else if (shouldSuppressPowerAction()) {
                        Reflect.writeField(chain.thisObject, "mPowerKeyHandled", true)
                        null
                    } else {
                        chain.proceed()
                    }
                }
            ) count++
        }
        for (name in listOf("showGlobalActions", "showGlobalActionsInternal")) {
            for (method in Reflect.findMethods(clazz, name)) {
                if (HookRuntime.hook(method, "guided_access/PhoneWindowManager#$name") { chain ->
                        if (shouldSuppressPowerAction()) {
                            Reflect.writeField(chain.thisObject, "mPowerKeyHandled", true)
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                ) count++
            }
        }
        return count
    }

    private fun installExitLockscreen(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.wm.LockTaskController") ?: return 0
        val method = Reflect.findMethods(clazz, "shouldLockKeyguard", paramCount = 1)
            .firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
            ?: return 0
        return if (HookRuntime.hookReturning(
                method,
                "guided_access/LockTaskController#shouldLockKeyguard",
                true,
            )
        ) 1 else 0
    }

    private fun installTransientBarShield(loader: ClassLoader): Int {
        var count = 0
        val insets = Reflect.loadClass(loader, "com.android.server.wm.InsetsPolicy")
        val showTransient = insets?.let {
            Reflect.findMethods(it, "showTransient", paramCount = 2).firstOrNull()
        }
        if (showTransient != null &&
            HookRuntime.hook(showTransient, "guided_access/InsetsPolicy#showTransient") { chain ->
                val types = chain.args.getOrNull(0) as? Int ?: 0
                if (locked && types and WindowInsets.Type.navigationBars() != 0) null else chain.proceed()
            }
        ) count++

        val display = Reflect.loadClass(loader, "com.android.server.wm.DisplayPolicy")
        val request = display?.let {
            Reflect.findMethods(it, "requestTransientBars", paramCount = 2).firstOrNull()
        }
        if (request != null &&
            HookRuntime.hook(request, "guided_access/DisplayPolicy#requestTransientBars") { chain ->
                if (locked) null else chain.proceed()
            }
        ) count++
        return count
    }

    private fun installSystemUiShield(loader: ClassLoader): Int {
        var count = 0
        count += hookApplicationCreate(loader)

        for (name in listOf(
            "com.android.systemui.statusbar.window.StatusBarWindowControllerImpl",
            "com.android.systemui.statusbar.window.StatusBarWindowController",
        )) {
            val clazz = Reflect.loadClass(loader, name) ?: continue
            for (constructor in clazz.declaredConstructors) {
                if (HookRuntime.hook(constructor, "guided_access_ui/$name#<init>") { chain ->
                        val result = chain.proceed()
                        val context = Reflect.readField(chain.thisObject, "mContext") as? Context
                        val view = Reflect.readField(chain.thisObject, "mStatusBarWindowView") as? View
                        if (context != null) {
                            registerObserver(context)
                            if (view != null) registerStatusBarView(view)
                            updateStatusBarVisibility()
                        }
                        result
                    }
                ) count++
            }
        }

        for (name in listOf(
            "com.android.systemui.navigationbar.gestural.NavigationHandle",
            "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle",
            "com.android.systemui.navigationbar.views.NavigationHandle",
            "com.android.systemui.navigationbar.gestural.GestureHandleView",
            "com.android.systemui.navigationbar.gestural.HomeHandle",
        )) {
            val clazz = Reflect.loadClass(loader, name) ?: continue
            for (constructor in clazz.declaredConstructors) {
                if (HookRuntime.hook(constructor, "guided_access_ui/$name#<init>") { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? View)?.let(::registerGestureHandle)
                        result
                    }
                ) count++
            }
            for (method in Reflect.findMethods(clazz, "setVisibility", paramCount = 1)) {
                if (HookRuntime.hook(method, "guided_access_ui/$name#setVisibility") { chain ->
                        val view = chain.thisObject as? View
                        if (view == null || !isLocked(view)) {
                            chain.proceed()
                        } else {
                            val args = chain.args.toTypedArray()
                            args[0] = View.GONE
                            chain.proceed(args)
                        }
                    }
                ) count++
            }
            for (method in Reflect.findMethods(clazz, "setAlpha", paramCount = 1)) {
                if (HookRuntime.hook(method, "guided_access_ui/$name#setAlpha") { chain ->
                        val view = chain.thisObject as? View
                        if (view == null || !isLocked(view)) {
                            chain.proceed()
                        } else {
                            val args = chain.args.toTypedArray()
                            args[0] = 0f
                            chain.proceed(args)
                        }
                    }
                ) count++
            }
            for (method in Reflect.findMethods(clazz, "onDraw", paramCount = 1)) {
                if (HookRuntime.hook(method, "guided_access_ui/$name#onDraw") { chain ->
                        val view = chain.thisObject as? View
                        if (view != null && isLocked(view)) null else chain.proceed()
                    }
                ) count++
            }
        }

        for (name in listOf(
            "com.android.systemui.navigationbar.views.NavigationBar",
            "com.android.systemui.navigationbar.NavigationBar",
        )) {
            val clazz = Reflect.loadClass(loader, name) ?: continue
            for (constructor in clazz.declaredConstructors) {
                if (HookRuntime.hook(constructor, "guided_access_ui/$name#<init>") { chain ->
                        val result = chain.proceed()
                        registerWeak(navigationBars, chain.thisObject)
                        result
                    }
                ) count++
            }
        }

        val taskbar = Reflect.loadClass(loader, "com.android.systemui.navigationbar.TaskbarDelegate")
        taskbar?.declaredConstructors?.forEach { constructor ->
            if (HookRuntime.hook(constructor, "guided_access_ui/TaskbarDelegate#<init>") { chain ->
                    val result = chain.proceed()
                    registerWeak(taskbarDelegates, chain.thisObject)
                    result
                }
            ) count++
        }

        val launcherProxy = Reflect.loadClass(loader, "com.android.systemui.recents.LauncherProxyService\$1")
        val proxyMethod = launcherProxy?.let {
            Reflect.findMethods(it, "verifyCallerAndClearCallingIdentityPostMain", paramCount = 1).firstOrNull()
        }
        if (proxyMethod != null &&
            HookRuntime.hook(proxyMethod, "guided_access_ui/LauncherProxyService#verifyCaller") { chain ->
                val action = chain.args.getOrNull(0) as? String
                val context = resolveContext(chain.thisObject)
                if (action == "stopScreenPinning" && context != null && getLockApp(context) != -1) {
                    null
                } else {
                    chain.proceed()
                }
            }
        ) count++
        return count
    }

    private fun hookApplicationCreate(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.systemui.SystemUIApplication") ?: return 0
        val method = Reflect.findMethods(clazz, "onCreate", paramCount = 0)
            .firstOrNull { it.returnType == Void.TYPE }
            ?: return 0
        return if (HookRuntime.hookAfter(method, "guided_access_ui/SystemUIApplication#onCreate") { chain, result ->
                val context = Reflect.callWith(chain.thisObject, "getApplicationContext") as? Context
                if (context != null) {
                    registerObserver(context)
                    updateStatusBarVisibility()
                }
                result
            }
        ) 1 else 0
    }

    private fun installSystemUiDialogBlock(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks",
        ) ?: return 0
        var count = 0
        for (method in Reflect.findMethods(clazz, "showScreenPinningRequest", paramCount = 1)) {
            if (HookRuntime.hook(method, "guided_access_ui/showScreenPinningRequest") { chain ->
                    val taskId = chain.args.getOrNull(0) as? Int ?: -1
                    if (taskId < 0) {
                        chain.proceed()
                    } else {
                        val context = resolveContext(chain.thisObject)
                        if (context != null && getLockApp(context) != -1) {
                            null
                        } else {
                            startSystemLockTaskMode(taskId)
                            null
                        }
                    }
                }
            ) count++
        }
        return count
    }

    private fun hookLauncherStartPinning(
        loader: ClassLoader,
        className: String,
        returnBoolean: Boolean,
    ): Int {
        val clazz = Reflect.loadClass(loader, className) ?: return 0
        val method = Reflect.findMethods(clazz, "startScreenPinning", paramCount = 1).firstOrNull()
            ?: return 0
        return if (HookRuntime.hook(method, "guided_access_home/$className#startScreenPinning") { chain ->
                val taskId = chain.args.getOrNull(0) as? Int ?: -1
                if (taskId < 0) {
                    chain.proceed()
                } else {
                    startSystemLockTaskMode(taskId)
                    if (returnBoolean) true else null
                }
            }
        ) 1 else 0
    }

    private fun registerStatusBarView(view: View) {
        if (statusBarViews.any { it.get() === view }) return
        statusBarViews += WeakReference(view)
    }

    private fun registerGestureHandle(view: View) {
        if (gestureHandles.any { it.get() === view }) return
        gestureHandles += WeakReference(view)
        view.context?.let(::registerObserver)
        updateGestureHandles(getLockApp(view.context) != -1)
    }

    private fun <T> registerWeak(target: MutableList<WeakReference<T>>, value: Any?) {
        if (value == null) return
        @Suppress("UNCHECKED_CAST")
        val item = value as T
        if (target.any { it.get() === item }) return
        target += WeakReference(item)
    }

    private fun registerObserver(context: Context) {
        if (observerContext === context) return
        observerContext = context
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(LOCK_APP_SETTING),
                false,
                object : android.database.ContentObserver(android.os.Handler(context.mainLooper)) {
                    override fun onChange(selfChange: Boolean) {
                        updateStatusBarVisibility()
                    }
                },
            )
        }.onFailure { ModuleLog.warn("guided_access_ui: observer register failed (${it.javaClass.simpleName})") }
    }

    private fun updateStatusBarVisibility() {
        val context = observerContext ?: return
        val isLocked = getLockApp(context) != -1
        statusBarViews.removeAll { reference ->
            val view = reference.get()
            if (view == null) {
                true
            } else {
                view.visibility = if (isLocked) View.GONE else View.VISIBLE
                false
            }
        }
        updateGestureHandles(isLocked)
        if (!isLocked) {
            navigationBars.removeAll { it.get() == null }
            taskbarDelegates.removeAll { it.get() == null }
        }
    }

    private fun updateGestureHandles(isLocked: Boolean) {
        gestureHandles.removeAll { reference ->
            val view = reference.get() ?: return@removeAll true
            if (isLocked) {
                handleVisibility.getOrPut(view) { view.visibility }
                handleAlpha.getOrPut(view) { view.alpha }
                view.visibility = View.GONE
                view.alpha = 0f
            } else {
                handleVisibility.remove(view)?.let { view.visibility = it }
                handleAlpha.remove(view)?.let { view.alpha = it }
            }
            false
        }
    }

    private fun isLocked(target: Any?): Boolean =
        resolveContext(target)?.let { getLockApp(it) != -1 } ?: false

    private fun resolveContext(target: Any?): Context? {
        if (target is Context) return target
        if (target is View) return target.context
        if (target == null) return null
        (Reflect.readField(target, "mContext") as? Context)?.let { return it }
        (Reflect.readField(target, "context") as? Context)?.let { return it }
        val outer = Reflect.readField(target, "this\$0")
        return if (outer != null && outer !== target) resolveContext(outer) else null
    }

    private fun getLockApp(context: Context?): Int {
        if (context == null) return -1
        return Reflect.attempt {
            Settings.Global.getInt(context.contentResolver, LOCK_APP_SETTING)
        } ?: -1
    }

    private fun markPowerExit() {
        lastPowerExitUptimeMs = SystemClock.uptimeMillis()
    }

    private fun shouldSuppressPowerAction(): Boolean {
        val mark = lastPowerExitUptimeMs
        if (mark <= 0L) return false
        if (SystemClock.uptimeMillis() - mark <= SUPPRESS_POWER_MENU_AFTER_EXIT_MS) return true
        lastPowerExitUptimeMs = 0L
        return false
    }

    private fun stopSystemLockTaskMode() {
        Reflect.attempt {
            val service = taskManagerService() ?: return@attempt null
            Reflect.callWith(service, "stopSystemLockTaskMode")
        } ?: ModuleLog.warn("guided_access: stopSystemLockTaskMode failed")
    }

    private fun startSystemLockTaskMode(taskId: Int) {
        Reflect.attempt {
            val service = taskManagerService() ?: return@attempt null
            Reflect.callWith(service, "startSystemLockTaskMode", taskId)
        } ?: ModuleLog.warn("guided_access: startSystemLockTaskMode failed")
    }

    private fun taskManagerService(): Any? {
        val loader = javaClass.classLoader ?: ClassLoader.getSystemClassLoader()
        val clazz = Reflect.loadClass(loader, "android.app.ActivityTaskManager") ?: return null
        val getService = Reflect.findMethods(clazz, "getService", paramCount = 0).firstOrNull()
            ?: return null
        return Reflect.attempt { getService.invoke(null) }
    }
}
