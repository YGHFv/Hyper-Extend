/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 */

package io.github.YGHFv.HyperExtend.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 系统默认的**通行密钥（凭据）提供方**。
 *
 * ## 它到底是什么
 *
 * Android 把「谁来提供通行密钥」记在 `Settings.Secure.credential_service` 里，
 * 值是一个 `包名/服务类名` 的组件名。系统设置里的「默认凭据提供方」改的就是它。
 *
 * ## 为什么这一层要绕道 root
 *
 * 写 `Settings.Secure` 需要 `WRITE_SECURE_SETTINGS`（signature|privileged 权限），
 * 普通应用即使用户手动授权也拿不到。本模块是普通应用，唯一能改它的途径是 su：
 * `settings put secure credential_service <组件>`。读同理 —— 界面要把「现在用的是哪个」
 * 如实显示出来，就得读这个键。
 *
 * ## 为什么候选是运行时枚举的
 *
 * 「哪几个应用能提供通行密钥」完全取决于用户装了什么（Google Play 服务、各家密码管理器）。
 * 写死在代码里必然过时，而系统给的权威口径只有一个：谁声明了
 * `android.service.credentials.CredentialProviderService`。
 */
object PasskeyProvider {

    /** 凭据提供方声明的 service action。 */
    private const val ACTION_CREDENTIAL_PROVIDER =
        "android.service.credentials.CredentialProviderService"

    /** `Settings.Secure` 里存默认提供方的键。 */
    private const val SECURE_KEY = "credential_service"

    /** 一个候选提供方。[component] 是写进系统设置的值，形如 `包名/类名`。 */
    data class Provider(val component: String, val label: String)

    /**
     * 设备上实现了凭据提供方服务的应用。
     *
     * 用 `queryIntentServices` 而不是 `queryIntentServicesAndUsers`：这是纯展示用途，
     * 不需要区分用户。**包含被禁用的组件** —— 被禁用的提供方同样会出现在系统设置里，
     * 漏掉它会让「我在系统设置里看到它、这里却找不到」变成一件说不通的事。
     */
    fun installed(context: Context): List<Provider> = runCatching {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        pm.queryIntentServices(Intent(ACTION_CREDENTIAL_PROVIDER), flags)
            .mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                val component = "${service.packageName}/${service.name}"
                val label = runCatching { service.loadLabel(pm).toString() }
                    .getOrDefault(service.packageName)
                Provider(component, label)
            }
            // 同一个应用可能声明多个服务，按组件去重；再按名字排序，界面上顺序才稳定。
            .distinctBy { it.component }
            .sortedBy { it.label }
    }.getOrDefault(emptyList())

    /**
     * 当前默认提供方；没设置、或读不到（无 root）时返回空串。
     *
     * `settings get` 在键未设置时会打印字面量 `null` —— 那要翻译成空串，
     * 否则界面会把它当成一个叫「null」的应用。
     */
    fun current(): String {
        val raw = RootAccess.exec("settings get secure $SECURE_KEY")?.trim().orEmpty()
        return if (raw.isEmpty() || raw == "null") "" else raw
    }

    /**
     * 写入默认提供方。[component] 为空串表示「清空，交回系统决定」（写 `null`）。
     *
     * @return 是否成功（无 root、或 su 被拒时为 false）
     */
    fun apply(component: String): Boolean {
        val value = component.trim().ifEmpty { "null" }
        val output = RootAccess.exec("settings put secure $SECURE_KEY '$value'") ?: return false
        // `settings put` 成功时不打印任何东西；有输出基本就是它在报错（会同时以非 0 退出，
        // 已在 exec 里被判掉）。这里只记一条日志便于排查。
        ModuleLog.info("passkey default provider -> '${component.ifEmpty { "(system)" }}'${output.trim().let { if (it.isEmpty()) "" else " | $it" }}")
        return true
    }
}
