/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace launcher {

// 异步 isValid 的 false 出口与动作调用点分开发布；只有全部出口安全挂载后才启用可见性位。
enum class InstallBatchPart : uint8_t {
    kCount = 0,
    kFirst = 1,
    kShareActions,
    kFreeform,
    kShareValidity,
    kFreeformValidity,
};

struct InstallBatchOutcome {
    uint32_t parts_attempted = 0;
    uint32_t parts_succeeded = 0;
    uint32_t published = 0;
    uint32_t failed_part = 0;
    uint32_t failed_stage = 0;   // 1=准备，2=发布
    uint32_t error_code = 0;

    constexpr bool succeeded() const {
        return parts_attempted != 0 && parts_succeeded == parts_attempted;
    }
};

constexpr uint32_t batch_part_bit(InstallBatchPart part) {
    return 1U << static_cast<uint32_t>(part);
}

constexpr uint32_t required_batch_bits(uint32_t features, int32_t count_feature,
                                       int32_t share_feature, int32_t freeform_feature) {
    return ((features & static_cast<uint32_t>(count_feature)) != 0
                ? batch_part_bit(InstallBatchPart::kCount) : 0U) |
        ((features & static_cast<uint32_t>(share_feature)) != 0
                ? batch_part_bit(InstallBatchPart::kShareActions) |
                    batch_part_bit(InstallBatchPart::kShareValidity) : 0U) |
        ((features & static_cast<uint32_t>(freeform_feature)) != 0
                ? batch_part_bit(InstallBatchPart::kFreeform) |
                    batch_part_bit(InstallBatchPart::kFreeformValidity) : 0U);
}

// 首选调用点由主组独占；其他组必须排除同一地址，避免同一代码字在准备或
// 发布阶段被重复处理。空目标表示该功能未启用或目标已被首选组接管。
constexpr bool batch_target_needed(const void *target, const void *first_target) {
    return target != nullptr && target != first_target;
}

constexpr bool batch_has_unique(const bool *unique, size_t size) {
    if (unique == nullptr) return false;
    for (size_t index = 0; index < size; ++index)
        if (unique[index]) return true;
    return false;
}

// 业务准备/发布回调先记录具体后端错误；通用的批处理报告回调随后只补阶段，
// 不得用 0 覆盖同一失败。返回值是本回调应写入诊断日志的错误码。
inline uint32_t record_batch_failure(InstallBatchOutcome *outcome,
                                     InstallBatchPart part, bool preparing,
                                     uint32_t error_code,
                                     bool preserve_recorded_error) {
    const uint32_t stage = preparing ? 1U : 2U;
    const bool recorded =
        preserve_recorded_error &&
        outcome->failed_part == static_cast<uint32_t>(part) &&
        outcome->failed_stage == stage &&
        outcome->error_code != 0U;
    outcome->failed_part = static_cast<uint32_t>(part);
    outcome->failed_stage = stage;
    if (!recorded) outcome->error_code = error_code;
    return outcome->error_code;
}

}  // namespace launcher
