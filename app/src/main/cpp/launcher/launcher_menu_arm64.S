/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * 菜单透明叶蹦床的标记入口。实际安装时后端会为每个调用点复制同一
 * 四条指令并重定位到独立 original 槽；此入口用于安装计划的类型与链接合同。
 * 现场许可通过前不会写入宿主调用点。
 */
    .text
    .p2align 4
    .hidden launcher_menu_primary_island
    .globl  launcher_menu_primary_island
    .type   launcher_menu_primary_island, %function
launcher_menu_primary_island:
    adrp    x16, launcher_menu_original_primary
    add     x16, x16, :lo12:launcher_menu_original_primary
    ldr     x16, [x16]
    br      x16
    .size launcher_menu_primary_island, .-launcher_menu_primary_island

/* 分享动作入口：完整保存调用者状态，把 x1 中的目标包名交给原生分享桥；
 * 返回后恢复状态，按宿主原有约定把 x0 置为 Dart null，并直接返回调用点+4。 */
    .hidden launcher_menu_primary_trace
    .globl  launcher_menu_primary_trace
    .type   launcher_menu_primary_trace, %function
launcher_menu_primary_trace:
    sub     sp, sp, #1040
    stp     x0, x1, [sp]
    stp     x2, x3, [sp, #16]
    stp     x4, x5, [sp, #32]
    stp     x6, x7, [sp, #48]
    stp     x8, x9, [sp, #64]
    stp     x10, x11, [sp, #80]
    stp     x12, x13, [sp, #96]
    stp     x14, x15, [sp, #112]
    stp     x16, x17, [sp, #128]
    str     x18, [sp, #136]
    stp     x19, x20, [sp, #144]
    stp     x21, x22, [sp, #160]
    stp     x23, x24, [sp, #176]
    stp     x25, x26, [sp, #192]
    stp     x27, x28, [sp, #208]
    str     x29, [sp, #224]
    str     x30, [sp, #232]
    stp     q0, q1, [sp, #240]
    stp     q2, q3, [sp, #272]
    stp     q4, q5, [sp, #304]
    stp     q6, q7, [sp, #336]
    stp     q8, q9, [sp, #368]
    stp     q10, q11, [sp, #400]
    stp     q12, q13, [sp, #432]
    stp     q14, q15, [sp, #464]
    stp     q16, q17, [sp, #496]
    stp     q18, q19, [sp, #528]
    stp     q20, q21, [sp, #560]
    stp     q22, q23, [sp, #592]
    stp     q24, q25, [sp, #624]
    stp     q26, q27, [sp, #656]
    stp     q28, q29, [sp, #688]
    stp     q30, q31, [sp, #720]
    mov     w0, #1
    bl      launcher_share_bridge
    // C++ 返回 0 表示 system_server 已接收这次分享请求，宿主闭包的结果
    // 应由本蹦床以 Dart null 返回；失败时必须恢复完整现场并尾跳原闭包，
    // 不能让一次桥故障吞掉宿主点击。
    cbnz    w0, .Lshare_primary_fallback
    ldp     q30, q31, [sp, #720]
    ldp     q28, q29, [sp, #688]
    ldp     q26, q27, [sp, #656]
    ldp     q24, q25, [sp, #624]
    ldp     q22, q23, [sp, #592]
    ldp     q20, q21, [sp, #560]
    ldp     q18, q19, [sp, #528]
    ldp     q16, q17, [sp, #496]
    ldp     q14, q15, [sp, #464]
    ldp     q12, q13, [sp, #432]
    ldp     q10, q11, [sp, #400]
    ldp     q8, q9, [sp, #368]
    ldp     q6, q7, [sp, #336]
    ldp     q4, q5, [sp, #304]
    ldp     q2, q3, [sp, #272]
    ldp     q0, q1, [sp, #240]
    ldr     x30, [sp, #232]
    ldr     x29, [sp, #224]
    ldp     x27, x28, [sp, #208]
    ldp     x25, x26, [sp, #192]
    ldp     x23, x24, [sp, #176]
    ldp     x21, x22, [sp, #160]
    ldp     x19, x20, [sp, #144]
    ldr     x18, [sp, #136]
    ldp     x16, x17, [sp, #128]
    ldp     x14, x15, [sp, #112]
    ldp     x12, x13, [sp, #96]
    ldp     x10, x11, [sp, #80]
    ldp     x8, x9, [sp, #64]
    ldp     x6, x7, [sp, #48]
    ldp     x4, x5, [sp, #32]
    ldp     x2, x3, [sp, #16]
    ldp     x0, x1, [sp]
    add     sp, sp, #1040
    mov     x0, x22
    ret

.Lshare_primary_fallback:
    ldp     q30, q31, [sp, #720]
    ldp     q28, q29, [sp, #688]
    ldp     q26, q27, [sp, #656]
    ldp     q24, q25, [sp, #624]
    ldp     q22, q23, [sp, #592]
    ldp     q20, q21, [sp, #560]
    ldp     q18, q19, [sp, #528]
    ldp     q16, q17, [sp, #496]
    ldp     q14, q15, [sp, #464]
    ldp     q12, q13, [sp, #432]
    ldp     q10, q11, [sp, #400]
    ldp     q8, q9, [sp, #368]
    ldp     q6, q7, [sp, #336]
    ldp     q4, q5, [sp, #304]
    ldp     q2, q3, [sp, #272]
    ldp     q0, q1, [sp, #240]
    ldr     x30, [sp, #232]
    ldr     x29, [sp, #224]
    ldp     x27, x28, [sp, #208]
    ldp     x25, x26, [sp, #192]
    ldp     x23, x24, [sp, #176]
    ldp     x21, x22, [sp, #160]
    ldp     x19, x20, [sp, #144]
    ldr     x18, [sp, #136]
    ldp     x16, x17, [sp, #128]
    ldp     x14, x15, [sp, #112]
    ldp     x12, x13, [sp, #96]
    ldp     x10, x11, [sp, #80]
    ldp     x8, x9, [sp, #64]
    ldp     x6, x7, [sp, #48]
    ldp     x4, x5, [sp, #32]
    ldp     x2, x3, [sp, #16]
    ldp     x0, x1, [sp]
    add     sp, sp, #1040
    adrp    x16, launcher_menu_original_primary
    add     x16, x16, :lo12:launcher_menu_original_primary
    ldr     x16, [x16]
    br      x16
    .size launcher_menu_primary_trace, .-launcher_menu_primary_trace

    .hidden launcher_menu_secondary_island
    .globl  launcher_menu_secondary_island
    .type   launcher_menu_secondary_island, %function
launcher_menu_secondary_island:
    adrp    x16, launcher_menu_original_share_secondary
    add     x16, x16, :lo12:launcher_menu_original_share_secondary
    ldr     x16, [x16]
    br      x16
    .size launcher_menu_secondary_island, .-launcher_menu_secondary_island

    .hidden launcher_menu_secondary_trace
    .globl  launcher_menu_secondary_trace
    .type   launcher_menu_secondary_trace, %function
launcher_menu_secondary_trace:
    sub     sp, sp, #1040
    stp     x0, x1, [sp]
    stp     x2, x3, [sp, #16]
    stp     x4, x5, [sp, #32]
    stp     x6, x7, [sp, #48]
    stp     x8, x9, [sp, #64]
    stp     x10, x11, [sp, #80]
    stp     x12, x13, [sp, #96]
    stp     x14, x15, [sp, #112]
    stp     x16, x17, [sp, #128]
    str     x18, [sp, #136]
    stp     x19, x20, [sp, #144]
    stp     x21, x22, [sp, #160]
    stp     x23, x24, [sp, #176]
    stp     x25, x26, [sp, #192]
    stp     x27, x28, [sp, #208]
    str     x29, [sp, #224]
    str     x30, [sp, #232]
    stp     q0, q1, [sp, #240]
    stp     q2, q3, [sp, #272]
    stp     q4, q5, [sp, #304]
    stp     q6, q7, [sp, #336]
    stp     q8, q9, [sp, #368]
    stp     q10, q11, [sp, #400]
    stp     q12, q13, [sp, #432]
    stp     q14, q15, [sp, #464]
    stp     q16, q17, [sp, #496]
    stp     q18, q19, [sp, #528]
    stp     q20, q21, [sp, #560]
    stp     q22, q23, [sp, #592]
    stp     q24, q25, [sp, #624]
    stp     q26, q27, [sp, #656]
    stp     q28, q29, [sp, #688]
    stp     q30, q31, [sp, #720]
    mov     w0, #2
    bl      launcher_share_bridge
    // 与主调用点相同：ACK 成功才以 Dart null 返回，否则透明恢复并
    // 尾跳原闭包，保证桥失败不会吞掉次菜单项的点击。
    cbnz    w0, .Lshare_secondary_fallback
    ldp     q30, q31, [sp, #720]
    ldp     q28, q29, [sp, #688]
    ldp     q26, q27, [sp, #656]
    ldp     q24, q25, [sp, #624]
    ldp     q22, q23, [sp, #592]
    ldp     q20, q21, [sp, #560]
    ldp     q18, q19, [sp, #528]
    ldp     q16, q17, [sp, #496]
    ldp     q14, q15, [sp, #464]
    ldp     q12, q13, [sp, #432]
    ldp     q10, q11, [sp, #400]
    ldp     q8, q9, [sp, #368]
    ldp     q6, q7, [sp, #336]
    ldp     q4, q5, [sp, #304]
    ldp     q2, q3, [sp, #272]
    ldp     q0, q1, [sp, #240]
    ldr     x30, [sp, #232]
    ldr     x29, [sp, #224]
    ldp     x27, x28, [sp, #208]
    ldp     x25, x26, [sp, #192]
    ldp     x23, x24, [sp, #176]
    ldp     x21, x22, [sp, #160]
    ldp     x19, x20, [sp, #144]
    ldr     x18, [sp, #136]
    ldp     x16, x17, [sp, #128]
    ldp     x14, x15, [sp, #112]
    ldp     x12, x13, [sp, #96]
    ldp     x10, x11, [sp, #80]
    ldp     x8, x9, [sp, #64]
    ldp     x6, x7, [sp, #48]
    ldp     x4, x5, [sp, #32]
    ldp     x2, x3, [sp, #16]
    ldp     x0, x1, [sp]
    add     sp, sp, #1040
    mov     x0, x22
    ret

.Lshare_secondary_fallback:
    ldp     q30, q31, [sp, #720]
    ldp     q28, q29, [sp, #688]
    ldp     q26, q27, [sp, #656]
    ldp     q24, q25, [sp, #624]
    ldp     q22, q23, [sp, #592]
    ldp     q20, q21, [sp, #560]
    ldp     q18, q19, [sp, #528]
    ldp     q16, q17, [sp, #496]
    ldp     q14, q15, [sp, #464]
    ldp     q12, q13, [sp, #432]
    ldp     q10, q11, [sp, #400]
    ldp     q8, q9, [sp, #368]
    ldp     q6, q7, [sp, #336]
    ldp     q4, q5, [sp, #304]
    ldp     q2, q3, [sp, #272]
    ldp     q0, q1, [sp, #240]
    ldr     x30, [sp, #232]
    ldr     x29, [sp, #224]
    ldp     x27, x28, [sp, #208]
    ldp     x25, x26, [sp, #192]
    ldp     x23, x24, [sp, #176]
    ldp     x21, x22, [sp, #160]
    ldp     x19, x20, [sp, #144]
    ldr     x18, [sp, #136]
    ldp     x16, x17, [sp, #128]
    ldp     x14, x15, [sp, #112]
    ldp     x12, x13, [sp, #96]
    ldp     x10, x11, [sp, #80]
    ldp     x8, x9, [sp, #64]
    ldp     x6, x7, [sp, #48]
    ldp     x4, x5, [sp, #32]
    ldp     x2, x3, [sp, #16]
    ldp     x0, x1, [sp]
    add     sp, sp, #1040
    adrp    x16, launcher_menu_original_share_secondary
    add     x16, x16, :lo12:launcher_menu_original_share_secondary
    ldr     x16, [x16]
    br      x16
    .size launcher_menu_secondary_trace, .-launcher_menu_secondary_trace

/*
 * 分享闭包入口蹦床。
 *
 * 入口替换只改写闭包第一条指令，因此调用者现场、x29 和 x30 都与直接调用
 * 原闭包一致。入口先保存调用者要保留的 ABI 寄存器与 NZCV，但不提前写
 * Dart 的 x15：原闭包自己会用 x15 建立 Dart 帧。包名解析只读取外层对象
 * 和阶段对象字段，不修改这两块内存。
 *
 * 主入口若看到免责声明标志位，不调用分享桥，恢复现场后尾跳透明原闭包。
 * 否则只把包名交给桥做转发与取证，随后无条件恢复现场并透明跳回原闭包。
 * 分享闭包本身负责 Dart Future 的建立与异步退栈；入口蹦床不能直接 ret，
 * 否则会把异步调用改写成同步返回，破坏宿主菜单的分发链。
 */
    .hidden launcher_menu_share_entry_primary
    .globl  launcher_menu_share_entry_primary
    .type   launcher_menu_share_entry_primary, %function
launcher_menu_share_entry_primary:
    sub     sp, sp, #1568
    stp     x19, x20, [sp]
    stp     x21, x22, [sp, #16]
    stp     x23, x24, [sp, #32]
    stp     x25, x26, [sp, #48]
    stp     x27, x28, [sp, #64]
    stp     x29, x30, [sp, #80]
    stp     q0, q1, [sp, #96]
    stp     q2, q3, [sp, #128]
    stp     q4, q5, [sp, #160]
    stp     q6, q7, [sp, #192]
    stp     q8, q9, [sp, #224]
    stp     q10, q11, [sp, #256]
    stp     q12, q13, [sp, #288]
    stp     q14, q15, [sp, #320]
    stp     q16, q17, [sp, #352]
    stp     q18, q19, [sp, #384]
    stp     q20, q21, [sp, #416]
    stp     q22, q23, [sp, #448]
    stp     q24, q25, [sp, #480]
    stp     q26, q27, [sp, #512]
    stp     q28, q29, [sp, #544]
    stp     q30, q31, [sp, #576]
    mrs     x9, nzcv
    str     x9, [sp, #608]
    // C++ 桥可改写 x0..x18，其中 x15 是原闭包的 Dart 栈指针。入口计划
    // 尚未建立宿主帧，因此失败回退前必须把整个调用者现场恢复原值。
    // str/ldr 的单寄存器立即数范围足够覆盖整块保存区，避免借用 x9 作
    // 基址时又被 mrs x9, nzcv 覆盖。
    str     x0, [sp, #616]
    str     x1, [sp, #624]
    str     x2, [sp, #632]
    str     x3, [sp, #640]
    str     x4, [sp, #648]
    str     x5, [sp, #656]
    str     x6, [sp, #664]
    str     x7, [sp, #672]
    str     x8, [sp, #680]
    str     x9, [sp, #688]
    str     x10, [sp, #696]
    str     x11, [sp, #704]
    str     x12, [sp, #712]
    str     x13, [sp, #720]
    str     x14, [sp, #728]
    str     x15, [sp, #736]
    str     x16, [sp, #744]
    str     x17, [sp, #752]
    str     x18, [sp, #760]

    // 主闭包：[x29+0x10] 的 w0 bit#4 表示免责声明分支，不在此处接管。
    ldr     x9, [x29, #0x10]
    tbnz    w9, #4, .Lshare_entry_primary_fallback
    // outer = [x29+0x18]；stage = low32[outer+7] + (heap_bits << 32)；
    // package = low32[stage+0xf] + (heap_bits << 32)。
    ldr     x9, [x29, #0x18]
    ldur    w10, [x9, #7]
    add     x10, x10, x28, lsl #32
    ldur    w1, [x10, #0xf]
    add     x1, x1, x28, lsl #32
    mov     w0, #1
    bl      launcher_share_entry_bridge
    b       .Lshare_entry_primary_fallback

.Lshare_entry_primary_fallback:
    ldr     x9, [sp, #608]
    msr     nzcv, x9
    ldr     x16, [sp, #744]
    ldr     x17, [sp, #752]
    ldr     x18, [sp, #760]
    ldr     x14, [sp, #728]
    ldr     x15, [sp, #736]
    ldr     x12, [sp, #712]
    ldr     x13, [sp, #720]
    ldr     x10, [sp, #696]
    ldr     x11, [sp, #704]
    ldr     x8, [sp, #680]
    ldr     x6, [sp, #664]
    ldr     x7, [sp, #672]
    ldr     x4, [sp, #648]
    ldr     x5, [sp, #656]
    ldr     x2, [sp, #632]
    ldr     x3, [sp, #640]
    ldr     x0, [sp, #616]
    ldr     x1, [sp, #624]
    ldr     x9, [sp, #688]
    ldp     q30, q31, [sp, #576]
    ldp     q28, q29, [sp, #544]
    ldp     q26, q27, [sp, #512]
    ldp     q24, q25, [sp, #480]
    ldp     q22, q23, [sp, #448]
    ldp     q20, q21, [sp, #416]
    ldp     q18, q19, [sp, #384]
    ldp     q16, q17, [sp, #352]
    ldp     q14, q15, [sp, #320]
    ldp     q12, q13, [sp, #288]
    ldp     q10, q11, [sp, #256]
    ldp     q8, q9, [sp, #224]
    ldp     q6, q7, [sp, #192]
    ldp     q4, q5, [sp, #160]
    ldp     q2, q3, [sp, #128]
    ldp     q0, q1, [sp, #96]
    ldp     x29, x30, [sp, #80]
    ldp     x27, x28, [sp, #64]
    ldp     x25, x26, [sp, #48]
    ldp     x23, x24, [sp, #32]
    ldp     x21, x22, [sp, #16]
    ldp     x19, x20, [sp]
    add     sp, sp, #1568
    adrp    x16, launcher_menu_entry_original_primary
    add     x16, x16, :lo12:launcher_menu_entry_original_primary
    ldr     x16, [x16]
    br      x16

    .size launcher_menu_share_entry_primary, .-launcher_menu_share_entry_primary

    .hidden launcher_menu_share_entry_secondary
    .globl  launcher_menu_share_entry_secondary
    .type   launcher_menu_share_entry_secondary, %function
launcher_menu_share_entry_secondary:
    sub     sp, sp, #1568
    stp     x19, x20, [sp]
    stp     x21, x22, [sp, #16]
    stp     x23, x24, [sp, #32]
    stp     x25, x26, [sp, #48]
    stp     x27, x28, [sp, #64]
    stp     x29, x30, [sp, #80]
    stp     q0, q1, [sp, #96]
    stp     q2, q3, [sp, #128]
    stp     q4, q5, [sp, #160]
    stp     q6, q7, [sp, #192]
    stp     q8, q9, [sp, #224]
    stp     q10, q11, [sp, #256]
    stp     q12, q13, [sp, #288]
    stp     q14, q15, [sp, #320]
    stp     q16, q17, [sp, #352]
    stp     q18, q19, [sp, #384]
    stp     q20, q21, [sp, #416]
    stp     q22, q23, [sp, #448]
    stp     q24, q25, [sp, #480]
    stp     q26, q27, [sp, #512]
    stp     q28, q29, [sp, #544]
    stp     q30, q31, [sp, #576]
    mrs     x9, nzcv
    str     x9, [sp, #608]
    str     x0, [sp, #616]
    str     x1, [sp, #624]
    str     x2, [sp, #632]
    str     x3, [sp, #640]
    str     x4, [sp, #648]
    str     x5, [sp, #656]
    str     x6, [sp, #664]
    str     x7, [sp, #672]
    str     x8, [sp, #680]
    str     x9, [sp, #688]
    str     x10, [sp, #696]
    str     x11, [sp, #704]
    str     x12, [sp, #712]
    str     x13, [sp, #720]
    str     x14, [sp, #728]
    str     x15, [sp, #736]
    str     x16, [sp, #744]
    str     x17, [sp, #752]
    str     x18, [sp, #760]

    // 次闭包：outer = [x29+0x10]，其余字段解析与主闭包相同。
    ldr     x9, [x29, #0x10]
    ldur    w10, [x9, #7]
    add     x10, x10, x28, lsl #32
    ldur    w1, [x10, #0xf]
    add     x1, x1, x28, lsl #32
    mov     w0, #2
    bl      launcher_share_entry_bridge
    b       .Lshare_entry_secondary_fallback

.Lshare_entry_secondary_fallback:
    ldr     x9, [sp, #608]
    msr     nzcv, x9
    ldr     x16, [sp, #744]
    ldr     x17, [sp, #752]
    ldr     x18, [sp, #760]
    ldr     x14, [sp, #728]
    ldr     x15, [sp, #736]
    ldr     x12, [sp, #712]
    ldr     x13, [sp, #720]
    ldr     x10, [sp, #696]
    ldr     x11, [sp, #704]
    ldr     x8, [sp, #680]
    ldr     x6, [sp, #664]
    ldr     x7, [sp, #672]
    ldr     x4, [sp, #648]
    ldr     x5, [sp, #656]
    ldr     x2, [sp, #632]
    ldr     x3, [sp, #640]
    ldr     x0, [sp, #616]
    ldr     x1, [sp, #624]
    ldr     x9, [sp, #688]
    ldp     q30, q31, [sp, #576]
    ldp     q28, q29, [sp, #544]
    ldp     q26, q27, [sp, #512]
    ldp     q24, q25, [sp, #480]
    ldp     q22, q23, [sp, #448]
    ldp     q20, q21, [sp, #416]
    ldp     q18, q19, [sp, #384]
    ldp     q16, q17, [sp, #352]
    ldp     q14, q15, [sp, #320]
    ldp     q12, q13, [sp, #288]
    ldp     q10, q11, [sp, #256]
    ldp     q8, q9, [sp, #224]
    ldp     q6, q7, [sp, #192]
    ldp     q4, q5, [sp, #160]
    ldp     q2, q3, [sp, #128]
    ldp     q0, q1, [sp, #96]
    ldp     x29, x30, [sp, #80]
    ldp     x27, x28, [sp, #64]
    ldp     x25, x26, [sp, #48]
    ldp     x23, x24, [sp, #32]
    ldp     x21, x22, [sp, #16]
    ldp     x19, x20, [sp]
    add     sp, sp, #1568
    adrp    x16, launcher_menu_entry_original_secondary
    add     x16, x16, :lo12:launcher_menu_entry_original_secondary
    ldr     x16, [x16]
    br      x16

    .size launcher_menu_share_entry_secondary, .-launcher_menu_share_entry_secondary

    .hidden launcher_menu_freeform_island
    .globl  launcher_menu_freeform_island
    .type   launcher_menu_freeform_island, %function
launcher_menu_freeform_island:
    adrp    x16, launcher_menu_original_freeform
    add     x16, x16, :lo12:launcher_menu_original_freeform
    ldr     x16, [x16]
    br      x16
    .size launcher_menu_freeform_island, .-launcher_menu_freeform_island

/*
 * 可见性入口替换。
 *
 * 宿主 isValid 在 Dart 栈 x15 上建立帧，并通过异步 Future 返回 Dart bool：
 * x22+0x30 为 false，x22+0x20 为 true。这里先保存临时寄存器和 NZCV，
 * 通过可调用中继完整执行原函数，再按功能位只把 false 改成 true。原函数
 * 为 true、功能未开启或任意条件不满足时都原样返回，不把异步函数改写成
 * 常量同步返回。
 */
    .p2align 4
    .hidden launcher_menu_share_valid_override
    .globl  launcher_menu_share_valid_override
    .type   launcher_menu_share_valid_override, %function
launcher_menu_share_valid_override:
    stp     x9, x10, [x15, #-16]!
    mrs     x9, nzcv
    stp     x9, x11, [x15, #-16]!
    stp     x30, xzr, [x15, #-16]!
    adrp    x16, launcher_menu_original_share_valid
    add     x16, x16, :lo12:launcher_menu_original_share_valid
    ldr     x16, [x16]
    adr     x30, .Lshare_valid_after
    blr     x16
.Lshare_valid_after:
    adrp    x16, launcher_menu_feature_state
    add     x16, x16, :lo12:launcher_menu_feature_state
    ldr     w16, [x16]
    tst     w16, #0x2
    b.eq    .Lshare_valid_restore
    add     x17, x22, #0x30
    cmp     x0, x17
    b.ne    .Lshare_valid_restore
    add     x0, x22, #0x20
.Lshare_valid_restore:
    ldp     x30, xzr, [x15], #16
    ldp     x9, x11, [x15], #16
    msr     nzcv, x9
    ldp     x9, x10, [x15], #16
    ret
    .size launcher_menu_share_valid_override, .-launcher_menu_share_valid_override

    .p2align 4
    .hidden launcher_menu_freeform_valid_override
    .globl  launcher_menu_freeform_valid_override
    .type   launcher_menu_freeform_valid_override, %function
launcher_menu_freeform_valid_override:
    stp     x9, x10, [x15, #-16]!
    mrs     x9, nzcv
    stp     x9, x11, [x15, #-16]!
    stp     x30, xzr, [x15, #-16]!
    adrp    x16, launcher_menu_original_freeform_valid
    add     x16, x16, :lo12:launcher_menu_original_freeform_valid
    ldr     x16, [x16]
    adr     x30, .Lfreeform_valid_after
    blr     x16
.Lfreeform_valid_after:
    adrp    x16, launcher_menu_feature_state
    add     x16, x16, :lo12:launcher_menu_feature_state
    ldr     w16, [x16]
    tst     w16, #0x4
    b.eq    .Lfreeform_valid_restore
    add     x17, x22, #0x30
    cmp     x0, x17
    b.ne    .Lfreeform_valid_restore
    add     x0, x22, #0x20
.Lfreeform_valid_restore:
    ldp     x30, xzr, [x15], #16
    ldp     x9, x11, [x15], #16
    msr     nzcv, x9
    ldp     x9, x10, [x15], #16
    ret
    .size launcher_menu_freeform_valid_override, .-launcher_menu_freeform_valid_override

/*
 * isValid 的异步状态机在多个出口处才产生最终 Dart bool。各出口岛页以 x17
 * 传入原出口后续指令地址；这里按功能位产生 true/false，再回到共享返回蹦床。
 */
    .p2align 4
    .hidden launcher_menu_share_valid_exit
    .globl  launcher_menu_share_valid_exit
    .type   launcher_menu_share_valid_exit, %function
launcher_menu_share_valid_exit:
    stp     x9, x10, [x15, #-16]!
    mrs     x9, nzcv
    stp     x9, x11, [x15, #-16]!
    stp     x16, x17, [x15, #-16]!
    add     x0, x22, #0x30
    adrp    x16, launcher_menu_feature_state
    add     x16, x16, :lo12:launcher_menu_feature_state
    ldr     w16, [x16]
    tst     w16, #0x2
    b.eq    .Lshare_valid_exit_restore
    add     x0, x22, #0x20
.Lshare_valid_exit_restore:
    ldp     x16, x17, [x15], #16
    ldr     x17, [x17]
    ldp     x9, x11, [x15], #16
    msr     nzcv, x9
    ldp     x9, x10, [x15], #16
    br      x17
    .size launcher_menu_share_valid_exit, .-launcher_menu_share_valid_exit

    .p2align 4
    .hidden launcher_menu_freeform_valid_exit
    .globl  launcher_menu_freeform_valid_exit
    .type   launcher_menu_freeform_valid_exit, %function
launcher_menu_freeform_valid_exit:
    stp     x9, x10, [x15, #-16]!
    mrs     x9, nzcv
    stp     x9, x11, [x15, #-16]!
    stp     x16, x17, [x15, #-16]!
    add     x0, x22, #0x30
    adrp    x16, launcher_menu_feature_state
    add     x16, x16, :lo12:launcher_menu_feature_state
    ldr     w16, [x16]
    tst     w16, #0x4
    b.eq    .Lfreeform_valid_exit_restore
    add     x0, x22, #0x20
.Lfreeform_valid_exit_restore:
    ldp     x16, x17, [x15], #16
    ldr     x17, [x17]
    ldp     x9, x11, [x15], #16
    msr     nzcv, x9
    ldp     x9, x10, [x15], #16
    br      x17
    .size launcher_menu_freeform_valid_exit, .-launcher_menu_freeform_valid_exit

    .section .note.GNU-stack,"",%progbits
