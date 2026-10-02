package io.github.YGHFv.HyperExtend.hook.feature

import java.lang.reflect.Constructor
import java.lang.reflect.Method

internal class MonetPaletteCompat private constructor(
    private val parameterTypes: Array<Class<*>>,
    private val colorSpecFactory: Method,
    private val paletteMethods: List<Method>,
    private val variant: Any,
) {
    fun rewrite(args: List<Any?>): Array<Any?>? {
        if (args.size != parameterTypes.size) return null
        val source = (args[0] as? List<*>)?.firstOrNull() ?: return null
        val dark = args[2] as? Boolean ?: return null
        val contrast = args[3] as? Double ?: return null
        val specVersion = args[5] ?: return null
        val colorSpec = colorSpecFactory.invoke(null, specVersion) ?: return null
        val replacement = args.toTypedArray()
        replacement[1] = variant
        for ((index, method) in paletteMethods.withIndex()) {
            val palette = method.invoke(colorSpec, variant, source, dark, contrast) ?: return null
            if (!parameterTypes[index + 6].isInstance(palette)) return null
            replacement[index + 6] = palette
        }
        return replacement
    }

    companion object {
        private const val LIBMONET = "com.google.ux.material.libmonet"
        private val PALETTE_METHODS = listOf(
            "getPrimaryPalette",
            "getSecondaryPalette",
            "getTertiaryPalette",
            "getNeutralPalette",
            "getNeutralVariantPalette",
            "getErrorPalette",
        )

        fun resolve(loader: ClassLoader, constructor: Constructor<*>, variant: Any): MonetPaletteCompat? {
            val parameters = constructor.parameterTypes
            val expectedTypes = listOf(
                "java.util.List",
                "$LIBMONET.dynamiccolor.Variant",
                "boolean",
                "double",
                "$LIBMONET.dynamiccolor.DynamicScheme\$Platform",
                "$LIBMONET.dynamiccolor.ColorSpec\$SpecVersion",
                "$LIBMONET.palettes.TonalPalette",
                "$LIBMONET.palettes.TonalPalette",
                "$LIBMONET.palettes.TonalPalette",
                "$LIBMONET.palettes.TonalPalette",
                "$LIBMONET.palettes.TonalPalette",
                "java.util.Optional",
            )
            if (parameters.map { it.name } != expectedTypes || !parameters[1].isInstance(variant)) return null
            return runCatching {
                val specs = Class.forName("$LIBMONET.dynamiccolor.ColorSpecs", false, loader)
                val specClass = Class.forName("$LIBMONET.dynamiccolor.ColorSpec2021", false, loader)
                val hct = Class.forName("$LIBMONET.hct.Hct", false, loader)
                val factory = specs.getDeclaredMethod("get", parameters[5]).also { it.isAccessible = true }
                val methods = PALETTE_METHODS.map { name ->
                    specClass.getDeclaredMethod(
                        name,
                        parameters[1],
                        hct,
                        Boolean::class.javaPrimitiveType,
                        Double::class.javaPrimitiveType,
                    ).also { it.isAccessible = true }
                }
                MonetPaletteCompat(parameters, factory, methods, variant)
            }.getOrNull()
        }
    }
}
