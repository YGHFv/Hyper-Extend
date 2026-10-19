/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * 数量调用点专用岛式蹦床。当前已编入隐藏原型代码并由数量安装计划指向它；
 * 现场许可通过前不会真正写入宿主调用点。
 * 控制流合同由 launcher_count_island_audit.py 与对应测试源验证。
 */
    .text
    .p2align 4
    .hidden launcher_shortcut_count_island
    .globl  launcher_shortcut_count_island
    .type   launcher_shortcut_count_island, %function
launcher_shortcut_count_island:
    adr     x30, .Lcount_after
    adrp    x16, launcher_shortcut_count_island_original
    add     x16, x16, :lo12:launcher_shortcut_count_island_original
    ldr     x17, [x16]
    br      x17
.Lcount_after:
    adrp    x16, launcher_shortcut_count_state
    add     x16, x16, :lo12:launcher_shortcut_count_state
    ldar    w17, [x16]
    sub     w16, w17, #5
    cmp     w16, #15
    b.hi    .Lcount_keep
    mov     w0, w17
.Lcount_keep:
    adrp    x16, launcher_shortcut_count_island_continuation
    add     x16, x16, :lo12:launcher_shortcut_count_island_continuation
    ldr     x30, [x16]
    br      x30
    .size launcher_shortcut_count_island, .-launcher_shortcut_count_island
    .section .note.GNU-stack,"",%progbits
