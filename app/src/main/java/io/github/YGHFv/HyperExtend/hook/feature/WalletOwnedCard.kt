package io.github.YGHFv.HyperExtend.hook.feature

internal object WalletOwnedCard {
    fun imageAddress(card: Any?): String? = runCatching {
        if (card == null) return@runCatching null
        val transit = card.javaClass.methods.firstOrNull {
            it.name == "isTransCard" && it.parameterCount == 0
        }?.invoke(card) == true
        if (transit) {
            var type: Class<*>? = card.javaClass
            while (type != null) {
                val field = type.declaredFields.firstOrNull { it.name == "mCardUIInfo" }
                if (field != null) {
                    val cardUi = field.also { it.isAccessible = true }.get(card) ?: return@runCatching null
                    return@runCatching cardUi.javaClass.getMethod("getBackground").invoke(cardUi) as? String
                }
                type = type.superclass
            }
            return@runCatching null
        }
        val imageMethod = card.javaClass.methods.firstOrNull {
            it.name == "getImageUrl" && it.parameterCount == 0 && it.returnType == String::class.java
        }
        val image = imageMethod?.invoke(card) as? String
        if (!image.isNullOrBlank()) return@runCatching image
        var type: Class<*>? = card.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == "mCardArt" && it.type == String::class.java }
            val address = field?.also { it.isAccessible = true }?.get(card) as? String
            if (!address.isNullOrBlank()) return@runCatching resolveAddress(address)
            type = type.superclass
        }
        null
    }.getOrNull()

    fun resolveAddress(address: String): String {
        if (address.startsWith("/") || address.contains("://")) return address
        return "https://did-cdn.pay.xiaomi.com/mfc/download/$address"
    }

    fun accepts(card: Any?): Boolean = runCatching {
        if (card == null) return@runCatching false
        fun flag(name: String): Boolean? = card.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 0
        }?.invoke(card) as? Boolean
        fun field(name: String): Any? {
            var type: Class<*>? = card.javaClass
            while (type != null) {
                val found = type.declaredFields.firstOrNull { it.name == name }
                if (found != null) return found.also { it.isAccessible = true }.get(card)
                type = type.superclass
            }
            return null
        }
        when {
            flag("isTransCard") == true -> field("mHasIssue") == true
            flag("isMiFareCard") == true -> flag("isDummy") == false
            flag("isBankCard") == true -> (field("mVCardNumber") as? String)?.isNotBlank() == true
            else -> false
        }
    }.getOrDefault(false)
}
