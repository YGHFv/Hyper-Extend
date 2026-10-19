"""防砖离线审计的独立只读基线；本模块不执行任何输入输出。"""

POLICY = "launcher-safety-parity-offline-v1"
JAVA = "app/src/main/java/io/github/YGHFv/HyperExtend/"
CPP = "app/src/main/cpp/launcher/"
LAUNCHER_NATIVE = "app/src/launcherNative/"
HOST = {
    "boot_fuse": JAVA + "hook/BootFusePolicy.kt",
    "boot_fuse_test": "app/src/test/java/io/github/YGHFv/HyperExtend/hook/BootFusePolicyTest.kt",
    "kill_switch": JAVA + "hook/KillSwitch.kt",
    "boot_loop_guard": JAVA + "hook/BootLoopGuard.kt",
    "scope_catalog": JAVA + "core/ScopeCatalog.kt",
    "launcher_identity": LAUNCHER_NATIVE + "java/io/github/YGHFv/HyperExtend/hook/launcher/LauncherIdentity.kt",
    "launcher_native_scope": LAUNCHER_NATIVE + "resources/META-INF/xposed/scope.list",
    "safety_gate": CPP + "safety_gate.h",
    "install_permit": CPP + "install_permit.h",
    "native_entry": CPP + "native_entry.cpp",
    "readme": "README.md",
}
FUSE = {
    "window_ms": 300000,
    "min_time_ms": 1600000000000,
    "count_limit": 3,
    "trip_condition": "count >= 3",
    "kotlin_state_fields": ["lastStartAt", "rapidRestarts", "disabled", "lastResetToken", "windowStartAt"],
    "native_state_fields": ["last_start_ms", "rapid_restarts", "disabled", "last_reset_token", "window_start_ms"],
    "field_pairs": [["lastStartAt", "last_start_ms"], ["rapidRestarts", "rapid_restarts"],
                    ["disabled", "disabled"], ["lastResetToken", "last_reset_token"],
                    ["windowStartAt", "window_start_ms"]],
}
TRIP_ORDER = {
    "kotlin": ["now < MIN_TIME", "resetToken > previous.lastResetToken", "previous.disabled",
               "now < previous.lastStartAt", "!countRestart",
               "previous.lastStartAt > 0 && now - previous.windowStartAt <= WINDOW_MS",
               "coerceAtMost(3)", "count >= 3"],
    "native": ["now < kSafetyMinTimeMs", "reset_token > previous.last_reset_token", "previous.disabled",
               "now < previous.last_start_ms", "!count_restart",
               "previous.last_start_ms > 0 && now - previous.window_start_ms <= kSafetyFuseWindowMs",
               "previous.rapid_restarts + 1 > 3 ? 3", "count >= 3"],
}
BODY_DIGESTS = {
    "kotlin_next": "fa721ce2243614d2e4a8160da801af1eaf4cca38142b7632acd3d0ca8ca1f05f",
    "kotlin_decode": "00a0df21622cc969c0fa2f374d3eba946565ea2578f309845c9834bdf667f357",
    "native_next": "e1274128271db11c63877cb188dea4a0e30ab4a72bac1551b458683b5b6231ae",
}
CASE_TABLES_SHA256 = "fd88966897d3f77ade2a4be9bbe10b7bb4450decdc818c5bb36ed599c92a9474"
ESCAPE_GATES = {
    "killSwitch": {"property": "persist.sys.hyperextend.disabled",
                   "files": ["/data/local/tmp/hyperextend.disabled", "/sdcard/HyperExtend/disable"]},
    "bootLoopGuard": {"property": "persist.sys.hyperextend.framework_disabled",
                       "stateFile": "/data/system/hyperextend_bootguard",
                       "systemUiStateFile": "files/hyperextend_systemui_bootguard"},
}
SCOPE = {
    "native_token": "com.miui.home",
    # 桌面作用域的真实来源不是主模块 SCOPES（那是界面侧可重启宿主清单，不含桌面），
    # 而是 launcherNative 的注入作用域声明与它的 Kotlin 身份契约。
    "launcher_package_constant": "PACKAGE",
    "scope_list_entries": ["com.miui.home", "system"],
}
NATIVE_WIRING_BASELINE = {
    "wired": True,
    "required_entry_symbols": ["install_permitted", "snapshot_safety_permits", "safety_fuse_next"],
    # 完整链路验证器逐项要求的证据；缺任一项都不能把本项翻绿。
    "required_commit_symbol": "commit_safety_startup_once",
    "required_links": [
        "entry_reachable",          # 提交调用从导出入口可达
        "snapshot_gated",           # 同一函数内先做快照用途校验
        "commit_result_controlled", # 提交结果进入早退控制流
        "install_dominated",        # 安装许可证据来自提交结果
        "install_plan_gated",       # 安装计划非空校验（resolve_launcher_install_plan/plan.ready）支配唯一安装点
        "failure_blocks_install",   # 提交失败时返回，不继续
        "install_path_present",     # 实际调用已保存的 hook 指针
        "install_path_exclusive",   # 全文仅一处 hook 调用点，不得另开未受控制分支
    ],
}
STRUCTURAL_WIRING_BASELINE = {
    "structure_wired": True,
    "feasibility_verified": True,
    "experimental_install_switch": True,
    "feasibility_exports": 10,
    "feasibility_imports": 27,
    "readonly_imports": 20,
    "new_imports": ["__errno", "__write_chk", "flock", "fstatat", "fsync", "openat", "renameat"],
    "new_whitelist_entries": ["write", "__write_chk", "fsync", "flock", "renameat", "fstatat", "__errno"],
}
README_SECTION_HEADINGS = {"start": "## 防砖", "end_prefix": "## ", "anchor": "逃生门"}
README_REQUIRED_STEPS = {
    "persist.sys.hyperextend.disabled": ["设回", "删除已设置的文件标记"],
    "persist.sys.hyperextend.framework_disabled": ["设回", "删除状态文件"],
}
KOTLIN_TEST_METHODS = [
    "fourthStartTripsAndLatchSurvivesLongWait", "windowIsNotExtendedByEveryRestart",
    "exactWindowBoundaryIsIncluded", "resetIsOneShotAndProtectionRemainsActive",
    "noRestartDoesNotIncrementCounter", "badClockAndUnreadableStateFailClosed",
    "legacyStateIsPreservedAndNewStateRoundTrips", "invalidStateCannotSilentlyResetCounters",
]
