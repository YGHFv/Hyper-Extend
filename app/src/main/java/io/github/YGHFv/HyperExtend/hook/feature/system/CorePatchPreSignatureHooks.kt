/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * 已安装证书查询与 V1 验签恢复参考 HyperCeiler AuthCreakPatch（AGPL-3.0）。
 */
package io.github.YGHFv.HyperExtend.hook.feature.system

import android.content.Context
import android.content.pm.PackageManager
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime

internal object CorePatchPreSignatureHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val targets = runCatching { CorePatchPreSignatureTargets.resolve(loader) }.getOrElse {
            ModuleLog.warn("沿用已安装签名：V1 验签结构不匹配，保留原生行为 (${it.javaClass.simpleName})")
            return 0
        }
        val reader = runCatching { InstalledReader(loader, targets) }.getOrElse {
            ModuleLog.warn("沿用已安装签名：旧签名读取结构不匹配，保留原生行为 (${it.javaClass.simpleName})")
            return 0
        }
        if (!targets.callers.map {
                HookRuntime.deoptimize(it, "core_patch_use_pre_signature/${it.declaringClass.name}#${it.name}")
            }.all { it }) return 0
        val recovery = CorePatchPreSignatureRecovery(targets)
        return if (HookRuntime.hook(targets.verify,
                "core_patch_use_pre_signature/${CorePatchPreSignatureTargets.VERIFIER}#verifyV1Signature",
            ) { chain ->
                recovery.invoke(chain.args.toTypedArray(), enabled = {
                    CorePatchPreSignatureRecovery.enabled(
                        master = settings.isOn("system_framework_core_patch"),
                        preSignature = settings.isOn("system_framework_core_patch_use_pre_signature"),
                        override = settings.isOn("system_framework_core_patch_override_signature"),
                        signature = settings.isOn("system_framework_core_patch_signature"),
                        safeMode = SafeModeRuntime.blocked,
                        stack = Thread.currentThread().stackTrace,
                    )
                }, original = { chain.proceed() }, installed = { reader.read(it) }, report = {
                    ModuleLog.warn("沿用已安装签名：恢复失败，保留原生结果 (${it.javaClass.simpleName})")
                })
            }) 1 else 0
    }

    private class InstalledReader(loader: ClassLoader, targets: CorePatchPreSignatureTargets) {
        private val threadClass = Class.forName("android.app.ActivityThread", false, loader)
        private val currentApplication = threadClass.getDeclaredMethod("currentApplication")
        private val currentThread = threadClass.getDeclaredMethod("currentActivityThread")
        private val systemContext = threadClass.getDeclaredMethod("getSystemContext")
        private val infoClass = Class.forName("android.content.pm.SigningInfo", false, loader)
        private val getDetails = runCatching {
            infoClass.getDeclaredMethod("getSigningDetails").apply {
                require(returnType == targets.detailsClass)
                isAccessible = true
            }
        }.getOrNull()
        private val detailsField = if (getDetails == null) {
            infoClass.getDeclaredField("mSigningDetails").apply {
                require(type == targets.detailsClass)
                isAccessible = true
            }
        } else null

        fun read(path: String): CorePatchPreSignatureRecovery.Installed? {
            // system_server 的 currentApplication 可能为空，使用其系统 Context 作只读查询。
            val context = currentApplication.invoke(null) as? Context
                ?: currentThread.invoke(null)?.let { systemContext.invoke(it) as? Context }
                ?: return null
            val pm = context.packageManager ?: return null
            // 不请求证书解析，避免查询待安装 APK 时再次触发验签恢复。
            val archive = pm.getPackageArchiveInfo(path, 0) ?: return null
            val packageName = archive.packageName
            if (packageName.isNullOrBlank()) return null
            val installed = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            if (installed.packageName != packageName) return null
            val info = installed.signingInfo ?: return null
            val details = getDetails?.invoke(info) ?: detailsField?.get(info) ?: return null
            return CorePatchPreSignatureRecovery.Installed(installed.packageName, details, packageName)
        }
    }
}
