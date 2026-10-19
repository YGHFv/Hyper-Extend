/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * 单字发布协议的**生产实现**（不是试验替身）。
 *
 * 三个导出函数就是协议本身的三个步骤，符号名与
 * `app/src/test/resources/launcher-gate5-residual-contract.json` 的 `trialSymbols`
 * 逐字一致，因此门槛 5 残余项 (c) 的机器码证据现在针对的是**本文件**，
 * 而不是一份只存在于测试目录里的等价写法。改写这三个函数中的任何一个，
 * 都会让 `tools/launcher_gate5_residual_audit.py` 直接失败。
 *
 * 本文件与 count_backend.cpp 一起进入原型生产目标，供真实二进制接受单字提交
 * 机器码审计；没有安全、后端和现场计划证据时，guarded_install_once 会在唯一
 * 安装点之前早退，不会触碰宿主代码页。
 *   协议本身的编译期合同由 `app/src/test/cpp/launcher_singleword_publish_test.cpp`
 *   在原型构建的 `launcher_probe_contract` 目标里执行（该目标只在
 *   `-PlauncherNativePrototype=true` 时存在），协议检查仍作为独立编译期合同保留。
 */

#include "singleword_publish.h"

extern "C" {

// 第一步：把已写好的蹦床代码同步到指令侧。
// 审计要求本函数只做 dc cvau → dsb ish → ic ivau → dsb ish，且**不含任何数据存储**。
__attribute__((visibility("hidden"))) void launcher_publish_trampoline(void *code, uintptr_t bytes) {
    launcher::singleword::publish_code(code, static_cast<size_t>(bytes));
}

// 第二步：把调用点那一个对齐的 32 位字换成 `BL 蹦床`。
// 审计要求本函数恰好是「一条 32 位存储 + ret」。
__attribute__((visibility("hidden"))) void launcher_commit_call_site(uint32_t *site, uint32_t word) {
    launcher::singleword::store_release(site, word);
}

// 两步的组合：必须是「先同步、后提交」。顺序颠倒会破坏预取推测保护。
__attribute__((visibility("hidden"))) void launcher_publish_then_commit(
    void *code, uintptr_t bytes, uint32_t *site, uint32_t word) {
    launcher::singleword::publish_code(code, static_cast<size_t>(bytes));
    launcher::singleword::store_release(site, word);
}

}  // extern "C"
