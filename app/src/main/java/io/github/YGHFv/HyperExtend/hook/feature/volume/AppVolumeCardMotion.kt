/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import io.github.YGHFv.HyperExtend.core.ModuleLog

/** Geometry morph with native SIZE/POSITION/COLOR springs, without plugin view dependencies. */
internal class AppVolumeCardMotion(private val body: View) {
    private var running: AnimatorSet? = null
    private var observer: ViewTreeObserver? = null
    private var pending: ViewTreeObserver.OnPreDrawListener? = null
    private var closeCompletion: (() -> Unit)? = null

    fun cancel(reset: Boolean = true) {
        pending?.let { if (observer?.isAlive == true) observer?.removeOnPreDrawListener(it) }
        pending = null; observer = null
        running?.let { it.removeAllListeners(); it.cancel() }
        running = null
        if (reset) {
            body.alpha = 1f; body.scaleX = 1f; body.scaleY = 1f
            body.translationX = 0f; body.translationY = 0f
        }
        val finish = closeCompletion
        closeCompletion = null
        finish?.invoke()
    }

    fun show(style: () -> AppVolumePanelStyle?) {
        cancel()
        body.alpha = 0f
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (body.width <= 0 || body.height <= 0 || body.isLayoutRequested) return true
                if (observer?.isAlive == true) observer?.removeOnPreDrawListener(this)
                pending = null; observer = null
                val current = style()
                runCatching {
                    if (current == null) cancel() else animate(current, true) {}
                }.onFailure { cancel(); ModuleLog.error("app_volume expand animation failed", it) }
                return true
            }
        }
        pending = listener
        observer = body.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
        body.invalidate()
    }

    fun hide(style: AppVolumePanelStyle?, complete: () -> Unit) {
        cancel(false)
        if (style == null || body.width <= 0 || !ValueAnimator.areAnimatorsEnabled()) {
            cancel(); complete(); return
        }
        runCatching { animate(style, false, complete) }.onFailure {
            closeCompletion = null
            cancel(); complete()
            ModuleLog.error("app_volume collapse animation failed", it)
        }
    }

    private fun animate(style: AppVolumePanelStyle, enter: Boolean, complete: () -> Unit) {
        if (!ValueAnimator.areAnimatorsEnabled()) { cancel(); complete(); return }
        // Parent coordinates exclude this card's in-flight scale/translation when reversing.
        val location = IntArray(2).also { (body.parent as View).getLocationOnScreen(it) }
        val right = (location[0] + body.right).toFloat()
        val top = (location[1] + body.top).toFloat()
        val targetX = style.sourceLeft + style.sourceWidth - right
        val targetY = style.sourceTop - top
        val scaleX = style.sourceWidth.toFloat() / body.width
        val scaleY = style.sourceHeight.toFloat() / body.height
        body.pivotX = body.width.toFloat(); body.pivotY = 0f
        if (enter) {
            body.scaleX = scaleX; body.scaleY = scaleY
            body.translationX = targetX; body.translationY = targetY; body.alpha = 0f
        }
        val fromScaleX = body.scaleX; val fromScaleY = body.scaleY
        val fromX = body.translationX; val fromY = body.translationY; val fromAlpha = body.alpha
        val offset = if (enter) 0 else 3
        fun track(index: Int, update: (Float) -> Unit): ValueAnimator {
            val spring = style.springs[offset + index]
            return ValueAnimator.ofFloat(0f, 1f).apply {
                duration = spring.durationMillis
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val fraction = it.animatedFraction
                    update(if (fraction >= 1f) 1f else spring.progress(fraction * duration / 1000.0))
                }
            }
        }
        fun mix(from: Float, to: Float, p: Float) = from + (to - from) * p
        val set = AnimatorSet()
        if (!enter) closeCompletion = complete
        set.playTogether(
            track(0) { p ->
                body.scaleX = mix(fromScaleX, if (enter) 1f else scaleX, p)
                body.scaleY = mix(fromScaleY, if (enter) 1f else scaleY, p)
            },
            track(1) { p ->
                body.translationX = mix(fromX, if (enter) 0f else targetX, p)
                body.translationY = mix(fromY, if (enter) 0f else targetY, p)
            },
            track(2) { p -> body.alpha = mix(fromAlpha, if (enter) 1f else 0f, p).coerceIn(0f, 1f) },
        )
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (running !== animation) return
                running = null
                closeCompletion = null
                if (enter) cancel()
                complete()
            }
        })
        running = set
        set.start()
    }
}
