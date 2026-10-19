/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdbool.h>

// Zygisk Next API 4 / HyperOS Rust Runtime API 1 的设备侧 ABI 合同。
// 只声明本模块实际用到的前缀字段，不链接任何 Zygisk Next 库。
extern "C" {

constexpr int ZYGISK_NEXT_API_VERSION = 4;
constexpr int ZYGISK_NEXT_HYOS_API_VERSION = 1;
constexpr int ZN_SUCCESS = 0;

struct ZygiskNextRuntime;

struct ZygiskNextAPI {
    int (*pltHook)(void *, const char *, void *, void **);
    int (*inlineHook)(void *, void *, void **);
    int (*inlineUnhook)(void *);
    void *(*newSymbolResolver)(const char *, void *);
    void (*freeSymbolResolver)(void *);
    void *(*getBaseAddress)(void *);
    void *(*symbolLookup)(void *, const char *, bool, unsigned long *);
    void (*forEachSymbols)(void *, bool (*)(const char *, void *, unsigned long, void *), void *);
    int (*connectCompanion)(void *);
    const ZygiskNextRuntime *(*getRuntime)();
};

struct ZygiskNextModule {
    int target_api_version;
    void (*onModuleLoaded)(void *, const ZygiskNextAPI *);
};

enum ZygiskNextRuntimeType {
    ZN_RUNTIME_HYOS = 1,
};

struct ZnHyosAppSpecializeArgs {
    const char *process_name;
    const char *package_name;
    const char *se_info;
};

struct ZygiskNextHyosModule {
    int target_api_version;
    void (*onAppSpecialized)(const ZnHyosAppSpecializeArgs *);
};

struct ZygiskNextRuntime {
    ZygiskNextRuntimeType type;
    int api_version;
    int (*registerModule)(const void *);
};

}
