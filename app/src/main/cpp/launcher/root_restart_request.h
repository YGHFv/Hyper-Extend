/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
#include "planned_restart.h"
#include "safety_gate.h"
#include <sys/stat.h>

namespace launcher {
constexpr char kRootRestartRequest[] = "hyperextend_launcher_restart.request";
constexpr char kRootRestartConsumed[] = "hyperextend_launcher_restart.consumed";

inline bool root_restart_file(const struct stat &info) {
    return S_ISREG(info.st_mode) && info.st_uid == 0 && info.st_nlink == 1 &&
        (info.st_mode & 0222) == 0 && info.st_size > 0 && info.st_size <= 128;
}

inline bool parse_root_restart(const char *text, size_t size, const DiagnosticContext &context,
    int64_t now, StartupRestartGrant &out) {
    int64_t fields[5]{};
    size_t cursor = 0;
    for (size_t i = 0; i < 5; ++i) {
        if (i != 0 && (cursor == size || text[cursor++] != ',')) return false;
        if (!parse_uint(text, size, cursor, fields[i])) return false;
    }
    if (cursor != size || fields[0] != 1 || fields[1] != context.uid ||
        fields[2] > INT64_MAX - 30 || (fields[3] != 1 && fields[3] != 2)) return false;
    out = {context.pid, context.uid, fields[2], fields[2] + 30, fields[4], fields[3] == 2};
    return restart_grant_valid(out, context, now);
}
}  // 命名空间 launcher
