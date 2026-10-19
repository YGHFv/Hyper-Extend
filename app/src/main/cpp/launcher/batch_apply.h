/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
#include <stddef.h>

namespace launcher {
// 去重或未启用的项不参与准备/提交；所有有效项准备成功后才开始发布。
// report 只记录逐项阶段和结果，不参与是否发布的判定。发布一旦开始就不会
// 自动回退已写代码页；调用方必须把可能导致半挂载的项拆成独立事务。
template <size_t Size, typename Prepare, typename Publish, typename Discard, typename Report>
bool apply_unique_batch(const bool (&unique)[Size], Prepare prepare, Publish publish,
                        Discard discard, Report report) {
    bool any = false;
    for (size_t i = 0; i < Size; ++i) {
        if (!unique[i]) continue;
        any = true;
        if (!prepare(i)) {
            report(i, true, false);
            discard();
            return false;
        }
        report(i, true, true);
    }
    if (!any) return false;
    for (size_t i = 0; i < Size; ++i) {
        if (!unique[i]) continue;
        if (!publish(i)) {
            report(i, false, false);
            discard();
            return false;
        }
        report(i, false, true);
    }
    return true;
}

// 保留旧调用形态，便于已有离线试验继续核验原有发布语义。
template <size_t Size, typename Prepare, typename Publish, typename Discard>
bool apply_unique_batch(const bool (&unique)[Size], Prepare prepare, Publish publish, Discard discard) {
    return apply_unique_batch(unique, prepare, publish, discard,
        [](size_t, bool, bool) {});
}
}  // 命名空间 launcher
