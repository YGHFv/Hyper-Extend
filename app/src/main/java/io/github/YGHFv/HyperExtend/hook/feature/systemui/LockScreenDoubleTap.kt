/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.util.WeakHashMap

internal object LockScreenDoubleTap {
    fun install(loader: ClassLoader): Int {
        val container = Reflect.loadClass(loader, SystemUiTargets.SHADE_CONTAINER) ?: return 0
        val touch = Reflect.declaredMethodsIncludingInherited(container).firstOrNull {
            it.name == "onTouchEvent" && it.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java)) &&
                it.returnType == Boolean::class.javaPrimitiveType
        } ?: return 0
        val sleep = PowerManager::class.java.getDeclaredMethod("goToSleep", Long::class.javaPrimitiveType)
        val trackers = WeakHashMap<View, DoubleTapTracker>()
        // Only handle events that reached the container itself; child controls keep their
        // touch targets and parents can still intercept a swipe normally.
        return if (HookRuntime.hookAfter(touch, "lockscreen_double_tap/onTouchEvent") { chain, original ->
                var acceptBackgroundTouch = false
                val view = chain.thisObject as? View
                if (view != null && container.isInstance(view)) {
                    val event = chain.args[0] as? MotionEvent
                    if (event != null) {
                        val context = view.context
                        val tracker = trackers.getOrPut(view) {
                            val configuration = ViewConfiguration.get(context)
                            DoubleTapTracker(
                                configuration.scaledDoubleTapSlop.toFloat(),
                                dragSlop = configuration.scaledTouchSlop.toFloat(),
                            )
                        }
                        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
                        val exploring = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
                        if (!locked || exploring || event.pointerCount != 1) tracker.reset()
                        else {
                            // Without accepting DOWN, an unclickable blank container never
                            // receives UP, so a completed-tap detector could never fire.
                            acceptBackgroundTouch = true
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> tracker.down(event.eventTime, event.x, event.y)
                                MotionEvent.ACTION_MOVE -> tracker.move(event.x, event.y)
                                MotionEvent.ACTION_UP -> if (tracker.up(event.eventTime, event.x, event.y)) {
                                    Reflect.attempt { sleep.invoke(context.getSystemService(PowerManager::class.java), SystemClock.uptimeMillis()) }
                                }
                                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> tracker.reset()
                            }
                        }
                    }
                }
                if (acceptBackgroundTouch) true else original
            }) 1 else 0
    }
}
