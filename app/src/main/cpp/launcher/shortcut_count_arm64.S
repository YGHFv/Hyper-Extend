/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * 快捷菜单数量上限的 Dart ABI 接续蹦床。
 *
 * 挂载点不是函数入口，而是调用者选出的“调用后接续点”：
 *   0x1147018  bl  _getMaxCountInCurrentOrientation
 *   0x114701c  mov x2, x0            <- 覆盖窗口起点，x0 是 getter 的裸整数返回值
 *   0x1147020  ldur x1, [x29, #-8]
 *   0x1147024  stur x2, [x29, #-0x10]
 *   0x1147028  ldur x0, [x1, #-1]
 *   0x114702c  ubfx x0, x0, #12, #20
 *   0x1147030  str x1, [x15]
 *   0x1147034  恢复点（24 字节覆盖结束）
 * 窗口首条指令把 x0 抄进 x2 并写回 [x29-0x10]，后续比较只读这份副本；
 * 因此只要在进入窗口前改写 x0，就能只影响本次菜单裁剪的上限，
 * 同时完整保留原 getter 的初始化与方向查询副作用。
 *
 * 行为：
 *   1. 保存 Dart 活寄存器 x9/x10/x11 与 nzcv 到 Dart 栈（x15）；
 *   2. 读一次性原子字：只接受 5..20，其余状态保持原 getter 的 x0；
 *   3. 恢复 Dart 活寄存器后尾跳后端回填的 `original` 中继，
 *      由后端中继执行窗口内的原指令并回到 0x1147034。
 *
 * 链接约定（由原生诊断库提供隐藏数据槽）：
 *   launcher_shortcut_count_state    仅由本 DSO 初始化的 32 位原子字；
 *   launcher_shortcut_count_original 由 hook_func 回填的中继地址（8 字节）。
 * 两者都必须隐藏可见（.hidden），否则会改变产物的导出集合。
 */
    .text
    .p2align 4
    .hidden launcher_shortcut_count_continuation
    .globl  launcher_shortcut_count_continuation
    .type   launcher_shortcut_count_continuation, %function
launcher_shortcut_count_continuation:
    stp x9, x10, [x15, #-16]!
    mrs x9, nzcv
    stp x9, x11, [x15, #-16]!
    adrp x9, launcher_shortcut_count_state
    add  x9, x9, :lo12:launcher_shortcut_count_state
    ldar w10, [x9]
    sub  w11, w10, #5
    cmp  w11, #15
    b.hi .Lshortcut_count_keep_original
    mov  w0, w10
.Lshortcut_count_keep_original:
    ldp x9, x11, [x15], #16
    msr nzcv, x9
    ldp x9, x10, [x15], #16
    adrp x16, launcher_shortcut_count_original
    add  x16, x16, :lo12:launcher_shortcut_count_original
    ldr  x16, [x16]
    br   x16
    .size launcher_shortcut_count_continuation, .-launcher_shortcut_count_continuation
