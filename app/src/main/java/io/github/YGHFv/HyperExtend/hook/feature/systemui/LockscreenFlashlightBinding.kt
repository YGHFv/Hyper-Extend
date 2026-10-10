/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.KeyguardManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime

internal class LockscreenFlashlightBinding(
    val image: ImageView,
    private val layout: View,
    private val bridge: LockscreenFlashlightBridge,
    private val controller: Any,
    private val owns: () -> Boolean,
    private val ordinaryLockscreen: () -> Boolean,
    private val removed: () -> Unit,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private val main = Handler(Looper.getMainLooper())
    private val power = image.context.getSystemService(PowerManager::class.java)
    private val keyguard = image.context.getSystemService(KeyguardManager::class.java)
    private val drawableState = ShortcutOwnedValue<Drawable>()
    private val descriptionState = ShortcutOwnedValue<CharSequence>()
    private val tintState = ShortcutOwnedValue<ColorStateList>()
    private var observer: ViewTreeObserver? = null
    private var disposed = false
    private var listening = false
    private var active = false
    var generation = 0L
        private set
    private var state: Pair<Boolean, Boolean>? = null
    private var icon: Drawable? = null
    private var description: CharSequence? = null
    private var configuration: Configuration? = null
    private var iconSize = 0
    private val refresh = Runnable { guarded { update() } }
    private val callback = bridge.callback {
        // Callbacks may arrive on a camera thread; coalesce and reject detached bindings on main.
        main.removeCallbacks(refresh)
        main.post(refresh)
    }

    fun start() {
        image.addOnAttachStateChangeListener(this)
        if (image.isAttachedToWindow) onViewAttachedToWindow(image)
    }
    fun usable(): Boolean = !disposed && active && eligible()
    private fun eligible(): Boolean = !SafeModeRuntime.blocked && owns() && image.isAttachedToWindow &&
        image.display?.displayId == 0 && image.isShown && image.windowVisibility == View.VISIBLE &&
        image.alpha == 1f && layout.alpha == 1f && ordinaryLockscreen()

    fun toggle() = guarded {
        if (usable() && power?.isInteractive == true && keyguard?.isKeyguardLocked == true && bridge.toggle(controller))
            image.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Do not optimistically change the icon: setFlashlight is asynchronous and can be rejected.
    }

    private fun update() {
        main.removeCallbacks(refresh)
        if (disposed) return
        if (!owns() || SafeModeRuntime.blocked) { dispose(); return }
        if (!eligible()) { stop(); return }
        if (!listening) {
            listening = true
            bridge.add(controller, callback)
        }
        val next = bridge.enabled(controller) to bridge.available(controller)
        val config = image.resources.configuration
        val size = minOf((48 * image.resources.displayMetrics.density).toInt(),
            image.width.takeIf { it > 0 } ?: Int.MAX_VALUE, image.height.takeIf { it > 0 } ?: Int.MAX_VALUE)
        if (state != next || icon == null || configuration != config || iconSize != size) {
            state = next
            iconSize = size
            configuration = Configuration(config)
            val dark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            icon = FlashlightIcon(next.first, next.second, dark, size)
            description = if (!next.second) "手电筒不可用" else if (next.first) "关闭手电筒" else "打开手电筒"
        }
        tintState.apply(image.imageTintList, null)
        if (image.imageTintList != null) image.imageTintList = null
        val replacement = drawableState.apply(image.drawable, icon)
        if (image.drawable !== replacement) image.setImageDrawable(replacement)
        val text = descriptionState.apply(layout.contentDescription, description)
        if (layout.contentDescription !== text) layout.contentDescription = text
        active = true
        // Screen-off can stop pre-draw entirely. Bound listener cleanup even without another frame.
        main.postDelayed(refresh, 1_000L)
    }

    private fun stop() {
        if (!active && !listening && icon == null) { main.removeCallbacks(refresh); return }
        if (active) { active = false; generation++ }
        try {
            if (listening) { listening = false; bridge.remove(controller, callback) }
        } finally {
            main.removeCallbacks(refresh)
            val native = drawableState.restore(image.drawable)
            if (image.drawable !== native) image.setImageDrawable(native)
            val nativeDescription = descriptionState.restore(layout.contentDescription)
            if (layout.contentDescription !== nativeDescription) layout.contentDescription = nativeDescription
            val nativeTint = tintState.restore(image.imageTintList)
            if (image.imageTintList !== nativeTint) image.imageTintList = nativeTint
            state = null; icon = null; description = null
        }
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
        image.removeOnAttachStateChangeListener(this)
        try { stop() } finally { removed() }
    }
    private fun guarded(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            runCatching { dispose() }
            ModuleLog.error("lockscreen flashlight binding failed", failure)
            SafeModeRuntime.trip("Hook failed: lockscreen flashlight")
        }
    }
    override fun onViewAttachedToWindow(v: View) = guarded {
        if (!disposed) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = image.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            update()
        }
    }
    override fun onViewDetachedFromWindow(v: View) = guarded {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
        stop()
    }
    override fun onPreDraw(): Boolean { guarded { update() }; return true }
}

/** Opaque, high-contrast icon; deliberately independent of private module resource IDs. */
private class FlashlightIcon(on: Boolean, available: Boolean, dark: Boolean, private val size: Int) : Drawable() {
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (on || !dark) 0xfff2f2f2.toInt() else 0xff303030.toInt() }
    private val foreground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (on || !dark) 0xff202020.toInt() else 0xfff2f2f2.toInt()
        if (!available) alpha = 100
    }
    private val beam = on
    private val body = Path().apply {
        moveTo(15f, 14f); lineTo(33f, 14f); lineTo(33f, 21f); lineTo(28f, 27f)
        lineTo(28f, 37f); lineTo(20f, 37f); lineTo(20f, 27f); lineTo(15f, 21f); close()
    }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 48f, bounds.height() / 48f)
        canvas.drawCircle(24f, 24f, 23f, background)
        canvas.drawPath(body, foreground)
        canvas.drawRect(22f, 28f, 26f, 31f, background)
        if (beam) canvas.drawRect(17f, 9f, 31f, 11f, foreground)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { background.alpha = alpha; foreground.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { background.colorFilter = colorFilter; foreground.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
}
