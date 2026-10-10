/* Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.app.NotificationChannel
import android.content.Context
import android.os.Looper
import android.os.Parcel
import android.widget.Toast
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationImportanceSettingsTargets as Targets

@android.annotation.SuppressLint("DiscouragedPrivateApi")
@android.annotation.TargetApi(30)
internal class NotificationImportanceSettingsHooks(private val loader: ClassLoader) {
    private val methods = (Targets.hooks + Targets.queries + Targets.callers).associateWith {
        requireNotNull(NotificationHooks.resolve(loader, it))
    }
    private val pages = Targets.pages.map { Class.forName(it, false, loader) }
    private val base = Class.forName(Targets.BASE, false, loader)
    private val preference = Class.forName(Targets.PREF, false, loader)
    private val listenerType = Class.forName("${Targets.PREF}\$OnPreferenceChangeListener", false, loader)
    private fun field(name: String) = requireNotNull(Reflect.findField(base, name))
    private val importance = field("mImportance")
    private val backup = field("mBackupImportance")
    private val channel = field("mChannel")
    private val backend = field("mBackend")
    private val pkg = field("mPkg")
    private val uid = field("mUid")
    private val context = field("mContext")
    private val conversation = field("mConversationId")
    private val block = field("mBlock")
    private val find = Reflect.findMethods(base, "findPreference", 1).single {
        it.parameterTypes.contentEquals(arrayOf(CharSequence::class.java))
    }.apply { isAccessible = true }
    private val key = preference.getMethod("getKey")
    private val enabled = preference.getMethod("isEnabled")
    private val setEnabled = preference.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
    private val setListener = preference.getMethod("setOnPreferenceChangeListener", listenerType)
    private val disabledByAdmin = block.type.getMethod("isDisabledByAdmin")
    private val disabledByEcm = block.type.getMethod("isDisabledByEcm")
    private val lock = NotificationChannel::class.java.getDeclaredMethod("lockFields", Int::class.javaPrimitiveType).apply { isAccessible = true }
    private var ready = false
    // Keep a captured row until onResume has finished removing other preferences.
    private val candidates = WeakHashMap<Any, WeakReference<Any>>()

    private fun supported(owner: Any): Boolean {
        val ctx = context.get(owner) as? Context ?: return false
        return ctx.packageManager.getPackageInfo("com.android.settings", 0).let { it.compatibleVersionCode == 37L && it.versionName == "17" }
    }
    private fun restriction(owner: Any, value: NotificationChannel): String? = when {
        value.importance !in 1..4 -> "channel_level_${value.importance}"
        value.id == "miscellaneous" -> "legacy_channel"
        methods.getValue(Targets.configurable).invoke(owner, value) != true -> "not_configurable"
        methods.getValue(Targets.blockable).invoke(owner, value) != true -> "not_blockable"
        methods.getValue(Targets.included).invoke(owner, "importance") != true -> "filtered"
        block.get(owner) == null -> "block_missing"
        disabledByAdmin.invoke(block.get(owner)) == true -> "admin"
        disabledByEcm.invoke(block.get(owner)) == true -> "ecm"
        enabled.invoke(block.get(owner)) != true -> "block_disabled"
        else -> null
    }
    private fun editable(owner: Any, value: NotificationChannel): Boolean = restriction(owner, value) == null

    private fun updateDependents(owner: Any, blocked: Boolean) {
        val type = pages.single { it.isInstance(owner) }
        methods.getValue(Targets.dependents.copy(owner = type.name)).invoke(owner, blocked)
    }

    private fun diagnostic(owner: Any, message: String) {
        // No package/channel names or notification contents in diagnostic events.
        ModuleLog.info("notification_importance: page=${owner.javaClass.name} $message")
    }

    fun install(): Int {
        require(backup.type == Int::class.javaPrimitiveType && uid.type == Int::class.javaPrimitiveType)
        if (!Targets.callers.map { HookRuntime.deoptimize(methods.getValue(it), "notification_importance/settings/${it.name}") }.all { it }) return 0
        val visible = HookRuntime.hook(methods.getValue(Targets.visible), "notification_importance/settingsVisibility") { chain ->
            val owner = chain.thisObject
            val pref = chain.args[0]
            val current = owner?.takeIf { value -> pages.any { it.isInstance(value) } }?.let { channel.get(it) as? NotificationChannel }
            if (ready && Looper.myLooper() == Looper.getMainLooper() && current != null && pref != null &&
                NotificationImportancePolicy.expose(key.invoke(pref) as? String) && supported(owner) && editable(owner, current)) {
                candidates[owner] = WeakReference(pref)
                chain.proceed(arrayOf(pref, true))
            } else chain.proceed()
        }
        val resumes = Targets.resumes.map { spec ->
            HookRuntime.hookAfter(methods.getValue(spec), "notification_importance/settingsResume/${spec.owner}") { chain, original ->
                val owner = chain.thisObject!!
                if (ready && Looper.myLooper() == Looper.getMainLooper()) {
                    if (!supported(owner)) diagnostic(owner, "bind skipped: host_version") else {
                        val pref = candidates.remove(owner)?.get() ?: find.invoke(owner, "importance")
                        if (pref != null) bind(owner, pref) else diagnostic(owner, "bind skipped: row_missing")
                    }
                }
                original
            }
        }
        ready = visible && resumes.all { it }
        return if (ready) 1 + resumes.size else 0
    }

    private fun bind(owner: Any, pref: Any) {
        val current = channel.get(owner) as? NotificationChannel
        val reason = when {
            current == null -> "channel_missing"
            !importance.type.isInstance(pref) -> "row_type"
            else -> restriction(owner, current)
        }
        if (reason != null || current == null) {
            diagnostic(owner, "bind skipped: $reason")
            // Another module can expose this row. Do not leave a restricted, unwired editor active.
            if (importance.type.isInstance(pref)) setEnabled.invoke(pref, false)
            return
        }
        val index = methods.getValue(Targets.index).invoke(pref, current.importance.toString()) as Int
        if (index < 0) { diagnostic(owner, "bind skipped: value_missing"); setEnabled.invoke(pref, false); return }
        val packageName = pkg.get(owner) as? String ?: run { diagnostic(owner, "bind skipped: package_missing"); return }
        val targetUid = uid.getInt(owner)
        val channelId = current.id
        val conversationId = conversation.get(owner) as? String
        val weakOwner = WeakReference(owner)
        val weakPref = WeakReference(pref)
        // Never persist a single "importance" key shared by every app/channel in Settings prefs.
        methods.getValue(Targets.setPersistent).invoke(pref, false)
        importance.set(owner, pref)
        backup.setInt(owner, current.importance)
        methods.getValue(Targets.setIndex).invoke(pref, index)
        val listener = Proxy.newProxyInstance(loader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "HyperExtendNotificationImportanceListener"
                "onPreferenceChange" -> {
                    val value = weakOwner.get()
                    val row = weakPref.get()
                    val level = NotificationImportancePolicy.selection(args?.getOrNull(1))
                    if (SafeModeRuntime.blocked || value == null || row == null || level == null || args?.getOrNull(0) !== row ||
                        Looper.myLooper() != Looper.getMainLooper()) false else try {
                        val target = channel.get(value) as? NotificationChannel
                        if (target?.id != channelId || importance.get(value) !== row || find.invoke(value, "importance") !== row ||
                            pkg.get(value) != packageName || uid.getInt(value) != targetUid || conversation.get(value) != conversationId ||
                            enabled.invoke(row) != true || (methods.getValue(Targets.index).invoke(row, level.toString()) as Int) < 0) false else {
                            diagnostic(value, "selection requested=$level previous=${target.importance}")
                            val access = object : ImportanceChannelAccess<NotificationChannel> {
                                override fun read() = methods.getValue(Targets.read).invoke(backend.get(value), packageName, targetUid, channelId, conversationId) as? NotificationChannel
                                override fun matches(channel: NotificationChannel) = channel.id == channelId &&
                                    channel.conversationId == target.conversationId && channel.parentChannelId == target.parentChannelId
                                override fun editable(channel: NotificationChannel) = targetUid >= 0 && editable(value, channel)
                                override fun importance(channel: NotificationChannel) = channel.importance
                                override fun changedCopy(channel: NotificationChannel, level: Int): NotificationChannel {
                                    val parcel = Parcel.obtain()
                                    return try {
                                        channel.writeToParcel(parcel, 0); parcel.setDataPosition(0)
                                        NotificationChannel.CREATOR.createFromParcel(parcel).also { it.importance = level; lock.invoke(it, 4) }
                                    } finally { parcel.recycle() }
                                }
                                override fun write(channel: NotificationChannel) {
                                    methods.getValue(Targets.update).invoke(backend.get(value), packageName, targetUid, channel)
                                }
                            }
                            val result = updateImportance(level, access)
                            diagnostic(value, "save confirmed=${result.confirmed} actual=${result.actual?.importance}")
                            result.actual?.let { actual ->
                                channel.set(value, actual)
                                if (actual.importance in 1..4) backup.setInt(value, actual.importance)
                                Reflect.attempt { updateDependents(value, actual.importance == 0) }
                            }
                            if (!result.confirmed) {
                                val actualLevel = result.actual?.importance ?: target.importance
                                val actualIndex = methods.getValue(Targets.index).invoke(row, actualLevel.toString()) as Int
                                if (actualIndex >= 0) methods.getValue(Targets.setIndex).invoke(row, actualIndex)
                                failed(value)
                            }
                            result.confirmed
                        }
                    } catch (failure: Throwable) {
                        ModuleLog.error("notification_importance: save/read-back failed", failure)
                        runCatching {
                            val actualLevel = (channel.get(value) as? NotificationChannel)?.importance
                            val actualIndex = methods.getValue(Targets.index).invoke(row, actualLevel?.toString()) as Int
                            if (actualIndex >= 0) methods.getValue(Targets.setIndex).invoke(row, actualIndex)
                        }
                        failed(value)
                        false
                    }
                }
                else -> null
            }
        }
        setListener.invoke(pref, listener)
        diagnostic(owner, "listener bound level=${current.importance} (server read-back enabled)")
    }

    private fun failed(owner: Any) {
        ModuleLog.warn("notification_importance: channel save not confirmed; selection rejected")
        runCatching { (context.get(owner) as? Context)?.let {
            Toast.makeText(it, "通知重要程度未保存，系统可能限制了此渠道，请返回后重试", Toast.LENGTH_LONG).show()
        } }
    }
}
