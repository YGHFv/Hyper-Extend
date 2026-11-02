/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * 验签恢复路径参考 HyperCeiler AuthCreakPatch（AGPL-3.0）。
 */
package io.github.YGHFv.HyperExtend.hook.feature.system

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** V1 验签与返回对象的精确结构；结构不符时不安装恢复 Hook。 */
internal class CorePatchPreSignatureTargets private constructor(
    val verify: Method,
    val callers: List<Method>,
    val detailsClass: Class<*>,
    val copyDetails: Constructor<*>,
    private val getSignatures: Method?,
    private val signaturesField: Field?,
    val parse: Parse?,
    val exceptionClass: Class<*>,
    val exceptionCode: Field,
) {
    class Parse(
        val inputClass: Class<*>,
        val resultClass: Class<*>,
        val isError: Method,
        val errorCode: Method,
        val errorMessage: Method,
        val exception: Method,
        val reset: Method,
        val success: Method,
        val error: Method,
        val wrap: Constructor<*>,
    )

    fun hasSignatures(details: Any): Boolean {
        if (!detailsClass.isInstance(details)) return false
        val signatures = getSignatures?.invoke(details) ?: signaturesField?.get(details)
        return signatures is Array<*> && signatures.isNotEmpty() && signatures.none { it == null }
    }

    companion object {
        const val VERIFIER = "android.util.apk.ApkSignatureVerifier"
        const val DETAILS = "android.content.pm.SigningDetails"
        const val OLD_DETAILS = "android.content.pm.PackageParser\$SigningDetails"
        const val INPUT = "android.content.pm.parsing.result.ParseInput"
        const val RESULT = "android.content.pm.parsing.result.ParseResult"
        const val INPUT_IMPL = "android.content.pm.parsing.result.ParseTypeImpl"
        const val WRAPPER = "android.util.apk.ApkSignatureVerifier\$SigningDetailsWithDigests"
        const val EXCEPTION = "android.content.pm.PackageParser\$PackageParserException"

        fun resolve(loader: ClassLoader): CorePatchPreSignatureTargets {
            fun load(name: String) = Class.forName(name, false, loader)
            val details = runCatching { load(DETAILS) }.getOrElse { load(OLD_DETAILS) }
            val input = runCatching { load(INPUT) }.getOrNull()
            return resolve(load(VERIFIER), details, load("android.content.pm.Signature"),
                load(EXCEPTION), input, input?.let { load(RESULT) },
                input?.let { load(INPUT_IMPL) }, input?.let { load(WRAPPER) })
        }

        internal fun resolve(
            verifier: Class<*>, details: Class<*>, signature: Class<*>, exception: Class<*>,
            input: Class<*>? = null, result: Class<*>? = null,
            inputImpl: Class<*>? = null, wrapper: Class<*>? = null,
        ): CorePatchPreSignatureTargets {
            val boolean = Boolean::class.javaPrimitiveType!!
            val integer = Int::class.javaPrimitiveType!!
            val signatureArray = java.lang.reflect.Array.newInstance(signature, 0).javaClass
            fun method(owner: Class<*>, name: String, returns: Class<*>, static: Boolean,
                vararg args: Class<*>): Method = owner.getDeclaredMethod(name, *args).apply {
                require(returnType == returns && Modifier.isStatic(modifiers) == static)
                isAccessible = true
            }
            val getter = runCatching {
                method(details, "getSignatures", signatureArray, false)
            }.getOrNull()
            val field = if (getter == null) details.getDeclaredField("signatures").apply {
                require(type == signatureArray && !Modifier.isStatic(modifiers))
                isAccessible = true
            } else null
            val copy = details.getDeclaredConstructor(details).apply { isAccessible = true }
            val code = exception.getDeclaredField("error").apply {
                require(type == integer && Throwable::class.java.isAssignableFrom(exception))
                isAccessible = true
            }
            val verify: Method
            val callers: List<Method>
            val parse: Parse?
            if (input != null) {
                requireNotNull(result)
                requireNotNull(inputImpl)
                requireNotNull(wrapper)
                require(input.isAssignableFrom(inputImpl))
                verify = method(verifier, "verifyV1Signature", result, true, input, String::class.java, boolean)
                callers = listOf(
                    method(verifier, "verifyV3AndBelowSignatures", result, true,
                        input, String::class.java, integer, boolean),
                    method(verifier, "verifySignaturesInternal", result, true,
                        input, String::class.java, integer, boolean),
                    method(verifier, "verifySignatures", result, true,
                        input, String::class.java, integer, boolean),
                )
                parse = Parse(inputImpl, result,
                    method(result, "isError", boolean, false),
                    method(result, "getErrorCode", integer, false),
                    method(result, "getErrorMessage", String::class.java, false),
                    method(result, "getException", Exception::class.java, false),
                    method(inputImpl, "reset", input, false),
                    method(input, "success", result, false, Any::class.java),
                    method(input, "error", result, false, integer, String::class.java, Exception::class.java),
                    wrapper.getDeclaredConstructor(details, Map::class.java).apply { isAccessible = true })
            } else {
                verify = method(verifier, "verifyV1Signature", details, true, String::class.java, boolean)
                callers = listOf(
                    method(verifier, "verify", details, true, String::class.java, integer),
                    method(verifier, "unsafeGetCertsWithoutVerification", details, true,
                        String::class.java, integer),
                )
                parse = null
            }
            return CorePatchPreSignatureTargets(verify, callers, details, copy, getter, field, parse, exception, code)
        }
    }
}
