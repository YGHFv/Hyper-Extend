/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <stdint.h>

// 桌面侧的 Binder NDK 调用约定。
//
// 这里**不包含** <android/binder_*.h>：那些头把全部 API 标成 `__INTRODUCED_IN(29)`，
// 而本库的 minSdk 是 26。改成全部经 dlsym 解析，并自带最小类型声明。
// 映射存在不代表符号对当前命名空间可见，解析失败必须拒绝，不能断言一定可用。
// 好处还有两条：
//   1. 原生库不新增任何 DT_NEEDED 依赖，只在运行期按需解析；
//   2. 判定逻辑仍然可以在编译期断言，不需要设备。
//
// 这些声明必须与 libbinder_ndk.so 导出的 C ABI 逐字一致；下面的静态断言
// 只钉住我们实际用到的那几个。
namespace launcher::binder {

// libbinder_ndk 里 AIBinder / AParcel 都是不透明类型，我们只持有指针。
struct AIBinder;
struct AParcel;
struct AIBinder_Class;

// binder_status_t 使用 32 位状态值；仅声明本库实际需要的常量。
using status_t = int32_t;
constexpr status_t kStatusOk = 0;
// STATUS_UNKNOWN_TRANSACTION = -EBADMSG = -74。用于「本进程只发事务、从不应答」
// 这件事在回调里的诚实返回值（见 native_entry.cpp 的 binder_on_transact）。
constexpr status_t kStatusUnknownTransaction = -74;

// transaction code 在 NDK 里是 uint32_t。
using transaction_t = uint32_t;

using CheckService = AIBinder *(*)(const char *name);
using PrepareTransaction = status_t (*)(AIBinder *binder, AParcel **input);
using Transact = status_t (*)(AIBinder *binder, transaction_t code, AParcel **input,
    AParcel **output, uint32_t flags);
using ReadInt32 = status_t (*)(const AParcel *parcel, int32_t *value);
using WriteInt32 = status_t (*)(AParcel *parcel, int32_t value);
using WriteString = status_t (*)(AParcel *parcel, const char *string, int32_t length);
using ParcelDelete = void (*)(AParcel *parcel);
using DecStrong = void (*)(AIBinder *binder);

// 下面这一组只用于补齐一类前提：从服务管理器取回的 `window` 句柄是一个
// **远端 BpBinder**，它在 NDK 侧没有关联的类（`AIBinder_getClass` 对它返回 null），
// 而 `AIBinder_prepareTransaction` 明确要求句柄必须已关联类——
// 未关联时它会直接返回 STATUS_INVALID_OPERATION，事务根本发不出去。
// 所以必须自己按目标接口描述符定义一个类并关联上去；`prepareTransaction`
// 随后就从该描述符写出 interface token，服务端 `enforceInterface` 才能通过。
using GetClass = const AIBinder_Class *(*)(AIBinder *binder);
using ClassGetDescriptor = const char *(*)(const AIBinder_Class *clazz);
using ClassDefine = AIBinder_Class *(*)(const char *interfaceDescriptor, void *(*onCreate)(void *),
    void (*onDestroy)(void *), status_t (*onTransact)(AIBinder *, transaction_t,
        const AParcel *, AParcel *));
using AssociateClass = bool (*)(AIBinder *binder, const AIBinder_Class *clazz);

// 符号名按 AOSP 的 C ABI 固定；当前进程可见性必须逐个检查，不能由设备版本外推。
// 不等待服务注册；服务管理器调用本身仍是同步 IPC，不承诺硬超时。
constexpr char kSymbolCheckService[] = "AServiceManager_checkService";
constexpr char kSymbolPrepareTransaction[] = "AIBinder_prepareTransaction";
constexpr char kSymbolTransact[] = "AIBinder_transact";
constexpr char kSymbolReadInt32[] = "AParcel_readInt32";
constexpr char kSymbolWriteInt32[] = "AParcel_writeInt32";
constexpr char kSymbolWriteString[] = "AParcel_writeString";
constexpr char kSymbolParcelDelete[] = "AParcel_delete";
constexpr char kSymbolDecStrong[] = "AIBinder_decStrong";
constexpr char kSymbolGetClass[] = "AIBinder_getClass";
constexpr char kSymbolClassGetDescriptor[] = "AIBinder_Class_getDescriptor";
constexpr char kSymbolClassDefine[] = "AIBinder_Class_define";
constexpr char kSymbolAssociateClass[] = "AIBinder_associateClass";

// 目标接口描述符。必须与宿主 `IWindowManager` 的 DESCRIPTOR 逐字一致：
// 它既被用来比对句柄已关联的类，也被写进事务的 interface token。
constexpr char kWindowManagerDescriptor[] = "android.view.IWindowManager";

static_assert(sizeof(CheckService) == sizeof(void *), "函数指针必须是单字宽");
static_assert(sizeof(WriteInt32) == sizeof(void *), "写整数 ABI 必须是单字宽");
static_assert(sizeof(WriteString) == sizeof(void *), "写字符串 ABI 必须是单字宽");
static_assert(kStatusOk == 0, "STATUS_OK 必须为 0");

}  // 命名空间 launcher::binder
