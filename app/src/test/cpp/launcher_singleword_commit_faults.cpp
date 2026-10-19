/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
/*
 * 门槛 5 残余项 (c) 的**故障对照**源：故意用几种常见的错误写法提交调用点，
 * 证明 `tools/launcher_gate5_residual_audit.py` 的判定在**真实工具链输出**上
 * 确实能拒绝它们，而不是只在合成机器字上自证。
 *
 * 每个函数都对应一类真实的错误：
 *   faults_two_word_store   分两条普通存储写两个字（多字覆盖，非原子）
 *   faults_pair_store       一条 STP 写两个字（多字覆盖，非原子）
 *   faults_wide_store       用 64 位存储覆盖两个 32 位字（多字覆盖）
 *   faults_memcpy_store     退化成 memcpy（长度可被运行时改变）
 *   faults_sync_missing_ic  缺少 ic ivau（只做 dc + dsb）
 *   faults_commit_first     先提交、后同步（破坏预取推测保护）
 *
 * 本文件不参与 Android 构建，只由 tools/launcher_singleword_commit_fault_audit.py
 * 用 NDK clang 交叉编译；不含安装动作、不链接 SO、不接触设备。
 */

#include <stdint.h>
#include <string.h>

struct faults_pair {
    uint32_t first;
    uint32_t second;
};

extern "C" {

void faults_two_word_store(uint32_t *site, uint32_t a, uint32_t b) {
    *(volatile uint32_t *)site = a;
    *(volatile uint32_t *)(site + 1) = b;
}

void faults_pair_store(uint32_t *site, uint32_t a, uint32_t b) {
    *(faults_pair *)site = faults_pair{a, b};
}

void faults_wide_store(uint32_t *site, uint32_t word) {
    const uint64_t widened = word;
    *(volatile uint64_t *)site = widened;
}

void faults_memcpy_store(uint32_t *site, const uint32_t *source) {
    memcpy(site, source, sizeof(uint32_t));
}

/* 只完成数据缓存维护、缺少指令缓存维护：新蹦床可能取不到。 */
void faults_sync_missing_ic(uint32_t *page) {
    __asm__ __volatile__("dc cvau, %0" ::"r"(page) : "memory");
    __asm__ __volatile__("dsb ish" ::: "memory");
    __asm__ __volatile__("dsb ish" ::: "memory");
}

/* 顺序颠倒：先改调用点，再同步蹦床页；取到新分支的线程可能仍未取到新代码。 */
void faults_commit_first(uint32_t *site, uint32_t word, uint32_t *page) {
    *(volatile uint32_t *)site = word;
    __asm__ __volatile__("dc cvau, %0" ::"r"(page) : "memory");
    __asm__ __volatile__("dsb ish" ::: "memory");
    __asm__ __volatile__("ic ivau, %0" ::"r"(page) : "memory");
    __asm__ __volatile__("dsb ish" ::: "memory");
}

}  /* extern "C" */
