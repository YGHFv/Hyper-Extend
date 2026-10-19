/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdint.h>
#include <unistd.h>

namespace launcher {

// logd 在 native_init 早期可能尚未可用；同时写入桌面私有目录，作为仅本机诊断证据。
inline void experimental_file_trace(const char *stage, uint32_t detail) {
#if defined(LAUNCHER_NATIVE_EXPERIMENTAL_INSTALL)
    if (stage == nullptr) return;
    int fd = open("/data/user/0/com.miui.home/files/hyperextend_native_entry.log",
        O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0) return;
    char message[192]{};
    size_t size = 0;
    // 多个 fork 后的桌面进程共用此文件；PID 用于避免把不同进程的阶段记录串成一条链。
    constexpr char prefix[] = "pid=0x";
    for (size_t i = 0; i < sizeof(prefix) - 1; ++i) message[size++] = prefix[i];
    constexpr char digits[] = "0123456789abcdef";
    const uint32_t pid = static_cast<uint32_t>(getpid());
    for (int shift = 28; shift >= 0; shift -= 4) message[size++] = digits[(pid >> shift) & 15U];
    message[size++] = ' ';
    size_t stage_size = 0;
    while (stage[stage_size] != '\0' && size < sizeof(message) - 12)
        message[size++] = stage[stage_size++];
    message[size++] = '=';
    message[size++] = '0'; message[size++] = 'x';
    for (int shift = 28; shift >= 0; shift -= 4) message[size++] = digits[(detail >> shift) & 15U];
    message[size++] = '\n';
    (void)write(fd, message, size);
    close(fd);
#else
    (void)stage; (void)detail;
#endif
}

// 只供显式实验包对照普通日志；不改宿主 logger、日志级别或系统属性。
// 不缓存函数指针，不引入加载期构造器，也不把日志成功当作安装许可。
inline void experimental_entry_trace(const char *stage, uint32_t detail) {
#if defined(LAUNCHER_NATIVE_EXPERIMENTAL_INSTALL)
    experimental_file_trace(stage, detail);
    char message[128];
    size_t size = 0;
    if (stage == nullptr) return;
    while (stage[size] != '\0') {
        if (size >= sizeof(message) - 12) return;
        message[size] = stage[size];
        ++size;
    }
    message[size++] = '=';
    message[size++] = '0';
    message[size++] = 'x';
    constexpr char digits[] = "0123456789abcdef";
    for (int shift = 28; shift >= 0; shift -= 4) {
        message[size++] = digits[(detail >> shift) & 15U];
    }
    message[size] = '\0';
    // API 30 起公开的直达 logd 接口；动态解析保持 API 26 的加载兼容性。
    // 仅写固定阶段与数值，不写进程路径、身份信息或配置令牌。
    const auto logger = reinterpret_cast<__android_logger_function>(
        dlsym(RTLD_DEFAULT, "__android_log_logd_logger"));
    if (logger != nullptr) {
        const __android_log_message record{
            sizeof(__android_log_message), LOG_ID_MAIN, ANDROID_LOG_INFO,
            "HELauncherEntry", nullptr, 0, message,
        };
        logger(&record);
    } else {
        __android_log_print(ANDROID_LOG_INFO, "HELauncherEntry", "%s", message);
    }
#else
    (void)stage;
    (void)detail;
#endif
}

}  // 命名空间 launcher
