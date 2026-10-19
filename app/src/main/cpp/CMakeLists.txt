cmake_minimum_required(VERSION 3.22.1)
project(HyperExtendLauncherNative LANGUAGES CXX)

option(LAUNCHER_NATIVE_EXPERIMENTAL_INSTALL "Enable the explicitly requested local hook trial" OFF)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "桌面原生原型仅支持 Android ARM64")
endif()

add_library(HyperExtendLauncherNative SHARED
    launcher/native_entry.cpp
    launcher/safety_startup.cpp
    launcher/count_backend.cpp
    launcher/singleword_publish.cpp
    launcher/zygisk_next_bridge.cpp)
target_compile_features(HyperExtendLauncherNative PRIVATE cxx_std_17)

# 数量上限蹦床按 Dart AOT 寄存器约定手写。旧接续蹦床与岛式蹦床均作为隐藏 OBJECT
# 链入原生库；状态默认关闭，岛式槽也保持空值。普通构建由实验安装开关关闭
# 唯一安装链路，因此仅构建产物不会导致代码页分配或宿主代码写入。
# 唯一安装点同时受 install_permitted 与计划非空校验支配；未通过现场证据前，
# 即使候选地址与蹦床已存在，也不会调用框架 hook_func。
enable_language(ASM)
add_library(launcher_shortcut_trampoline OBJECT launcher/shortcut_count_arm64.S)
target_compile_options(launcher_shortcut_trampoline PRIVATE -Wa,--noexecstack)
add_library(launcher_shortcut_count_island OBJECT launcher/shortcut_count_island_arm64.S)
target_compile_options(launcher_shortcut_count_island PRIVATE -Wa,--noexecstack)
add_library(launcher_menu_island OBJECT launcher/launcher_menu_arm64.S)
target_compile_options(launcher_menu_island PRIVATE -Wa,--noexecstack)
target_sources(HyperExtendLauncherNative PRIVATE
    $<TARGET_OBJECTS:launcher_shortcut_trampoline>
    $<TARGET_OBJECTS:launcher_shortcut_count_island>
    $<TARGET_OBJECTS:launcher_menu_island>)
add_dependencies(HyperExtendLauncherNative launcher_shortcut_trampoline launcher_shortcut_count_island launcher_menu_island)
# CAS 使用 ARMv8 基线指令，避免编译器引入加载期 CPU/系统属性探测构造器。
target_compile_options(HyperExtendLauncherNative PRIVATE
    -Wall -Wextra -Werror -fno-exceptions -fno-rtti -fvisibility=hidden -mno-outline-atomics)
if(LAUNCHER_NATIVE_EXPERIMENTAL_INSTALL)
    target_compile_definitions(HyperExtendLauncherNative PRIVATE LAUNCHER_NATIVE_EXPERIMENTAL_INSTALL=1)
endif()
target_link_options(HyperExtendLauncherNative PRIVATE -Wl,--no-undefined)
# 实验安装开关可让普通构建裁剪安装链路；显式保留蹦床入口，避免链接器把已链入
# 的真实替换代码当成死代码丢弃。它仍是隐藏符号，不能改变原型导出集合。
target_link_options(HyperExtendLauncherNative PRIVATE
    -Wl,-u,launcher_shortcut_count_continuation
    -Wl,-u,launcher_shortcut_count_island
    -Wl,-u,launcher_menu_primary_island
    -Wl,-u,launcher_menu_secondary_island
    -Wl,-u,launcher_menu_share_entry_primary
    -Wl,-u,launcher_menu_share_entry_secondary
    -Wl,-u,launcher_menu_freeform_island
    -Wl,-u,launcher_menu_share_valid_override
    -Wl,-u,launcher_menu_freeform_valid_override)
target_link_libraries(HyperExtendLauncherNative PRIVATE log)

# 断言在交叉编译时执行，不需要连接或运行设备。
# `singleword_publish.cpp` 与 `count_backend.cpp` 已接入生产库：前者只提交一条对齐
# 32 位 BL，后者负责近距岛页、临时权限窗口和失败事务。未取得现场许可时
# 入口不会触碰这些写入路径。
# 测试目录中的岛式汇编只作离线模拟输入；生产目录的岛式副本虽已链入，但尚未接安装计划。
# safety_startup.cpp 已接进原型：启动状态提交会写宿主私有目录（该后端只写
# 包目录内已核对的文件，原子替换，不删除、不截断）。原型里的唯一安装点被
# install_permitted 与安装计划双重支配：三段来源解析出的目标仅是候选计划；
# 许可不通过时实机不会安装，也不会写宿主代码页。
# install_permit.h 只按宿主、入口、安全和后端现场证据判定，不按版本号判定。
add_library(launcher_probe_contract OBJECT
    ../../test/cpp/launcher_probe_test.cpp
    ../../test/cpp/launcher_config_test.cpp
    ../../test/cpp/launcher_host_identity_test.cpp
    ../../test/cpp/launcher_install_permit_test.cpp
    ../../test/cpp/launcher_safety_gate_test.cpp
    ../../test/cpp/launcher_snapshot_wire_test.cpp
    ../../test/cpp/launcher_channel_test.cpp
    ../../test/cpp/launcher_diagnostic_probe_test.cpp
    ../../test/cpp/launcher_install_transaction_test.cpp
    ../../test/cpp/launcher_shortcut_count_test.cpp
    ../../test/cpp/launcher_island_relocation_test.cpp
    ../../test/cpp/launcher_count_backend_test.cpp
    ../../test/cpp/launcher_singleword_publish_test.cpp
    ../../test/cpp/launcher_dart_string_test.cpp
    ../../test/cpp/launcher_share_wire_test.cpp)
target_compile_features(launcher_probe_contract PRIVATE cxx_std_17)
target_compile_options(launcher_probe_contract PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)
target_include_directories(launcher_probe_contract PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
add_dependencies(HyperExtendLauncherNative launcher_probe_contract)

# 额外保留独立合同对象，供离线合同目标继续编译；生产库同时链接上面的真实后端。
add_library(launcher_backend_contract OBJECT launcher/count_backend.cpp)
target_compile_features(launcher_backend_contract PRIVATE cxx_std_17)
target_compile_options(launcher_backend_contract PRIVATE
    -Wall -Wextra -Werror -fno-exceptions -fno-rtti -fvisibility=hidden -mno-outline-atomics)
target_include_directories(launcher_backend_contract PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
add_dependencies(HyperExtendLauncherNative launcher_backend_contract)
