/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * V1 错误恢复参考 HyperCeiler AuthCreakPatch（AGPL-3.0），不沿用硬编码证书兜底。
 */
package io.github.YGHFv.HyperExtend.hook.feature.system

/** 原生调用只执行一次；只有读取到同包名的真实已安装签名才恢复 -103。 */
internal class CorePatchPreSignatureRecovery(private val targets: CorePatchPreSignatureTargets) {
    data class Installed(
        val packageName: String?,
        val signingDetails: Any?,
        val archivePackageName: String? = packageName,
    )

    private val reading = ThreadLocal<Boolean>()

    fun invoke(
        args: Array<out Any?>,
        enabled: () -> Boolean,
        original: () -> Any?,
        installed: (String) -> Installed?,
        report: (Throwable) -> Unit = {},
    ): Any? {
        val outcome = runCatching(original)
        if (reading.get() == true) return outcome.getOrThrow()
        val replacement = runCatching {
            if (!enabled()) return@runCatching null
            val parse = targets.parse
            val result = outcome.getOrNull()
            val failure = outcome.exceptionOrNull()
            if (parse != null && failure == null) {
                if (!parse.resultClass.isInstance(result) || parse.isError.invoke(result) != true ||
                    parse.errorCode.invoke(result) != NO_CERTIFICATES) return@runCatching null
            } else {
                val exception = sequenceOf(failure, failure?.cause)
                    .filterNotNull().firstOrNull { targets.exceptionClass.isInstance(it) }
                    ?: return@runCatching null
                if (targets.exceptionCode.getInt(exception) != NO_CERTIFICATES) return@runCatching null
            }
            val path = args.getOrNull(if (parse == null) 0 else 1) as? String
            if (path.isNullOrBlank() || path.endsWith(".apex", ignoreCase = true)) return@runCatching null
            val input = if (parse == null) null else args.getOrNull(0)
            if (parse != null && !parse.inputClass.isInstance(input)) return@runCatching null
            reading.set(true)
            try {
                val old = installed(path) ?: return@runCatching null
                if (!CorePatchSignaturePolicy.samePackageName(old.archivePackageName, old.packageName))
                    return@runCatching null
                val details = old.signingDetails ?: return@runCatching null
                if (!targets.hasSignatures(details)) return@runCatching null
                // 复制完整对象，不把轮换历史当作当前多签名数组，也不改写已安装包的记录。
                val copy = targets.copyDetails.newInstance(details)
                if (!targets.hasSignatures(copy)) return@runCatching null
                if (parse == null) copy else {
                    val wrapped = parse.wrap.newInstance(copy, null)
                    val code = if (failure == null) parse.errorCode.invoke(result) as Int else NO_CERTIFICATES
                    val message = if (failure == null) parse.errorMessage.invoke(result) else failure.message
                    val exception = if (failure == null) parse.exception.invoke(result) else failure as? Exception
                    try {
                        // ParseTypeImpl 同时充当输入与结果；先准备完签名，再清除原来的错误状态。
                        parse.reset.invoke(input)
                        val success = parse.success.invoke(input, wrapped)
                        check(parse.resultClass.isInstance(success) && parse.isError.invoke(success) == false)
                        success
                    } catch (error: Throwable) {
                        runCatching { parse.error.invoke(input, code, message, exception) }
                        throw error
                    }
                }
            } finally {
                reading.remove()
            }
        }.onFailure { runCatching { report(it) } }.getOrNull()
        return replacement ?: outcome.getOrThrow()
    }

    companion object {
        const val NO_CERTIFICATES = -103
        private val INSTALL_ENTRY_METHODS = mapOf(
            "com.android.server.pm.PackageInstallerSession" to "validateApkInstallLocked",
            "com.android.server.pm.InstallPackageHelper" to "preparePackage",
        )

        fun enabled(
            master: Boolean, preSignature: Boolean, override: Boolean, signature: Boolean,
            safeMode: Boolean, stack: Array<StackTraceElement>,
        ): Boolean = master && preSignature && override && signature && !safeMode && stack.any {
            val method = INSTALL_ENTRY_METHODS[it.className]
            method != null && (it.methodName == method || it.methodName.startsWith("$method\$"))
        }
    }
}
