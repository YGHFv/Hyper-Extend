/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
// 离线替身只检查日志记录，不链接或调用设备的 logd。
#include "../../main/cpp/launcher/entry_trace.h"
#include <stdarg.h>

extern "C" int open(const char *, int, ...) { return -1; }
extern "C" long write(int, const void *, unsigned long) { return -1; }
extern "C" int close(int) { return 0; }

namespace {
int lookups = 0, direct = 0, fallback = 0, errors = 0;
bool available = true;
const char *expected_message = nullptr;

bool equal(const char *left, const char *right) {
    if (left == nullptr || right == nullptr) return false;
    while (*left != '\0' && *left == *right) { ++left; ++right; }
    return *left == *right;
}

void logger(const __android_log_message *record) {
    ++direct;
    if (record->struct_size != sizeof(*record) || record->buffer_id != LOG_ID_MAIN ||
        record->priority != ANDROID_LOG_INFO || record->file != nullptr || record->line != 0 ||
        !equal(record->tag, "HELauncherEntry") || !equal(record->message, expected_message)) {
        ++errors;
    }
}
}  // 匿名命名空间

extern "C" void *dlsym(void *, const char *name) {
    ++lookups;
    if (!equal(name, "__android_log_logd_logger")) ++errors;
    return available ? reinterpret_cast<void *>(logger) : nullptr;
}

extern "C" int __android_log_print(int priority, const char *tag, const char *format, ...) {
    ++fallback;
    va_list values;
    va_start(values, format);
    const char *message = va_arg(values, const char *);
    va_end(values);
    if (priority != ANDROID_LOG_INFO || !equal(tag, "HELauncherEntry") ||
        !equal(format, "%s") || !equal(message, expected_message)) ++errors;
    return 0;
}

extern "C" __attribute__((visibility("default")))
int launcher_entry_trace_trial(int scenario) {
    lookups = direct = fallback = errors = 0;
    available = scenario != 1;
    expected_message = "native_init_abi=0x00000002";
    const char *stage = "native_init_abi";
    uint32_t detail = 2;
    char long_stage[118];
    char long_message[128];
    if (scenario == 2) { stage = "zero"; detail = 0; expected_message = "zero=0x00000000"; }
    if (scenario == 3) { stage = "max"; detail = UINT32_MAX; expected_message = "max=0xffffffff"; }
    if (scenario == 4) stage = nullptr;
    if (scenario == 5 || scenario == 6) {
        for (int i = 0; i < 117; ++i) long_stage[i] = 'a';
        long_stage[scenario == 5 ? 117 : 116] = '\0';
        stage = long_stage;
        if (scenario == 6) {
            for (int i = 0; i < 116; ++i) long_message[i] = 'a';
            const char suffix[] = "=0x00000002";
            for (int i = 0; i < 12; ++i) long_message[116 + i] = suffix[i];
            expected_message = long_message;
        }
    }
    launcher::experimental_entry_trace(stage, detail);
#if defined(LAUNCHER_NATIVE_EXPERIMENTAL_INSTALL)
    const bool valid = scenario != 4 && scenario != 5;
    if (lookups != (valid ? 1 : 0)) return __LINE__;
    if (direct != (valid && available ? 1 : 0)) return __LINE__;
    if (fallback != (valid && !available ? 1 : 0)) return __LINE__;
#else
    if (lookups != 0 || direct != 0 || fallback != 0) return __LINE__;
#endif
    return errors == 0 ? 0 : __LINE__;
}
