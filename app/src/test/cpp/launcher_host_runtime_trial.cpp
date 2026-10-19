/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#include "../../main/cpp/launcher/host_image_contract.h"
#include "../../main/cpp/launcher/startup_wait.h"
#include "../../main/cpp/launcher/batch_apply.h"
#include "../../main/cpp/launcher/install_batch_policy.h"

extern "C" void *memset(void *p, int value, size_t size) {
    for (size_t i = 0; i < size; ++i) static_cast<unsigned char *>(p)[i] = value;
    return p;
}
extern "C" void *memcpy(void *p, const void *q, size_t size) {
    for (size_t i = 0; i < size; ++i)
        static_cast<unsigned char *>(p)[i] = static_cast<const unsigned char *>(q)[i];
    return p;
}
#define CHECK(x) do { if (!(x)) return __LINE__; } while (false)

extern "C" __attribute__((visibility("default")))
int launcher_host_runtime_trial(int code) {
    using namespace launcher;
    if (code == 0) {
        const char *accepted[] = {
            "/product/priv-app/MiuiHome/MiuiHome.apk!/lib/arm64-v8a/libapp.so",
            "/product/priv-app/MiuiHome/lib/arm64/libapp.so",
            "/data/app/cache/com.miui.home-random/base.apk!/lib/arm64-v8a/libapp.so"};
        for (const char *name : accepted) {
            CHECK(launcher_library_path(name, fixed_len(name)));
        }
        const char *rejected[] = {
            "/data/app/cache/com.miui.home.fake/base.apk!/lib/arm64-v8a/libapp.so",
            "/data/app/cache/other/base.apk!/lib/arm64-v8a/libapp.so",
            "/product/priv-app/MiuiHome/MiuiHome.apk!/lib/arm64-v8a/libapp.so.bak",
            "/data/app/cache/com.miui.home-/base.apk!/lib/arm64-v8a/libapp.so"};
        for (const char *name : rejected) {
            CHECK(!launcher_library_path(name, fixed_len(name)));
        }
        CHECK(launcher_apk_path(kSystemLauncherApk, fixed_len(kSystemLauncherApk)));
        constexpr char apk[] = "/data/app/cache/com.miui.home-random/base.apk";
        CHECK(launcher_apk_path(apk, sizeof(apk) - 1));
        return 0;
    }
    if (code >= 1 && code <= 8) {
        unsigned char note[32]{};
        ElfW(Phdr) phdr[3]{};
        phdr[0].p_type = PT_LOAD; phdr[0].p_flags = PF_R; phdr[0].p_filesz = sizeof(note);
        dl_phdr_info info{};
        info.dlpi_addr = reinterpret_cast<uintptr_t>(note);
        info.dlpi_phdr = phdr; info.dlpi_phnum = 1;
        if (code == 2) note[16] ^= 1; // 内容变化不影响可读区间，也不构成版本门禁。
        if (code == 3) phdr[0].p_filesz = 31;
        if (code == 4) phdr[0].p_type = PT_NOTE;
        if (code == 5) info.dlpi_phnum = 0;
        if (code == 6) phdr[0].p_vaddr = 4096;
        if (code == 7) phdr[0].p_flags = 0;
        if (code == 8) info.dlpi_addr = UINTPTR_MAX;
        CHECK(readable_image_span(info, 0, sizeof(note)) == (code == 1 || code == 2));
        return 0;
    }
    if (code >= 9 && code <= 13) {
        unsigned probes = 0, waits = 0;
        const auto result = wait_for_host(
            [&] { return code != 11 || waits < 2; },
            [&] { ++probes; return code == 9 || (code == 10 && waits == 3); },
            [&] { ++waits; return code != 12; });
        if (code == 9) CHECK(result == StartupWaitResult::kReady && probes == 1 && waits == 0);
        if (code == 10) CHECK(result == StartupWaitResult::kReady && probes == 4 && waits == 3);
        if (code == 11) CHECK(result == StartupWaitResult::kIdentityRejected && waits == 2);
        if (code == 12) CHECK(result == StartupWaitResult::kWaitFailed && waits == 1);
        if (code == 13) CHECK(result == StartupWaitResult::kExpired && probes == 200 && waits == 199);
        return 0;
    }
    if (code == 18) {
        const void *first = reinterpret_cast<const void *>(0x1000);
        const void *other = reinterpret_cast<const void *>(0x2000);
        CHECK(!batch_target_needed(first, first));
        CHECK(batch_target_needed(other, first));
        CHECK(!batch_target_needed(nullptr, first));
        return 0;
    }
    if (code == 19) {
        bool actions[2] = {true, true};
        unsigned prepared = 0, published = 0, discarded = 0;
        InstallBatchOutcome outcome{};
        outcome.parts_attempted = 1;
        const bool actions_ok = apply_unique_batch(actions,
            [&](size_t) { ++prepared; return true; },
            [&](size_t) { ++published; return true; },
            [&] { ++discarded; },
            [&](size_t, bool preparing, bool success) {
                if (!success) record_batch_failure(&outcome,
                    InstallBatchPart::kShareActions, preparing, 0U, true);
            });
        CHECK(actions_ok && prepared == 2 && published == 2 && discarded == 0);
        outcome.parts_succeeded = 1;
        bool freeform[1] = {true};
        const bool freeform_ok = apply_unique_batch(freeform,
            [&](size_t) { ++prepared; return true; },
            [&](size_t) {
                record_batch_failure(&outcome, InstallBatchPart::kFreeform,
                    false, 6U, false);
                return false;
            },
            [&] { ++discarded; },
            [&](size_t, bool preparing, bool success) {
                if (!success) record_batch_failure(&outcome,
                    InstallBatchPart::kFreeform, preparing, 0U, true);
            });
        CHECK(!freeform_ok && prepared == 3 && published == 2 && discarded == 1);
        CHECK(outcome.parts_attempted == 1 && outcome.parts_succeeded == 1);
        // 这里直接调用通用批处理，不经过 apply_specs，公开计数不会自动增加；
        // 只验证发布失败报告没有清除上一组已发布的事实和真实错误码。
        CHECK(outcome.published == 0);
        CHECK(outcome.failed_part ==
            static_cast<uint32_t>(InstallBatchPart::kFreeform));
        CHECK(outcome.failed_stage == 2 && outcome.error_code == 6);
        return 0;
    }
    if (code == 20) {
        bool unique[2] = {true, true};
        unsigned prepared = 0, published = 0, discarded = 0;
        InstallBatchOutcome outcome{};
        const bool ok = apply_unique_batch(unique,
            [&](size_t index) {
                ++prepared;
                return index != 0;
            },
            [&](size_t) { ++published; return true; },
            [&] { ++discarded; },
            [&](size_t, bool preparing, bool success) {
                if (!success) record_batch_failure(&outcome,
                    InstallBatchPart::kShareActions, preparing, 0U, true);
            });
        CHECK(!ok && prepared == 1 && published == 0 && discarded == 1);
        CHECK(outcome.failed_stage == 1 && outcome.error_code == 0);
        return 0;
    }
    if (code == 21) {
        bool empty[2] = {false, false};
        CHECK(!batch_has_unique(empty, 2));
        bool filled[2] = {false, true};
        CHECK(batch_has_unique(filled, 2));
        return 0;
    }
    bool unique[7] = {true, false, true, true, false, true, false};
    if (code == 17) for (bool &entry : unique) entry = false;
    unsigned prepared = 0, published = 0, discarded = 0, errors = 0;
    const bool ok = apply_unique_batch(unique,
        [&](size_t i) {
            if (!unique[i] || published != 0) ++errors;
            ++prepared;
            return code != 15 || i != 3;
        },
        [&](size_t i) {
            if (!unique[i] || prepared != 4) ++errors;
            ++published;
            return code != 16 || i != 3;
        },
        [&] { ++discarded; });
    CHECK(errors == 0);
    if (code == 14) CHECK(ok && prepared == 4 && published == 4 && discarded == 0);
    if (code == 15) CHECK(!ok && prepared == 3 && published == 0 && discarded == 1);
    if (code == 16) CHECK(!ok && prepared == 4 && published == 3 && discarded == 1);
    if (code == 17) CHECK(!ok && prepared == 0 && published == 0 && discarded == 0);
    return 0;
}
