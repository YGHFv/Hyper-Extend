/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * 独立离线候选：在 0x1147054 的 B.gt 处处理已经解箱的长度与上限。
 * 原 getter 与长度查询均已执行。只更新 x3/本帧上限并重算原比较，
 * 随后进入原保留或裁剪分支；没有函数调用、Dart 对象分配或新返回地址。
 *
 * 本文件不接入 CMake、不链接到任何 SO，不改变现有候选 2 的 24 字节合同。
 * 这里只验证行为，不能据此宣称分支提交、代码页生命周期或实机安全已验收。
 */
    .text
    .p2align 4
    .hidden launcher_count_branch_trial
    .globl launcher_count_branch_trial
    .type launcher_count_branch_trial, %function
launcher_count_branch_trial:
    stp x16, x17, [x15, #-16]!
    adrp x16, launcher_shortcut_count_state
    add x16, x16, :lo12:launcher_shortcut_count_state
    ldar w16, [x16]
    sub w17, w16, #5
    cmp w17, #15
    b.hi .Lkeep_limit
    mov w3, w16
    stur x3, [x29, #-16]
.Lkeep_limit:
    ldp x16, x17, [x15], #16
    cmp x1, x3
    b.gt .Lclip
    b launcher_count_trial_keep
.Lclip:
    b launcher_count_trial_clip
    .size launcher_count_branch_trial, .-launcher_count_branch_trial
    .section .note.GNU-stack,"",%progbits
