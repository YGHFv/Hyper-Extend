package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal class SliderLabelState {
    data class NativeText(val text: String, val visibility: Int)
    private data class Applied(val original: NativeText, val value: String)
    private var applied: Applied? = null
    val ownsText get() = applied != null

    fun capture(text: String, visibility: Int, value: String) {
        check(applied == null)
        applied = Applied(NativeText(text, visibility), value)
    }

    fun release(text: String, visible: Boolean, richText: Boolean): NativeText? {
        val previous = applied ?: return null
        applied = null
        return previous.original.takeIf { text == previous.value && visible && !richText }
    }

    fun shouldRelease(enabled: Boolean, blocked: Boolean, attached: Boolean, shown: Boolean) =
        ownsText && (!enabled || blocked || !attached || !shown)
}
