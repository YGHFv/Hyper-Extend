/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
#include <stdint.h>

namespace launcher {
enum class StartupWaitResult : uint32_t { kReady, kExpired, kIdentityRejected, kWaitFailed };

// 只在后台线程等待宿主映像，次数有界；等待期间不采纳配置、不记录启动或写代码页。
template <typename Identity, typename Ready, typename Wait>
StartupWaitResult wait_for_host(Identity identity, Ready ready, Wait wait) {
    for (unsigned attempt = 0; attempt < 200; ++attempt) {
        if (!identity()) return StartupWaitResult::kIdentityRejected;
        if (ready()) return StartupWaitResult::kReady;
        if (attempt != 199 && !wait()) return StartupWaitResult::kWaitFailed;
    }
    return StartupWaitResult::kExpired;
}
}  // 命名空间 launcher
