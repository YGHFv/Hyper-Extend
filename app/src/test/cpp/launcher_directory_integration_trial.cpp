/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
// 同一套真实预检、启动和维护故障试验，在同应用组可写 files 目录上重放。
#if defined(DIRECTORY_DIAGNOSTIC_TRIAL)
#include "launcher_diagnostic_entry_trial.cpp"
#elif defined(DIRECTORY_MAINTENANCE_TRIAL)
#include "launcher_safety_maintenance_trial.cpp"
#else
#include "launcher_safety_startup_trial.cpp"
#endif

extern "C" __attribute__((visibility("default")))
int launcher_directory_integration_trial(int code) {
    trial::app_group_files = true;
#if defined(DIRECTORY_DIAGNOSTIC_TRIAL)
    return launcher_diagnostic_trial(code);
#elif defined(DIRECTORY_MAINTENANCE_TRIAL)
    return launcher_safety_maintenance_trial(code);
#else
    return launcher_safety_startup_trial(code);
#endif
}
