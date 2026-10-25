/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.tsmclient

import android.content.Context
import android.nfc.NfcAdapter
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.TsmClientSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect

/**
 * 进入智能卡刷卡页时临时开启 NFC，退出时仅恢复本次开启的状态。
 *
 * 只挂 `DoubleClickActivity` 的两个生命周期方法，不接管钱包的 CTA、授权或卡片校验。
 * `NfcAdapter#enable/disable/isEnabled` 虽在 Android 37 SDK 中可见，运行时仍按隐藏 API
 * 反射调用并完整容错，避免厂商 ROM 的访问限制影响宿主。
 */
internal object TsmAutoNfc {
    private const val LABEL = TsmClientSettings.AUTO_NFC
    private val policy = TsmAutoNfcPolicy()

    fun install(loader: ClassLoader): Int {
        val activity = Reflect.loadClass(loader, TsmAutoNfcTargets.ACTIVITY) ?: run {
            ModuleLog.warn("$LABEL: target class missing ${TsmAutoNfcTargets.ACTIVITY}")
            return 0
        }
        var installed = 0
        for (target in TsmAutoNfcTargets.all) {
            val method = TsmAutoNfcTargets.resolve(activity, target)
            if (method == null) {
                ModuleLog.warn("$LABEL: target missing ${activity.name}#${target.name}")
                continue
            }
            val label = "$LABEL/${activity.simpleName}#${target.name}"
            val hooked = when (target) {
                TsmAutoNfcTargets.onCreate -> HookRuntime.hookAfter(method, label) { chain, original ->
                    opened(chain.thisObject)
                    original
                }
                TsmAutoNfcTargets.onDestroy -> HookRuntime.hook(method, label) { chain ->
                    closed(chain.thisObject)
                    chain.proceed()
                }
                else -> false
            }
            if (hooked) installed++
        }
        return installed
    }

    private fun opened(owner: Any?) {
        val context = owner as? Context ?: return
        val adapter = runCatching {
            context.getSystemService(Context.NFC_SERVICE) as? NfcAdapter
        }.getOrNull() ?: return
        if (policy.onOpened(owner, AndroidNfcControl(adapter))) {
            ModuleLog.info("$LABEL: enabled NFC for ${owner.javaClass.simpleName}")
        }
    }

    private fun closed(owner: Any?) {
        if (owner != null && policy.onClosed(owner)) {
            ModuleLog.info("$LABEL: restored NFC for ${owner.javaClass.simpleName}")
        }
    }

    private class AndroidNfcControl(private val adapter: NfcAdapter) : TsmAutoNfcControl {
        override fun isEnabled(): Boolean = call("isEnabled")
        override fun enable(): Boolean = call("enable")
        override fun disable(): Boolean = call("disable")

        private fun call(name: String): Boolean = try {
            val method = NfcAdapter::class.java.getDeclaredMethod(name)
            method.isAccessible = true
            method.invoke(adapter) as? Boolean ?: false
        } catch (t: Throwable) {
            ModuleLog.warn("$LABEL: NfcAdapter#$name failed (${t.javaClass.simpleName})")
            false
        }
    }
}
