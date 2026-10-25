/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.settings

/** 自动锁屏额外提供的时间档位。 */
internal data class AdditionalScreenTimeout(
    val millis: Int,
    val stringResource: String,
    val fallbackLabel: String,
)

internal val ADDITIONAL_SCREEN_TIMEOUTS = listOf(
    AdditionalScreenTimeout(
        millis = 20 * 60 * 1000,
        stringResource = "settings_screen_timeout_20_minutes",
        fallbackLabel = "20 分钟",
    ),
    AdditionalScreenTimeout(
        millis = 30 * 60 * 1000,
        stringResource = "settings_screen_timeout_30_minutes",
        fallbackLabel = "30 分钟",
    ),
    AdditionalScreenTimeout(
        millis = 60 * 60 * 1000,
        stringResource = "settings_screen_timeout_60_minutes",
        fallbackLabel = "60 分钟",
    ),
    AdditionalScreenTimeout(
        millis = Int.MAX_VALUE,
        stringResource = "settings_screen_timeout_never",
        fallbackLabel = "永不",
    ),
)

/**
 * 在宿主原生档位中补齐额外时间。
 *
 * 保留原生条目和重复项，按毫秒值升序排列；已存在的同值档位不覆盖。解析失败时返回 null，
 * 调用方保持宿主原样，避免为了一组额外选项破坏设备策略或原生值。
 */
internal fun mergeScreenTimeoutOptions(
    entries: List<CharSequence>,
    values: List<CharSequence>,
    additionalLabels: Map<Long, CharSequence>,
): List<Pair<Long, CharSequence>>? {
    if (entries.size != values.size) return null
    val merged = ArrayList<Pair<Long, CharSequence>>(entries.size + additionalLabels.size)
    for (index in values.indices) {
        val millis = values[index].toString().toLongOrNull() ?: return null
        merged += millis to entries[index]
    }
    val existing = merged.mapTo(HashSet()) { it.first }
    for ((millis, label) in additionalLabels) {
        if (millis !in existing) merged += millis to label
    }
    return merged.sortedBy { it.first }
}
