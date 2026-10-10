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

package io.github.YGHFv.HyperExtend.config

import android.content.Context
import android.content.SharedPreferences
import io.github.YGHFv.HyperExtend.core.FEATURES
import io.github.YGHFv.HyperExtend.core.FRAMEWORK_FUSE_RESET_KEY
import io.github.YGHFv.HyperExtend.core.FrameworkBridge
import io.github.YGHFv.HyperExtend.core.CONFIG_KEYS
import io.github.YGHFv.HyperExtend.core.MONET_SCHEME_KEY
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.NFC_IMAGE_KEY
import io.github.YGHFv.HyperExtend.core.NOTIFY_ICON_AUTO_TIME_KEY
import io.github.YGHFv.HyperExtend.core.NOTIFY_ICON_SOURCE_KEY
import io.github.YGHFv.HyperExtend.core.defaultEnabledOf
import io.github.YGHFv.HyperExtend.core.SCOPES
import io.github.YGHFv.HyperExtend.core.SafeModeKeys
import org.json.JSONObject

/**
 * 模块 App 进程侧的设置读写。
 *
 * ## 两处存储，各有分工
 *
 * - **本地 SharedPreferences**：模块界面直接读写，永远可用。界面上的值以它为准。
 * - **RemotePreferences**（框架提供）：被注入的进程（SystemUI、设置、system_server）读的是这一份。
 *   它由框架托管，模块 App 通过 `XposedService.getRemotePreferences(group)` 写入。
 *
 * 为什么不用一份：被注入的进程与模块 App 是两个 uid，**读不到对方的私有目录**。
 * 框架的 RemotePreferences 是唯一能让两边共享数据的通道，但它依赖 `PROP_CAP_REMOTE` 能力，
 * 且需要框架服务在线 —— 都不能假定。所以本地那份是权威副本，RemotePreferences 是投影：
 * 每次写设置时尽力同步过去，同步失败只记日志（界面照常工作，只是被注入侧读到的还是旧值）。
 *
 * ## 为什么读设置也要走本地
 *
 * 界面显示的是「用户设了什么」，本地 prefs 是唯一不会因为框架离线而失真的来源。
 * 如果读 RemotePreferences，框架一断界面就显示成默认值，用户会以为设置丢了。
 */
object HyperSettings {

    /** RemotePreferences 的组名。被注入进程按这个名字取同一份。 */
    const val GROUP = "hyper_extend_settings"

    /** 本地 prefs 文件名。与组名相同只是巧合地便于对照，两者互不影响。 */
    private const val LOCAL_NAME = "hyper_extend_settings"

    /** 一次性版本标记：以后改默认值时可以据此做迁移，不必让用户手动恢复默认。 */
    const val KEY_SCHEMA = "schema_version"

    const val SCHEMA_VERSION = 1
    /**
     * 字符串型设置。
     *
     * 两类东西在这里：**用户提供的内容**（NFC 卡面图片）与**功能目录里的配置行**
     * （多选一 / 数值，见 `core/FeatureCatalog` 的 [HyperConfigRow]）。
     * 登记而不是随手 `putString`，理由与布尔开关相同：读全量时只认登记过的键，
     * 否则老版本残留的键会变成一个界面看不见、hook 却在读的「幽灵配置」。
     *
     * 配置行的键从 [FEATURES] 派生（[CONFIG_KEYS]），不在这里再抄一份：
     * 抄的那份加一个配置行就会漏一处，表现是「改完设置、重启宿主还是老样子」，
     * 而日志里看不出任何异常。
     */
    val STRING_KEYS: List<String> = listOf(
        NFC_IMAGE_KEY,
        MONET_SCHEME_KEY,
        NOTIFY_ICON_SOURCE_KEY,
        NOTIFY_ICON_AUTO_TIME_KEY,
    ) + CONFIG_KEYS

