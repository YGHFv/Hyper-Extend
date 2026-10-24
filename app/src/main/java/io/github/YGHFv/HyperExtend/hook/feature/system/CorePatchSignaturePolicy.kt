/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Capability 白名单与安装链路门禁参考 HyperCeiler DigestCreakPatch
 * (Copyright (C) 2023-2026 HyperCeiler Contributions, AGPL-3.0)。
 */
package io.github.YGHFv.HyperExtend.hook.feature.system

/**
 * 「允许覆盖安装不同签名」子项的判定逻辑。
 *
 * ## 为什么必须存在
 *
 * 覆盖安装同包名但不同签名的应用时，宿主在
 * `com.android.server.pm.PackageManagerServiceUtils#verifySignatures` 里调用
 * `android.content.pm.SigningDetails#checkCapability(SigningDetails, int)` 判定
 * 「新包能不能继承已安装包的能力」。判定为否就抛
 * `PackageManagerException(-7, "Existing package … signatures do not match newer version; ignoring!")`。
 * 只要不放宽这一处，核心破解其余的靶点（签名方案版本、jar 摘要、分包精确匹配）
 * 全都拦不到这个异常。
 *
 * ## 为什么只能放宽一部分能力
 *
 * `checkCapability` 的第二个参数是 `@CertCapabilities`，它同时被**运行时权限**复用：
 * `InstallPackageHelper#doesSignatureMatchForPermissions` 就用 `PERMISSION` 询问
 * 「新包是否延续旧包的签名权限」。伪造它等于让任意应用接走别人的
 * `signature|privileged` 权限；伪造 `AUTH` 则能冒充他人的账户签名。
 * 因此 `4`（PERMISSION）与 `16`（AUTH）**永不放宽**，本模块只伪造
 * 覆盖安装真正需要的 `1`（INSTALLED_DATA）与 `8`（ROLLBACK）。`2`（SHARED_USER_ID）
 * 由「共享 UID 签名兼容」子项单独决定 —— 上游把 1/2/8 一起放开，但让不同签名的包
 * 落进共享 UID（可能是 system 级）比覆盖安装高一档风险，不该被顺带打开。
 *
 * ## 为什么还要看调用栈
 *
 * 这些能力也会被安装之外的路径查询：`PackageManagerService#hasSignatureCapability`
 * 供 rollback 与权限服务随时询问、`InstantAppRegistry` 用另一个重载校验免安装应用
 * 的 cookie。只在包管理的安装入口内伪造，其余场景一律走宿主原始判定，
 * 避免把「签名比对」整体变成常量真。
 *
 * ## 栈门禁挡不住什么（必须如实知道）
 *
 * 宿主开机扫描同样复用这条管线：`reconcileInstallPackages → reconcilePackages →
 * verifySignatures`。因此开启本项后，开机时签名与记录不符的系统包也会被放行并更新
 * 记录 —— 这与上游 DigestCreakPatch 的行为一致（它完全不看栈，影响面比这里更大），
 * 但它**不是**「只作用于用户主动安装」。
 *
 * `PackageSessionVerifier#checkApexSignature` 也问同样的能力，但它由 Handler 异步
 * 投递、栈里没有安装帧，所以 APEX 容器签名仍然按原生判定（-22 不会被绕过）。
 */
internal object CorePatchSignaturePolicy {
    /** 新包可以继承已安装包的数据目录。 */
    const val CAP_INSTALLED_DATA = 1

    /** 可以加入同一个 sharedUserId。 */
    const val CAP_SHARED_USER_ID = 2

    /** 延续旧包的签名权限 —— 绝不能伪造。 */
    const val CAP_PERMISSION = 4

    /** 回滚代理归属。 */
    const val CAP_ROLLBACK = 8

    /** 账户签名互通 —— 绝不能伪造。 */
    const val CAP_AUTH = 16

    /** 覆盖安装必需的能力：继承数据目录与回滚归属。 */
    private val OVERRIDE_CAPABILITIES = setOf(CAP_INSTALLED_DATA, CAP_ROLLBACK)

    /** 只有「共享 UID 签名兼容」开启时才额外放宽的能力。 */
    private val SHARED_USER_CAPABILITIES = setOf(CAP_SHARED_USER_ID)

    /** 安装链路的方法名。宿主可能给它们加合成后缀（`preparePackage$1`、`lambda$verifySignatures$0`），所以按前缀匹配。 */
    private val INSTALL_FRAMES = listOf(
        "verifySignatures",
        "matchSignatureInSystem",
        "canJoinSharedUserId",
        "preparePackage",
    )

    /**
     * 该能力是否落在允许伪造的白名单内。
     *
     * `2`（SHARED_USER_ID）单独由「共享 UID 签名兼容」子项决定：上游把 1/2/8 一起放开，
     * 但让不同签名的包落进共享 UID（可能是 system 级）比覆盖安装高一档风险，
     * 不该被「允许覆盖安装不同签名」顺带打开。
     */
    fun forgesCapability(
        capability: Int?,
        overrideEnabled: Boolean,
        sharedUserEnabled: Boolean,
    ): Boolean = capability != null && (
        (overrideEnabled && capability in OVERRIDE_CAPABILITIES) ||
            (sharedUserEnabled && capability in SHARED_USER_CAPABILITIES)
        )

    /** 当前调用是否来自包管理的安装链路。 */
    fun inInstallPath(frameNames: Set<String>): Boolean = frameNames.any { frame ->
        INSTALL_FRAMES.any { base ->
            // 本体 `verifySignatures`；合成/内部类后缀 `verifySignatures$1`；
            // javac lambda 与 access 桥接 `lambda$verifySignatures$0`、`access$verifySignatures`。
            frame == base ||
                (frame.length > base.length && frame.startsWith(base) && frame[base.length] == '$') ||
                frame.contains('$' + base)
        }
    }

    /** 从真实栈帧取方法名集合。 */
    fun frameNames(stack: Array<StackTraceElement>): Set<String> =
        stack.mapTo(HashSet(stack.size)) { it.methodName }
}
