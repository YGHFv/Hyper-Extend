/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * 数量点专用蹦床：在调用点被单字改成 `bl 本蹦床` 之后，
 * 保留原 getter 的全部副作用，只改写它交给本次裁剪的裸整数上限。
 *
 * 控制流（x30 语义已由 tools/launcher_menu_island_audit.py 离线取证）：
 *   调用点 `bl 本蹦床`  ->  硬件把 x30 设成「调用点+4」
 *   本蹦床            ->  把 x30 改成岛内 `adr .Lcount_after`，再 `br` 原 getter
 *   原 getter         ->  照常执行、照常按 x30 返回到 `.Lcount_after`
 *   `.Lcount_after`   ->  只做一次性原子字覆盖，然后把 x30 恢复成「调用点+4」
 *                        并 `br` 过去，接续指令处寄存器状态与原来完全一致
 *
 * 因此这里**不需要**进入时保存 x30：原始返回地址由模块在安装前写进
 * `launcher_count_island_continuation` 槽；本蹦床进入时不读、也不动 Dart 栈。
 * `.Lcount_after` 用 `adr` 自相对寻址（±1MB 内），不引入任何本地重定位。
 *
 * 只碰 x16/x17（Dart TMP/TMP2，编译器保留暂存，不承载 Dart 活值）与 x30。
 * x15（Dart 栈）、x29（帧）、x19..x28（ABI 保留）与全部向量寄存器一律不动，
 * 全程零内存写入（只读数据槽）。
 *
 * 一次性原子字语义（与 launcher/dart_abi.h 一致）：
 *   0        = 不干预，原样返回 getter 的 x0；
 *   5..20    = 直接作为裸整数上限写入 w0（写 w0 会清零 x0 高 32 位）；
 *   其它     = 不干预（用 `sub`/`cmp`/`b.hi` 无符号范围判定，同时排除 0、
 *              小于 5、大于 20 与高位异常值）。
 *
 * 模块必须填好的数据槽（都在模块自己的数据页，不在代码页）：
 *   launcher_count_island_original       原 getter 的运行期地址（u64）
 *   launcher_count_island_continuation   调用点+4 的运行期地址（u64）
 *   launcher_count_island_state          一次性原子字（u32，初始 0）
 *
 * 本文件不接入 CMake、不链接任何 SO，只供离线交叉汇编与模拟器试验。
 */
    .text
    .p2align 4
    .hidden launcher_count_island
    .globl  launcher_count_island
    .type   launcher_count_island, %function
launcher_count_island:
    /* 让原 getter 返回到岛内的 .Lcount_after，而不是直接回到调用点+4。 */
    adr     x30, .Lcount_after
    adrp    x16, launcher_count_island_original
    add     x16, x16, :lo12:launcher_count_island_original
    ldr     x17, [x16]
    br      x17
.Lcount_after:
    /* 到这里 x0 = 原 getter 的裸整数返回值，x30 = .Lcount_after。 */
    adrp    x16, launcher_count_island_state
    add     x16, x16, :lo12:launcher_count_island_state
    ldar    w17, [x16]
    sub     w16, w17, #5
    cmp     w16, #15
    b.hi    .Lcount_keep
    mov     w0, w17
.Lcount_keep:
    /* 恢复原始返回地址，接续指令处的寄存器状态与未挂载时逐位一致。 */
    adrp    x16, launcher_count_island_continuation
    add     x16, x16, :lo12:launcher_count_island_continuation
    ldr     x30, [x16]
    br      x30
    .size launcher_count_island, .-launcher_count_island
    .section .note.GNU-stack,"",%progbits
