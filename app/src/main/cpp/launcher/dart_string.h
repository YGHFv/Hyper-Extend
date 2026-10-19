/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stddef.h>
#include <stdint.h>

// 桌面宿主是 Dart AOT，分享调用点的 x1 是一个完整的 tagged String 指针。
// 这里只接受包名所需的 ASCII 子集，并在读取对象头之前先限制长度，避免把
// 损坏的 tagged 指针当成可读内存。
namespace launcher {

constexpr uint32_t kDartStringCid = 93;
constexpr uint32_t kDartOneByteStringCid = 94;
constexpr uint32_t kDartTwoByteStringCid = 95;
constexpr uint32_t kDartStringCidShift = 12;
constexpr uint32_t kDartStringCidMask = 0xfffff;
constexpr uint32_t kDartMaxPackageNameLength = 255;
constexpr uintptr_t kDartTaggedPointerMask = 1;
// tagged 地址 = 原始对象地址 + 1；对象头正好占原始地址前的 8 字节。
// 下列偏移都以 tagged 地址为基准，和宿主反汇编里的 ldur/tagged 访问一致。
constexpr uint32_t kDartHeaderBytesBeforeTagged = 1;
constexpr uint32_t kDartLengthOffsetFromTagged = 7;
constexpr uint32_t kDartDataOffsetFromTagged = 11;

struct DartStringView {
    const char *data;
    uint32_t length;
    bool two_byte;
};

// tagged 对象的真实地址总是去掉最低位；对象头中 bit 12..31 是类 ID。
constexpr uintptr_t dart_object_address(uintptr_t tagged) {
    return tagged & ~static_cast<uintptr_t>(kDartTaggedPointerMask);
}

constexpr uint32_t dart_cid_from_header(uint64_t header) {
    return static_cast<uint32_t>((header >> kDartStringCidShift) & kDartStringCidMask);
}

constexpr bool dart_string_cid(uint32_t cid) {
    return cid == kDartStringCid || cid == kDartOneByteStringCid ||
        cid == kDartTwoByteStringCid;
}

// 只允许小写字母、数字、点、下划线和短横线。这样既覆盖合法包名，又排除
// 路径、NUL 和任何需要再次解析的字符；首字符不能是点或短横线。
constexpr bool package_name_char(char value, bool first) {
    if (value >= 'a' && value <= 'z') return true;
    if (value >= '0' && value <= '9') return !first;
    if (value == '.') return !first;
    if (value == '_' || value == '-') return !first;
    return false;
}

// 与 system_server 侧 packagePolicy 使用同一收敛规则：除字符集外还要求点号
// 形成非空段。只校验一个字符无法发现连续点或尾点，必须在复制完成后整串检查。
constexpr bool package_name_canonical(const char *value, size_t length) {
    if (value == nullptr || length == 0 || length > kDartMaxPackageNameLength ||
        value[0] == '.' || value[length - 1] == '.') return false;
    for (size_t index = 0; index < length; ++index) {
        if (!package_name_char(value[index], index == 0)) return false;
        if (value[index] == '.' && value[index + 1] == '.') return false;
    }
    return true;
}

// 从宿主字符串对象中解出只读视图。调用方仍负责确认指针在可读映射内；
// 这里不捕获异常，也不做分配。长度来自对象头后的第一个 32 位字段。
inline bool dart_string_view(uintptr_t tagged, DartStringView *out) {
    if (out == nullptr || tagged == 0 || (tagged & kDartTaggedPointerMask) == 0) return false;
    const auto object = reinterpret_cast<const uint8_t *>(tagged);
    if (object == nullptr) return false;
    uint64_t header = 0;
    __builtin_memcpy(&header, object - kDartHeaderBytesBeforeTagged, sizeof(header));
    const uint32_t cid = dart_cid_from_header(header);
    if (!dart_string_cid(cid)) return false;
    uint32_t length = 0;
    __builtin_memcpy(&length, object + kDartLengthOffsetFromTagged, sizeof(length));
    if (length == 0 || length > kDartMaxPackageNameLength) return false;
    out->data = reinterpret_cast<const char *>(object + kDartDataOffsetFromTagged);
    out->length = length;
    out->two_byte = cid == kDartTwoByteStringCid;
    return true;
}

// 将 Dart String 复制为 NUL 结尾的包名。双字节字符串只接受低字节，
// 高字节非零直接拒绝，避免把 UTF-16 内容截成另一个包名。
inline bool copy_package_name(uintptr_t tagged, char *buffer, size_t capacity) {
    if (buffer == nullptr || capacity == 0) return false;
    DartStringView view{};
    if (!dart_string_view(tagged, &view)) return false;
    if (view.length + 1 > capacity) return false;
    const auto *bytes = reinterpret_cast<const uint8_t *>(view.data);
    for (uint32_t index = 0; index < view.length; ++index) {
        // OneByteString 每个字符 1 字节；TwoByteString 每个字符 2 字节。
        const char value = static_cast<char>(
            view.two_byte ? bytes[index * 2] : bytes[index]);
        if (view.two_byte && bytes[index * 2 + 1] != 0) return false;
        if (!package_name_char(value, index == 0)) return false;
        buffer[index] = value;
    }
    buffer[view.length] = '\0';
    return package_name_canonical(buffer, view.length);
}

}  // 命名空间 launcher
