/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

namespace launcher {

enum ShareBridgeResult : uint32_t {
    kShareBridgeAccepted = 0,
    kShareBridgeInvalidPoint = 1,
    kShareBridgeInvalidPackage = 2,
    kShareBridgeNoBinder = 3,
    kShareBridgeNoService = 4,
    kShareBridgeTransactFailed = 5,
    kShareBridgeRejected = 6,
    kShareBridgeShortReply = 7,
};

// 分享蹦床只负责把主/次调用点与 x1 包名交给 C++；实际 Binder 发送和
// system_server 转发在 native_entry.cpp 中完成。保持独立声明，便于汇编
// 和离线模拟器用同一个 ABI 进行核对。
// 返回 0 表示事务已被 system_server 接收；非零是固定阶段码，仅用于诊断。
// ARM64 蹦床只传 point 与完整 tagged 指针，不依赖寄存器以外的状态。
uint32_t share_bridge_submit(uint32_t point, uintptr_t package_tagged);

// 旧调用点接口保留给离线合同；它只返回是否执行了桥调用，不能据此吞掉
// 宿主点击。入口蹦床应以 result == kShareBridgeAccepted 作为成功条件，
// 其余结果必须透明回退到原闭包。
bool share_bridge_consume(uint32_t point, uintptr_t package_tagged, uint32_t *result);

}  // 命名空间 launcher
