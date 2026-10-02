package io.github.YGHFv.HyperExtend.core

import org.json.JSONObject

object CardFaceMappings {
    fun read(value: String): Map<String, String> = runCatching {
        if (value.isBlank()) return@runCatching emptyMap()
        require(value.length <= 128 * 1024)
        val json = JSONObject(value)
        require(json.length() <= 64)
        buildMap {
            for (address in json.keys()) {
                val replacement = json.optString(address)
                if (WalletCardFaceCapture.isSupportedAddress(address) &&
                    replacement.startsWith("content://${NfcCardImage.AUTHORITY}/card/")) put(address, replacement)
            }
        }
    }.getOrDefault(emptyMap())

    fun update(value: String, address: String, replacement: String?): String {
        val mappings = read(value).toMutableMap()
        if (replacement == null) mappings.remove(address) else mappings[address] = replacement
        require(mappings.size <= 64)
        return if (mappings.isEmpty()) "" else JSONObject(mappings as Map<*, *>).toString()
    }
}
