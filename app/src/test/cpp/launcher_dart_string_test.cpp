/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "launcher/dart_string.h"

#include <stdint.h>
#include <string.h>

using namespace launcher;

namespace {

struct FakeString {
    uint64_t header;
    uint32_t length;
    uint8_t data[512];
};

constexpr uint64_t string_header(uint32_t cid) {
    return static_cast<uint64_t>(cid) << kDartStringCidShift;
}

uintptr_t tagged(FakeString *value) {
    return reinterpret_cast<uintptr_t>(value) + 1;
}

bool decode(FakeString *value, char *buffer, size_t capacity) {
    return copy_package_name(tagged(value), buffer, capacity);
}

}  // 命名空间

int main() {
    static_assert(kDartStringCid == 93 && kDartOneByteStringCid == 94 &&
        kDartTwoByteStringCid == 95, "字符串类 ID 必须固定");
    static_assert(dart_cid_from_header(string_header(94)) == 94, "CID 解码必须从 bit12 开始");
    static_assert(dart_string_cid(93) && dart_string_cid(94) && dart_string_cid(95),
        "三种字符串类 ID 都必须可识别");
    static_assert(!dart_string_cid(92) && !dart_string_cid(96), "其它类 ID 必须拒绝");
    static_assert(package_name_char('a', true) && package_name_char('1', false) &&
        package_name_char('.', false) && package_name_char('_', false) &&
        package_name_char('-', false), "合法包名字符必须接受");
    static_assert(!package_name_char('A', false) && !package_name_char('/', false) &&
        !package_name_char('1', true) && !package_name_char('.', true),
        "大写、路径分隔符和非法首字符必须拒绝");
    static_assert(package_name_canonical("com.tencent.mobileqq", 19), "规范包名必须接受");
    static_assert(!package_name_canonical(".com.example", 12), "首点必须拒绝");
    static_assert(!package_name_canonical("com..example", 12), "连续点必须拒绝");
    static_assert(!package_name_canonical("com.example.", 12), "尾点必须拒绝");

    char output[256]{};
    FakeString one{};
    one.header = string_header(kDartOneByteStringCid);
    const char package[] = "com.tencent.mobileqq";
    one.length = sizeof(package) - 1;
    __builtin_memcpy(one.data, package, sizeof(package));
    if (!decode(&one, output, sizeof(output))) return 1;
    if (__builtin_strcmp(output, package) != 0) return 2;

    FakeString two{};
    two.header = string_header(kDartTwoByteStringCid);
    two.length = sizeof(package) - 1;
    for (uint32_t index = 0; index < two.length; ++index) {
        two.data[index * 2] = static_cast<uint8_t>(package[index]);
    }
    output[0] = '\0';
    if (!decode(&two, output, sizeof(output))) return 3;
    if (__builtin_strcmp(output, package) != 0) return 4;

    // 双字节字符串的高字节非零不能被截断成另一个 ASCII 包名。
    two.data[1] = 0x4e;
    if (decode(&two, output, sizeof(output))) return 5;
    two.data[1] = 0;

    // CID、长度、首字符和容量都必须拒绝。
    FakeString wrong = one;
    wrong.header = string_header(92);
    if (decode(&wrong, output, sizeof(output))) return 6;
    wrong = one;
    wrong.length = 0;
    if (decode(&wrong, output, sizeof(output))) return 7;
    wrong = one;
    wrong.length = kDartMaxPackageNameLength + 1;
    if (decode(&wrong, output, sizeof(output))) return 8;
    wrong = one;
    wrong.data[0] = '.';
    if (decode(&wrong, output, sizeof(output))) return 9;
    wrong = one;
    wrong.data[3] = '/';
    if (decode(&wrong, output, sizeof(output))) return 10;
    wrong = one;
    wrong.data[3] = '.';
    wrong.data[4] = '.';
    if (decode(&wrong, output, sizeof(output))) return 14;
    wrong = one;
    wrong.data[one.length - 1] = '.';
    if (decode(&wrong, output, sizeof(output))) return 15;
    if (decode(&one, output, one.length)) return 11;
    if (copy_package_name(0, output, sizeof(output))) return 12;
    if (copy_package_name(tagged(&one) & ~kDartTaggedPointerMask, output,
            sizeof(output))) return 13;
    return 0;
}
