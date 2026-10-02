/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 *
 * ---------------------------------------------------------------------------
 * 通知图标库（ANIP）的唯一入口。
 *
 * 用的是上游 MIUINativeNotifyIcon 同一套 SDK（com.highcapable.anip:anip-sdk），
 * 图标资源来自 Android 通知图标规范适配计划的公开仓库。
 *
 * ## 每个进程一份实例、一份缓存
 *
 * ANIP 的缓存放在「传入 context 的私有目录」里。模块 App 与 SystemUI 是两个 uid，
 * 各自的目录互不可见 —— 所以**每个进程都要自己 fetch 一遍**，没有「模块同步完
 * SystemUI 直接读」的捷径。上游的对策是一模一样的：SystemUI 进程在启动时自己
 * reload + fetch，模块进程只是把「该刷新了」这个信号递过去（本模块用一条广播）。
 *
 * ## 快照发布
 *
 * `reload` / `fetch` 之外，本对象还持有一份**已发布的快照**（[snapshot]）：
 * 注入侧在通知渲染路径上查图标，绝不能边查边让 ANIP 现算 —— 快照把包名映射
 * 预先建好，查一次就是一次 HashMap 命中。发布采用「新快照就绪才整体替换」，
 * 失败时保留上一份，正在渲染的通知不受同步失败影响。
 */

package io.github.YGHFv.HyperExtend.core

import android.content.Context
import android.graphics.Bitmap
import com.highcapable.anip.sdk.Anip
import com.highcapable.anip.sdk.config.AnipConfig
import com.highcapable.anip.sdk.config.RemoteSource
import com.highcapable.anip.sdk.entity.NotificationIconSnapshot
import com.highcapable.anip.sdk.type.SystemVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「通知图标库」。模块进程与被注入的 SystemUI 进程各自实例化一份。 */
object NotifyIconLibrary {

    /**
     * 模块 App → SystemUI 的「图标库更新了，你也去同步」广播。
     *
     * 动态注册的接收器（见 `hook/feature/NativeNotifyIcon`），包名定向发送，
     * 不经任何第三方。SystemUI 侧收到后走它**自己**的 reload + fetch —— 广播里
     * 不带数据，数据永远由每个进程的私有缓存各自负责。
     */
    const val SYNC_ACTION = "io.github.YGHFv.HyperExtend.SYNC_NOTIFY_ICONS"

    /** ANIP 官方仓库。上游模块默认就是它，不给自定义入口 —— 换仓库是开发者的事。 */
    private const val OFFICIAL_REPO_SLUG = "BetterAndroid/android-notification-icon-project"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var anip: Anip? = null

    /** 已发布快照。null 表示本进程还没拿到过任何可用资源。 */
    @Volatile
    var snapshot: NotificationIconSnapshot? = null
        private set

    /** 已发布快照的仓库清单地址与资源时间戳；尚无可用快照时为 null。 */
    @Volatile
    var snapshotVersion: Pair<String, Long>? = null
        private set

    /** 一次同步的结论。[message] 是给界面直接显示的句子。 */
    data class Outcome(val message: String, val success: Boolean)

    /**
     * 按 [source] 建一个（或复用现有的）ANIP 实例。
     *
     * 来源变了要重建而不是复用：ANIP 的缓存目录按来源的 identity 隔离，
     * 改 config.source 换目录的代价与新建一样 —— 干脆显式重建，语义更清楚。
     */
    private fun obtain(context: Context, source: NotifyIconSyncSource): Anip =
        synchronized(this) {
            val config = AnipConfig(systemVariant = SystemVariant.MIOS).apply {
                this.source = buildRemoteSource(source)
            }
            Anip(context.applicationContext, config).also { anip = it }
        }

    private fun buildRemoteSource(source: NotifyIconSyncSource): RemoteSource {
        val prefix = source.urlPrefix
        if (prefix.isEmpty()) return RemoteSource.GitHub(OFFICIAL_REPO_SLUG)
        val resolver = RemoteSource.UrlResolver { sourceUrl, _, _ -> "$prefix/$sourceUrl" }
        return RemoteSource.GitHub(OFFICIAL_REPO_SLUG, resolver)
    }

    /**
     * 从私有缓存恢复快照，再向远端要一次最新资源；两者都成功才重新发布快照。
     *
     * @param notifyPhase true 表示这是「自动更新」触发的同步（结果会写进模块日志供排查）
     */
    suspend fun refresh(context: Context, source: NotifyIconSyncSource, notifyPhase: Boolean = false): Outcome {
        val current = obtain(context, source)
        val published = withContext(Dispatchers.IO) {
            runCatching {
                current.reload()
                val result = current.fetch()
                publish(current)
                if (result.isOk && snapshot != null) {
                    Outcome(
                        if (result.status == Anip.FetchResult.Status.UP_TO_DATE) {
                            "图标库已是最新。"
                        } else {
                            "图标库已更新（共 ${snapshot?.icons?.size ?: 0} 个图标）。"
                        },
                        true,
                    )
                } else {
                    Outcome(result.message.ifBlank { "同步失败：远端没有返回可用资源。" }, false)
                }
            }.getOrElse {
                if (notifyPhase) ModuleLog.error("icon library auto refresh failed", it)
                // 发布失败时快照可能还是 reload 恢复出来的那份 —— 再兜一次。
                runCatching { publish(current) }
                Outcome(it.message ?: it.javaClass.simpleName, false)
            }
        }
        if (published.success) {
            ModuleLog.info(
                "icon library refreshed: ${snapshot?.icons?.size ?: 0} icons " +
                    "(version=${snapshotVersion?.second ?: 0})",
            )
        }
        return published
    }

    /** 由缓存重建快照并发布；失败保留旧快照。启动时（没有网络前提）用它。 */
    suspend fun restore(context: Context, source: NotifyIconSyncSource): Boolean {
        val current = obtain(context, source)
        return withContext(Dispatchers.IO) {
            runCatching {
                if (current.reload()) publish(current) else false
            }.getOrElse { false }
        }
    }

    private suspend fun publish(current: Anip): Boolean {
        val next = current.createSnapshot()
        if (next.icons.isEmpty()) return false
        snapshot = next
        snapshotVersion = current.config.source.manifestUrl to current.timestamp
        return true
    }

    /**
     * 通知渲染路径上的唯一查询口。查不到返回 null —— 调用方保持原样，不猜、不兜。
     *
     * 位图取自快照内置缓存（`getBitmap`），ANIP 自己做了命中管理，这里不加第二层。
     */
    fun bitmapFor(packageName: String): Bitmap? = runCatching {
        val current = snapshot ?: return null
        val icon = current.getIcon(packageName) ?: return null
        current.getBitmap(icon)
    }.getOrNull()

    /** 图标库里适配了哪些应用（界面显示数量用）。 */
    fun iconCount(): Int = snapshot?.icons?.size ?: 0

    /** 本进程是否已有可用快照。 */
    fun hasSnapshot(): Boolean = snapshot != null

    /**
     * 在后台线程做一次 [refresh]，结果交给 [onDone]。
     *
     * 给界面用：同步可能要跑几秒（下载 + 解包），不能让它发生在组合阶段或主线程。
     */
    fun refreshAsync(context: Context, source: NotifyIconSyncSource, onDone: (Outcome) -> Unit) {
        scope.launch {
            val outcome = refresh(context, source)
            withContext(Dispatchers.Main) { onDone(outcome) }
        }
    }
}
