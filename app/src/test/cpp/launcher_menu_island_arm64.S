/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * 四项菜单功能共用的「调用点单字替换」蹦床（叶蹦床）。
 *
 * 挂载方式：把某条调用点的
 *   0x1147018  bl 原目标
 * 换成
 *   0x1147018  bl 本蹦床
 * 只改一个对齐的 32 位机器字；旧、新字都属于 ARM DDI 0487 L.a B2.2.5 的
 * 无同步并发修改例外集合，因此不需要多字覆盖协议。
 *
 * 控制流契约（x30 语义已由离线实验取证，见 tools/launcher_menu_island_audit.py）：
 *   - `BL 本蹦床` 由硬件把 x30 设成「调用点+4」。本蹦床是叶函数，**不调用**任何
 *     子程序，因此**绝不写 x30**；x30 一路保持「调用点+4」。
 *   - 蹦床用 `B 原目标` 尾跳（**不要** BL / blr），原目标照常执行、照常按 x30
 *     返回，控制流回到同一条接续指令。所以这里**不需要**“显式恢复 x30”，
 *     只需要保证不破坏它。
 *   - 蹦床只碰 x16/x17（Dart TMP/TMP2，编译器保留暂存，不承载 Dart 活值），
 *     不碰 x15（Dart 栈）、x29/x30（帧/返回）、x19..x28（ABI 保留），也不碰
 *     任何向量寄存器。因此这里是**叶蹦床，不动 Dart 栈、不动 C 栈**。
 *
 * 当前语义：无条件尾跳原目标，即本蹦床在行为上等价于原调用。
 * 之所以先落地这一版，是因为“透明转发”本身就是门槛 5 必须单独证明的一项：
 * 若连转发都不保持寄存器、标志、栈与返回地址，后面的配置改写根本没有意义。
 * 真正的配置读取与改写（数量点/分享 APK/小窗/新任务各自的行为）尚未接入；
 * 落地时不得把这一版说成功能已可用。
 *
 * 链接约定：
 *   launcher_menu_island     本蹦床入口，必须隐藏可见（.hidden）；
 *   launcher_menu_original   原目标运行期地址，由模块在写补丁字之前填好。
 * 两个符号都不得出现在导出集合里。
 *
 * 本文件不接入 CMake、不链接任何 SO，只供离线交叉汇编与模拟器试验。
 */
    .text
    .p2align 4
    .hidden launcher_menu_island
    .globl  launcher_menu_island
    .type   launcher_menu_island, %function
launcher_menu_island:
    adrp    x16, launcher_menu_original
    add     x16, x16, :lo12:launcher_menu_original
    ldr     x16, [x16]
    br      x16
    .size launcher_menu_island, .-launcher_menu_island
    .section .note.GNU-stack,"",%progbits
