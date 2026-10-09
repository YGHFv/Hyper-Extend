/* Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import io.github.YGHFv.HyperExtend.core.MediaCardSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.util.WeakHashMap
import java.lang.reflect.Method

/** Scale the native drawable, not a rasterized copy that loses animated play/pause state. */
internal object MediaButtonSizing {
    private class State(val scaleType: ImageView.ScaleType, val matrix: Matrix, var size: Int?)
    private val states = WeakHashMap<ImageButton, State>()
    private var tinyScreen: Method? = null
    private val labels = WeakHashMap<Context, Pair<Configuration, Set<String>>>()
    private val layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        Reflect.attempt {
            (view as? ImageButton)?.let { button -> states[button]?.size?.let { transform(button, it) } }
        }
    }

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val main = MediaCardSettings.value(settings.string(MediaCardSettings.BUTTON_SIZE), MediaCardSettings.buttonSize)
        val custom = MediaCardSettings.value(settings.string(MediaCardSettings.CUSTOM_BUTTON_SIZE), MediaCardSettings.customButtonSize)
        if (main == 140 && custom == 140) return 0
        val controller = Reflect.loadClass(loader, SystemUiTargets.MEDIA_CONTROLLER) ?: return 0
        val utils = Reflect.loadClass(loader,
            "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaActionButtonUtils") ?: return 0
        val action = Reflect.loadClass(loader, "com.android.systemui.media.controls.shared.model.MediaAction") ?: return 0
        val common = controller.getDeclaredMethod("bindButtonCommon", ImageButton::class.java, action)
        val semantic = utils.declaredMethods.firstOrNull { it.name == "bindButtonsCommon" && it.parameterCount == 3
            && it.parameterTypes[0] == ImageButton::class.java && it.parameterTypes[1] == action } ?: return 0
        val tiny = NotificationHooks.resolve(loader, SystemUiTargets.flipTinyScreen) ?: return 0
        tinyScreen = tiny
        val island = Reflect.findField(utils, "island") ?: return 0
        val controls = listOf("play", "prev", "next").map { requireNotNull(Reflect.findField(utils, it)) }
        var count = listOf(common, semantic).count { method ->
            HookRuntime.hookAfter(method, "media_card_layout/buttonSize/${method.declaringClass.simpleName}") { chain, original ->
                val owner = chain.thisObject!!
                val button = chain.args[0] as ImageButton
                val isSemantic = method == semantic
                val context = Reflect.readField(owner, "context") as? Context ?: button.context
                val normal = !(isSemantic && island.getBoolean(owner)) && tiny.invoke(null, context) == false
                val isMain = if (isSemantic) controls.any { it.get(owner) === button }
                    else button.contentDescription?.toString() in mainLabels(context)
                resize(button, if (normal && chain.args[1] != null) MediaButtonSizePolicy.size(main, custom, isMain) else null)
                original
            }
        }
        val setSemantic = utils.getDeclaredMethod("setSemanticButton", ImageButton::class.java, action)
        if (HookRuntime.hookAfter(setSemantic, "media_card_layout/clearSemanticSize") { chain, original ->
                if (chain.args[1] == null) resize(chain.args[0] as ImageButton, null)
                original
            }) count++
        // The semantic binder is also called from a deferred animation callback.
        for (type in listOf(controller, utils)) for (method in type.declaredMethods) {
            HookRuntime.deoptimize(method, "media_card_layout/buttonCaller")
        }
        for (name in listOf(utils.name + "\$\$ExternalSyntheticLambda0", utils.name + "\$updateButtonDelay\$1")) {
            Reflect.loadClass(loader, name)?.declaredMethods?.forEach {
                HookRuntime.deoptimize(it, "media_card_layout/deferredButtonCaller")
            }
        }
        return count
    }

    private fun mainLabels(context: Context): Set<String> {
        val configuration = context.resources.configuration
        labels[context]?.takeIf { it.first == configuration }?.let { return it.second }
        val text = listOf("play", "pause", "prev", "next").mapNotNull { suffix ->
            val id = context.resources.getIdentifier("controls_media_button_$suffix", "string", "com.android.systemui")
            if (id == 0) null else context.getString(id)
        }.toSet()
        labels[context] = Configuration(configuration) to text
        return text
    }

    private fun resize(button: ImageButton, size: Int?) {
        if (size == null) {
            states.remove(button)?.let { state ->
                button.scaleType = state.scaleType
                button.imageMatrix = state.matrix
                button.removeOnLayoutChangeListener(layoutListener)
            }
            return
        }
        states.getOrPut(button) {
            button.addOnLayoutChangeListener(layoutListener)
            State(button.scaleType, Matrix(button.imageMatrix), size)
        }.size = size
        transform(button, size)
    }

    private fun transform(button: ImageButton, size: Int) {
        if (tinyScreen?.invoke(null, button.context) != false) {
            states[button]?.let { button.scaleType = it.scaleType; button.imageMatrix = it.matrix }
            return
        }
        val drawable = button.drawable ?: return
        val width = button.width - button.paddingLeft - button.paddingRight
        val height = button.height - button.paddingTop - button.paddingBottom
        val dw = drawable.intrinsicWidth
        val dh = drawable.intrinsicHeight
        val scale = MediaButtonSizePolicy.scale(size, width, height, dw, dh) ?: return
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - dw * scale) / 2, (height - dh * scale) / 2)
        }
        button.scaleType = ImageView.ScaleType.MATRIX
        button.imageMatrix = matrix
    }
}
