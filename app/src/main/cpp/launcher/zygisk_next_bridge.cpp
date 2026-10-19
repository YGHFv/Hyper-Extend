/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "zygisk_next_api.h"
#include "native_api.h"

#include <android/log.h>

extern "C" launcher::ModuleLoadedCallback native_init(const launcher::NativeApiEntries *entries);

namespace {

const ZygiskNextAPI *g_zygisk_api = nullptr;

bool exact_name(const char *value, const char *expected) {
    if (value == nullptr || expected == nullptr) return false;
    while (*value != '\0' && *expected != '\0') {
        if (*value++ != *expected++) return false;
    }
    return *value == '\0' && *expected == '\0';
}

void on_hyos_app_specialized(const ZnHyosAppSpecializeArgs *args) {
    if (args == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
            "Zygisk Next app_specialized reached; args=null; install_permitted=false");
        return;
    }
    if (!exact_name(args->package_name, "com.miui.home")) return;
    const auto *api = g_zygisk_api;
    if (api == nullptr || api->inlineHook == nullptr || api->inlineUnhook == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
            "Zygisk Next app_specialized reached; package=com.miui.home; api=%s inline=%s uninline=%s; install_permitted=false",
            api != nullptr ? "present" : "null",
            api != nullptr && api->inlineHook != nullptr ? "present" : "null",
            api != nullptr && api->inlineUnhook != nullptr ? "present" : "null");
        return;
    }
    // Zygisk Next 已在特化后提供与 LSPosed 相同形状的 inline API；复用统一入口，
    // 保持身份、快照、安全与安装计划只在一个地方裁定。
    const launcher::NativeApiEntries entries{
        2,
        reinterpret_cast<launcher::HookFunction>(api->inlineHook),
        reinterpret_cast<launcher::UnhookFunction>(api->inlineUnhook),
    };
    __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
        "Zygisk Next app_specialized reached; package=com.miui.home process=%s se_info=%s; install_permitted=false",
        args->process_name != nullptr ? args->process_name : "null",
        args->se_info != nullptr ? args->se_info : "null");
    (void)native_init(&entries);
}

const ZygiskNextHyosModule kHyosModule{
    ZYGISK_NEXT_HYOS_API_VERSION,
    on_hyos_app_specialized,
};

void on_zygisk_module_loaded(void *, const ZygiskNextAPI *api) {
    g_zygisk_api = api;
    if (api == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
            "Zygisk Next onModuleLoaded reached; api=null; install_permitted=false");
        return;
    }
    if (api->getRuntime == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
            "Zygisk Next onModuleLoaded reached; getRuntime=null; install_permitted=false");
        return;
    }
    const auto *runtime = api->getRuntime();
    if (runtime == nullptr) {
        __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
            "Zygisk Next runtime unavailable; install_permitted=false");
        return;
    }
    if (runtime->type != ZN_RUNTIME_HYOS ||
        runtime->api_version < ZYGISK_NEXT_HYOS_API_VERSION ||
        runtime->registerModule == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "HELauncherProbe",
            "Zygisk Next runtime rejected; type=%d api=%d register=%s; install_permitted=false",
            static_cast<int>(runtime->type), runtime->api_version,
            runtime->registerModule != nullptr ? "present" : "null");
        return;
    }
    const int result = runtime->registerModule(&kHyosModule);
    __android_log_print(ANDROID_LOG_INFO, "HELauncherProbe",
        "Zygisk Next runtime registration result=%d; install_permitted=false", result);
}

}  // namespace

extern "C" __attribute__((visibility("default"), used))
ZygiskNextModule zn_module{
    ZYGISK_NEXT_API_VERSION,
    on_zygisk_module_loaded,
};