    /** 模块 App 进程的本地 prefs。 */
    fun localPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LOCAL_NAME, Context.MODE_PRIVATE)

    /**
     * 读全量开关。
     *
     * 只挑**目录里登记过的键**：用户从旧版本升级后，prefs 里可能留着已经删掉的功能键，
     * 原样读出来会让界面上出现一个「幽灵开关」，而 hook 侧根本不认识它。
     */
    fun read(context: Context): Map<String, Boolean> = readFrom(localPrefs(context))

    fun readFrom(prefs: SharedPreferences): Map<String, Boolean> {
        val all = prefs.all
        val result = LinkedHashMap<String, Boolean>(all.size)
        for (feature in FEATURES) {
            putVerified(result, all, feature.id, feature.defaultEnabled)
            for (option in feature.options) {
                putVerified(result, all, option.id, option.defaultEnabled)
            }
        }
        return result
    }

    private fun putVerified(
        target: MutableMap<String, Boolean>,
        all: Map<String, *>,
        key: String,
        fallback: Boolean,
    ) {
        val raw = all[key]
        target[key] = raw as? Boolean ?: fallback
    }

    /** 单个开关当前值（界面用；hook 侧走 `HookSettings`）。 */
    fun isOn(context: Context, id: String): Boolean =
        localPrefs(context).getBoolean(id, defaultEnabledOf(id))

    /** 读全部字符串设置。同样只认登记过的键（理由见 [STRING_KEYS]）。 */
    fun readStrings(context: Context): Map<String, String> = readStringsFrom(localPrefs(context))

    fun readStringsFrom(prefs: SharedPreferences): Map<String, String> {
        val result = LinkedHashMap<String, String>(STRING_KEYS.size)
        for (key in STRING_KEYS) {
            result[key] = prefs.getString(key, "") ?: ""
        }
        return result
    }

    /** 写一个字符串设置。空串是合法值，含义是「清除这一项」。 */
    fun setString(context: Context, key: String, value: String): Boolean {
        if (key !in STRING_KEYS) {
            ModuleLog.error("refuse to write unknown string key: $key")
            return false
        }
        return runCatching {
            localPrefs(context).edit().putString(key, value).apply()
            syncToFramework(context)
            true
        }.getOrElse {
            ModuleLog.error("write string $key failed", it)
            false
        }
    }

    /**
     * 写一个开关：先落本地，再尽力同步给框架。
     *
     * @return true 表示本地已写入（同步失败不影响返回值，只记日志）
     */
    fun set(context: Context, id: String, enabled: Boolean): Boolean {
        // 未登记 id 直接拒绝：写进去只会污染 prefs，而且下次读全量时还会被过滤掉，
        // 表现为「点了开关、重启回来又是关的」，极难排查。
        if (FEATURES.none { it.id == id } &&
            FEATURES.none { feature -> feature.options.any { it.id == id } }
        ) {
            ModuleLog.error("refuse to write unknown switch id: $id")
            return false
        }
        return runCatching {
            val local = localPrefs(context)
            local.edit().putBoolean(id, enabled).apply()
            syncToFramework(context)
            true
        }.getOrElse {
            ModuleLog.error("write switch $id failed", it)
            false
        }
    }

    /** 全部恢复默认：只清登记过的键，别的一概不碰。 */
    fun resetToDefaults(context: Context): Boolean = runCatching {
        val local = localPrefs(context)
        val editor = local.edit()
        for (feature in FEATURES) {
            editor.remove(feature.id)
            feature.options.forEach { editor.remove(it.id) }
        }
        STRING_KEYS.forEach { editor.remove(it) }
        editor.apply()
        syncToFramework(context)
        true
    }.getOrElse {
        ModuleLog.error("reset switches failed", it)
        false
    }

    /**
     * 把本地全量设置投影到框架的 RemotePreferences。
     *
     * 句柄从 [FrameworkBridge] 取（它是框架服务的唯一持有者）。失败只记日志 ——
     * 界面上的设置已经存好了，只是被注入侧暂时读不到新值；而**下一次写出/绑定时会再投一次**，
     * 所以这不是一个需要在这里重试的错误。
     *
     * 两次 `read` 合并成一次快照：这不是微优化。「启用全部功能」会连着写出十几个开关，
     * 也就是连着投十几次，而每次 `read` 都要把 SharedPreferences 全表读出来 ——
     * 做成两次等于把这点开销翻倍，而这个函数跑的正是用户点完开关、盯着屏幕等反馈的那一刻。
     */
    @Synchronized
    fun syncToFramework(context: Context) {
        val prefs = FrameworkBridge.remotePreferences(GROUP)
        if (prefs == null) {
            ModuleLog.warn("framework service unavailable — switches saved locally only")
            return
        }
        runCatching {
            val switches = read(context)
            val strings = readStrings(context)
            val editor = prefs.edit()
            switches.forEach { (key, value) -> editor.putBoolean(key, value) }
            strings.forEach { (key, value) -> editor.putString(key, value) }
            editor.putLong(
                FRAMEWORK_FUSE_RESET_KEY,
                localPrefs(context).getLong(FRAMEWORK_FUSE_RESET_KEY, 0L),
            )
            editor.putInt(KEY_SCHEMA, SCHEMA_VERSION)
            val safety = localPrefs(context)
            SCOPES.forEach { scope ->
                editor.putBoolean(SafeModeKeys.disabled(scope.id), safety.getBoolean(SafeModeKeys.disabled(scope.id), false))
                editor.putLong(SafeModeKeys.reset(scope.id), safety.getLong(SafeModeKeys.reset(scope.id), 0))
            }
            editor.apply()
            ModuleLog.info(
                "settings projected to framework (${switches.size} switches, ${strings.size} strings)",
            )
        }.onFailure {
            ModuleLog.error("project switches to framework failed (local copy is intact)", it)
        }
    }

    /** 开关数量摘要；框架状态由界面单独展示。 */
    fun describe(context: Context): String {
        val values = read(context)
        val on = values.count { it.value }
        return "已启用 $on / ${values.size} 项"
    }

    // ------------------------------------------------------------------ 备份 / 恢复

    /**
     * 把当前设置导出成一段 JSON。
     *
     * ## 为什么是「导出成文本」而不是「备份到自己的目录」
     *
     * 备份文件放在模块自己的私有目录里，对用户来说等于不存在：既看不见、也拿不走，
     * 换机或重装时一点用都没有。真正需要它的时刻（换设备、给同好一份自己的配置）
     * 都得**把文件拿到手**，所以这里只负责产出文本，落盘位置由界面用系统文件选择器问用户要。
     *
     * ## 只导出登记过的键
     *
     * 与 [read] / [readStrings] 同一条规矩：老版本残留的键导出去，
     * 在别的设备上恢复时会变成一个「界面看不见、hook 却在读」的幽灵配置。
     * 空字符串不导出 —— 字符串设置的语义本来就是「空 = 未配置」。
     */
    fun exportJson(context: Context): String {
        val root = JSONObject()
        root.put("schema", SCHEMA_VERSION)
        val switches = JSONObject()
        read(context).forEach { (key, value) -> switches.put(key, value) }
        root.put("switches", switches)
        val strings = JSONObject()
        readStrings(context).forEach { (key, value) -> if (value.isNotEmpty()) strings.put(key, value) }
        root.put("strings", strings)
        return root.toString(2)
    }

    /**
     * 从导出的 JSON 恢复设置。
     *
     * **不是覆盖式**：只写 JSON 里真的出现过的键，其余保持原样。理由是这样一来
     * 「一个只配了 NFC 卡面的备份」不会把用户本机的取色风格清零 ——
     * 恢复设置是个破坏性动作，能少动一点就少动一点。
     *
     * @return 实际写入的键数
     * @throws org.json.JSONException 文本不是合法 JSON 时（由调用方转成界面提示）
     */
    fun importJson(context: Context, json: String): Int {
        val root = JSONObject(json)
        var applied = 0
        val editor = localPrefs(context).edit()

        root.optJSONObject("switches")?.let { switches ->
            for (feature in FEATURES) {
                if (applyBoolean(editor, switches, feature.id, feature.defaultEnabled)) applied++
                for (option in feature.options) {
                    if (applyBoolean(editor, switches, option.id, option.defaultEnabled)) applied++
                }
            }
        }
        root.optJSONObject("strings")?.let { strings ->
            for (key in STRING_KEYS) {
                if (strings.has(key)) {
                    editor.putString(key, strings.optString(key, ""))
                    applied++
                }
            }
        }

        editor.apply()
        syncToFramework(context)
        ModuleLog.info("settings restored from backup: $applied key(s)")
        return applied
    }

    private fun applyBoolean(
        editor: SharedPreferences.Editor,
        source: JSONObject,
        key: String,
        fallback: Boolean,
    ): Boolean {
        if (!source.has(key)) return false
        editor.putBoolean(key, source.optBoolean(key, fallback))
        return true
    }
}
