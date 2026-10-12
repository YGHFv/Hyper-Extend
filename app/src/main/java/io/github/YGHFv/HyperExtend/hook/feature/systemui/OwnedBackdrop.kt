/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View

internal object OwnedBackdropTargets {
    const val BLUR = "com.android.internal.graphics.drawable.BackgroundBlurDrawable"
    const val CROSS = "android.view.CrossWindowBlurListeners"
    val allowed = SystemUiMethod("com.miui.systemui.notification.MiuiBaseNotifUtil", "isBackgroundBlurOpened",
        "boolean", listOf("android.content.Context"), isStatic = true)
    val methods = listOf(
        SystemUiMethod("android.view.View", "getViewRootImpl", "android.view.ViewRootImpl"),
        SystemUiMethod("android.view.ViewRootImpl", "createBackgroundBlurDrawable", BLUR),
        SystemUiMethod(BLUR, "setBlurRadius", "void", listOf("int")),
        SystemUiMethod(BLUR, "setCornerRadius", "void", listOf("float")),
        SystemUiMethod(BLUR, "setColor", "void", listOf("int")),
        SystemUiMethod(CROSS, "getInstance", CROSS, isStatic = true),
        SystemUiMethod(CROSS, "isCrossWindowBlurEnabled", "boolean"),
        SystemUiMethod("android.view.View", "setPassWindowBlurEnabled", "boolean", listOf("boolean")),
        SystemUiMethod("android.view.View", "clearMiBackgroundBlendColor", "void"),
        SystemUiMethod("android.view.View", "addMiBackgroundBlendColor", "void", listOf("int", "int")),
    ) + listOf("setMiViewBlurMode", "setMiBackgroundBlurMode", "setMiBackgroundBlurRadius")
        .map { SystemUiMethod("android.view.View", it, "void", listOf("int")) }
}

/** attach/clear require module-created views. drawable only borrows an attached ViewRoot. */
// 该字段只存在于被注入的宿主进程，模块自身不执行；与本包 ClipboardOverlayHooks 同一豁免理由。
@SuppressLint("BlockedPrivateApi")
internal class OwnedBackdrop(loader: ClassLoader) {
    private val t = OwnedBackdropTargets
    private val allowed = requireNotNull(NotificationHooks.resolve(loader, t.allowed))
    private val methods = t.methods.associate { it.name to requireNotNull(NotificationHooks.resolve(loader, it)) }
    private val supported = Class.forName(t.CROSS, false, loader).getDeclaredField("CROSS_WINDOW_BLUR_SUPPORTED").apply {
        require(type == Boolean::class.javaPrimitiveType); isAccessible = true
    }
    fun allowed(context: Context): Boolean = allowed.invoke(null, context) == true &&
        supported.getBoolean(null) && methods.getValue("isCrossWindowBlurEnabled")
            .invoke(methods.getValue("getInstance").invoke(null)) == true

    /** Create an owned drawable without changing the host view. The caller owns restoration. */
    fun drawable(view: View, radius: Float, color: Int): Drawable? {
        if (!view.isAttachedToWindow || !allowed(view.context)) return null
        val root = methods.getValue("getViewRootImpl").invoke(view) ?: return null
        val result = methods.getValue("createBackgroundBlurDrawable").invoke(root) as? Drawable ?: return null
        return try {
            methods.getValue("setBlurRadius").invoke(result, 100)
            methods.getValue("setCornerRadius").invoke(result, radius)
            methods.getValue("setColor").invoke(result, color)
            result
        } catch (_: Throwable) { release(result); null }
    }
    fun isBlur(drawable: Drawable) = methods.getValue("setBlurRadius").declaringClass.isInstance(drawable)
    fun release(drawable: Drawable) {
        if (isBlur(drawable)) runCatching { methods.getValue("setBlurRadius").invoke(drawable, 0) }
        drawable.setVisible(false, false)
        drawable.callback = null
    }

    fun attach(view: View, advanced: Boolean, radius: Float, color: Int): Boolean {
        if (!view.isAttachedToWindow || !allowed(view.context)) return false
        return try {
            if (advanced) {
                if (methods.getValue("setPassWindowBlurEnabled").invoke(view, true) != true) { clear(view, true); return false }
                methods.getValue("setMiViewBlurMode").invoke(view, 1)
                methods.getValue("setMiBackgroundBlurMode").invoke(view, 1)
                methods.getValue("setMiBackgroundBlurRadius").invoke(view, 100)
                methods.getValue("clearMiBackgroundBlendColor").invoke(view)
                methods.getValue("addMiBackgroundBlendColor").invoke(view, color, 101)
            } else {
                val root = methods.getValue("getViewRootImpl").invoke(view) ?: return false
                val drawable = methods.getValue("createBackgroundBlurDrawable").invoke(root) as? Drawable ?: return false
                view.background = drawable
                methods.getValue("setBlurRadius").invoke(drawable, 100)
                methods.getValue("setCornerRadius").invoke(drawable, radius)
                methods.getValue("setColor").invoke(drawable, color)
            }
            true
        } catch (_: Throwable) { clear(view, advanced); false }
    }
    fun clear(view: View, advanced: Boolean) {
        if (advanced) {
            for (name in listOf("setMiBackgroundBlurRadius", "setMiBackgroundBlurMode", "setMiViewBlurMode"))
                runCatching { methods.getValue(name).invoke(view, 0) }
            runCatching { methods.getValue("clearMiBackgroundBlendColor").invoke(view) }
            runCatching { methods.getValue("setPassWindowBlurEnabled").invoke(view, false) }
        } else view.background?.let {
            if (methods.getValue("setBlurRadius").declaringClass.isInstance(it))
                runCatching { methods.getValue("setBlurRadius").invoke(it, 0) }
            it.setVisible(false, false)
        }
        view.background = null
    }
}
