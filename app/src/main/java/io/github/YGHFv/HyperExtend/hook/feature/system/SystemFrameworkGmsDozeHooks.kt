/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** 澎湃省电策略对 GMS 推送与联网的限制修补。 */
internal object SystemFrameworkGmsDozeHooks {
    private const val GMS = "com.google.android.gms"
    private const val GMS_PERSISTENT = "com.google.android.gms.persistent"
    private const val ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE"
    private val deferredActions = setOf(
        "com.google.android.intent.action.GCM_RECONNECT",
        "com.google.android.gcm.DISCONNECTED",
        "com.google.android.gcm.CONNECTED",
        "com.google.android.gms.gcm.HEARTBEAT_ALARM",
    )

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn("system_framework_gms_doze")) return 0
        var count = 0
        count += installGreezeManager(loader)
        count += installDomesticPolicy(loader)
        count += installListAppsManager(loader)
        count += installBroadcastAutoStart(loader)
        count += installProcessPolicy(loader)
        count += installAwareResourceControl(loader)
        count += installActivityManager(loader)
        return count
    }

    private fun installGreezeManager(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.server.greeze.GreezeManagerService") ?: return 0
        var count = 0
        val packageName = Reflect.findMethods(clazz, "getPackageNameFromUid", paramCount = 1).firstOrNull()
        packageName?.isAccessible = true
        for (method in Reflect.findMethods(clazz, "isAllowBroadcast", paramCount = 5)) {
            if (method.returnType != Boolean::class.javaPrimitiveType) continue
            if (HookRuntime.hook(method, "gms_doze/GreezeManagerService#isAllowBroadcast") { chain ->
                    val calleeUid = chain.args.getOrNull(2) as? Int
                    val resolved = calleeUid?.let {
                        Reflect.attempt { packageName?.invoke(chain.thisObject, it) } as? String
                    }
                    val callee = resolved ?: chain.args.getOrNull(3) as? String
                    val caller = chain.args.getOrNull(1) as? String
                    val action = chain.args.getOrNull(4) as? String
                    val gmsPush = caller == GMS && action == ACTION_REMOTE_INTENT
                    val gmsDeferred = (callee == GMS || callee == GMS_PERSISTENT) &&
                        action in deferredActions
                    if (gmsPush || gmsDeferred) true else chain.proceed()
                }
            ) count++
        }
        for (method in Reflect.findMethods(clazz, "deferBroadcastForMiui", paramCount = 1)) {
            if (method.returnType != Boolean::class.javaPrimitiveType) continue
            if (HookRuntime.hook(method, "gms_doze/GreezeManagerService#deferBroadcastForMiui") { chain ->
                    val action = chain.args.getOrNull(0) as? String
                    if (action in deferredActions) false else chain.proceed()
                }
            ) count++
        }
        for (method in Reflect.findMethods(clazz, "triggerGMSLimitAction")) {
            if (HookRuntime.hook(method, "gms_doze/GreezeManagerService#triggerGMSLimitAction") { chain ->
                    if (chain.args.isEmpty()) {
                        Reflect.writeField(chain.thisObject, "mGmsLimitEnabled", false)
                        chain.proceed()
                    } else {
                        val args = chain.args.toTypedArray()
                        args[0] = false
                        chain.proceed(args)
                    }
                }
            ) count++
        }
        return count
    }

    private fun installDomesticPolicy(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.server.greeze.DomesticPolicyManager") ?: return 0
        return Reflect.findMethods(clazz, "deferBroadcast", paramCount = 1)
            .filter { it.returnType == Boolean::class.javaPrimitiveType }
            .count {
                HookRuntime.hookReturning(
                    it,
                    "gms_doze/DomesticPolicyManager#deferBroadcast",
                    false,
                )
            }
    }

    private fun installListAppsManager(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.server.greeze.power.ListAppsManager") ?: return 0
        val method = Reflect.findMethods(clazz, "isInWhiteList", paramCount = 1)
            .firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
            ?: return 0
        return if (HookRuntime.hook(method, "gms_doze/ListAppsManager#isInWhiteList") { chain ->
                if (chain.args.getOrNull(0) == GMS) true else chain.proceed()
            }
        ) 1 else 0
    }

    private fun installBroadcastAutoStart(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.am.BroadcastQueueModernStubImpl")
            ?: return 0
        val method = Reflect.findMethods(clazz, "checkApplicationAutoStart", paramCount = 3)
            .firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
            ?: return 0
        return if (HookRuntime.hook(method, "gms_doze/BroadcastQueueModernStubImpl#checkApplicationAutoStart") { chain ->
                val record = chain.args.getOrNull(1)
                val caller = record?.let { Reflect.readField(it, "callerPackage") as? String }
                val intent = record?.let { Reflect.readField(it, "intent") as? Intent }
                if (caller == GMS && intent?.action == ACTION_REMOTE_INTENT) true else chain.proceed()
            }
        ) 1 else 0
    }

    private fun installProcessPolicy(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.am.ProcessPolicy") ?: return 0
        val method = Reflect.findMethods(clazz, "getWhiteList", paramCount = 1).firstOrNull()
            ?: return 0
        return if (HookRuntime.hook(method, "gms_doze/ProcessPolicy#getWhiteList") { chain ->
                val result = chain.proceed()
                val flags = chain.args.getOrNull(0) as? Int ?: return@hook result
                if (flags and 1 == 0) return@hook result
                val list = when (result) {
                    is MutableList<*> -> @Suppress("UNCHECKED_CAST") result as MutableList<Any?>
                    is List<*> -> result.toMutableList()
                    else -> return@hook result
                }
                if (GMS !in list) list += GMS
                if (GMS_PERSISTENT !in list) list += GMS_PERSISTENT
                list
            }
        ) 1 else 0
    }

    private fun installAwareResourceControl(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.server.greeze.power.AwareResourceControl")
            ?: return 0
        var count = 0
        for (constructor in clazz.declaredConstructors) {
            if (HookRuntime.hook(constructor, "gms_doze/AwareResourceControl#<init>") { chain ->
                    val result = chain.proceed()
                    val list = Reflect.readField(chain.thisObject, "mNoNetworkBlackUids")
                    if (list is MutableCollection<*>) {
                        @Suppress("UNCHECKED_CAST")
                        (list as MutableCollection<Any?>).remove(GMS)
                    }
                    result
                }
            ) count++
        }
        return count
    }

    private fun installActivityManager(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.am.ActivityManagerService")
            ?: return 0
        val broadcast = Reflect.findMethods(clazz, "broadcastIntentWithFeature", paramCount = 16)
            .firstOrNull()
            ?: return 0
        val getRecord = Reflect.findMethods(clazz, "getRecordForAppLOSP", paramCount = 1)
            .firstOrNull()
        return if (HookRuntime.hook(broadcast, "gms_doze/ActivityManagerService#broadcastIntentWithFeature") { chain ->
                val intent = chain.args.getOrNull(2) as? Intent
                if (intent?.action == ACTION_REMOTE_INTENT) {
                    val app = getRecord?.let {
                        Reflect.attempt { it.invoke(chain.thisObject, chain.args.getOrNull(0)) }
                    }
                    val info = app?.let { Reflect.readField(it, "info") as? ApplicationInfo }
                    if (info?.packageName == GMS) {
                        grantTemporaryPushAllowance(
                            chain.thisObject,
                            intent,
                            Thread.currentThread().contextClassLoader,
                        )
                        if (intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES == 0) {
                            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        }
                    }
                }
                chain.proceed()
            }
        ) 1 else 0
    }

    private fun grantTemporaryPushAllowance(service: Any, intent: Intent, loader: ClassLoader?) {
        val target = intent.`package` ?: return
        val context = Reflect.readField(service, "mContext") as? Context ?: return
        val managerClass = Reflect.loadClass(
            loader ?: context.classLoader,
            "android.os.PowerExemptionManager",
        ) ?: return
        val manager = Reflect.attempt {
            managerClass.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }
                .newInstance(context)
        } ?: return
        val method = Reflect.findMethods(managerClass, "addToTemporaryAllowList", paramCount = 4)
            .firstOrNull() ?: return
        Reflect.attempt { method.invoke(manager, target, 102, "GOOGLE_C2DM", 2000L) }
            .also { if (it == null) ModuleLog.warn("gms_doze: temporary allow-list call failed") }
    }
}
