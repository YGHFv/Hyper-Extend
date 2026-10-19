/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逃生门（`KillSwitch` / `BootLoopGuard`）的常量与 fail-closed 分支单测。
 *
 * 这里钉死的是**常量字面量**与**读不到属性时的兜底**两件事：
 *
 * - 常量决定恢复手册里那条 `setprop` 命令能不能真正解掉保护，改错一个字就会让用户在
 *   开机循环里怎么设都没反应，所以必须逐字断言；
 * - 属性读取在普通 JVM 单测里必然失败（没有 `android.os.SystemProperties`），
 *   这正是我们要断言的**失败方向**：KillSwitch 必须返回「已触发」（true，什么都不要装），
 *   BootLoopGuard 必须返回 null（调用方据此跳过 hook）。注意这是 fail-closed 分支的语义，
 *   不是实机属性读取的等价验证。
 *
 * 生产类里的属性/状态函数是 private，因此用反射读取常量、调用读取函数；不触碰
 * `shouldSkipSystemServerHooks` / `shouldSkipSystemUiHooks`，那两条会在真实路径上创建
 * `/data/system` 之类的目录，属于单测不该产生的文件系统副作用。
 */
class EscapeGateTest {

    @Test fun killSwitchPropertyAndFlagsAreLiteral() {
        val propertyName = stringField(KillSwitch::class.java, "PROPERTY_NAME")
        val flagFiles = stringListField(KillSwitch::class.java, "FLAG_FILES")
        assertEquals("persist.sys.hyperextend.disabled", propertyName)
        assertEquals(listOf("/data/local/tmp/hyperextend.disabled", "/sdcard/HyperExtend/disable"), flagFiles)
    }

    @Test fun killSwitchFailsClosedWhenPropertyUnreadable() {
        // 单测 JVM 里没有 android.os.SystemProperties，属性读取必然失败；
        // 两个入口都必须落到「已触发」（true = 本进程什么都不要装）。
        assertEquals(true, KillSwitch.isEngaged())
        assertEquals(true, KillSwitch.reportOnce())
        assertEquals("persist.sys.hyperextend.disabled", stringField(KillSwitch::class.java, "PROPERTY_NAME"))
    }

    @Test fun bootLoopGuardStateAndPropertyAreLiteralAndDistinct() {
        val stateFile = stringField(BootLoopGuard::class.java, "STATE_FILE")
        val disabledProperty = stringField(BootLoopGuard::class.java, "DISABLED_PROPERTY")
        val killProperty = stringField(KillSwitch::class.java, "PROPERTY_NAME")
        assertEquals("/data/system/hyperextend_bootguard", stateFile)
        assertEquals("persist.sys.hyperextend.framework_disabled", disabledProperty)
        // 两条属性是两个不同的标记：清掉一条不能顺带清掉另一条。
        assertFalse(disabledProperty == killProperty)
        assertFalse(disabledProperty.startsWith(killProperty))
        assertFalse(killProperty.startsWith(disabledProperty))
    }

    @Test fun bootLoopGuardPropertyReadFailsClosedWithNull() {
        // readDisabledProperty() 是 private 的真实现，这里经反射桥接调用：
        // 读不到属性时必须返回 null（调用方判断「非 false 即跳过」）。
        assertEquals(null, readDisabledProperty())
        assertEquals("persist.sys.hyperextend.framework_disabled", stringField(BootLoopGuard::class.java, "DISABLED_PROPERTY"))
    }

    /** 反射桥：直接调用生产实现 `BootLoopGuard.readDisabledProperty()`，不改写其语义。 */
    private fun readDisabledProperty(): Boolean? {
        val method = BootLoopGuard::class.java.getDeclaredMethod("readDisabledProperty")
        method.isAccessible = true
        return method.invoke(BootLoopGuard) as Boolean?
    }

    private fun stringField(owner: Class<*>, name: String): String {
        val value = fieldValue(owner, name)
        assertNotNull(value)
        return value as String
    }

    private fun stringListField(owner: Class<*>, name: String): List<String> {
        val value = fieldValue(owner, name)
        assertNotNull(value)
        @Suppress("UNCHECKED_CAST")
        return (value as List<*>).map { it as String }
    }

    /** Kotlin 单例的 `const val` 编译成静态字段，普通 `val` 编译成对象实例字段。 */
    private fun fieldValue(owner: Class<*>, name: String): Any? {
        val field = owner.getDeclaredField(name)
        field.isAccessible = true
        val target = if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else objectInstance(owner)
        return field.get(target)
    }

    private fun objectInstance(owner: Class<*>): Any {
        val instance = owner.getDeclaredField("INSTANCE")
        instance.isAccessible = true
        return checkNotNull(instance.get(null)) { "Kotlin 单例缺少 INSTANCE 字段" }
    }
}
