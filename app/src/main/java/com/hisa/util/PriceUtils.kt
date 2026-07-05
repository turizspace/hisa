package com.hisa.util

import com.hisa.data.model.ServiceListing

fun formatServicePrice(service: ServiceListing): String? {
    val priceTag = service.rawTags.firstOrNull { it.size > 1 && it[0] == "price" }
    val priceValue = priceTag?.getOrNull(1) as? String ?: service.price
    val priceCurrency = (priceTag?.getOrNull(2) as? String)?.uppercase() ?: "SATS"

    return when {
        priceValue.isBlank() || priceValue.equals("N/A", true) -> null
        priceValue == "0" || priceValue.equals("free", true) || priceValue.equals("open", true) -> "Free"
        priceValue.lowercase().contains("sat") -> priceValue
        priceCurrency == "USD" -> "$$priceValue"
        priceCurrency == "SATS" || priceCurrency.isBlank() -> {
            if (priceValue.all { it.isDigit() }) {
                val amount = priceValue.toLongOrNull()
                when {
                    amount == null -> priceValue
                    amount < 1000 -> "${amount} sats"
                    amount < 1000000 -> String.format("%.1fK sats", amount / 1000.0)
                    else -> String.format("%.1fM sats", amount / 1000000.0)
                }
            } else {
                priceValue
            }
        }
        else -> "$priceValue $priceCurrency"
    }
}

fun formatServicePrice(priceValue: String, priceCurrency: String): String {
    val normalizedValue = priceValue.trim()
    return when {
        normalizedValue.isBlank() || normalizedValue.equals("N/A", ignoreCase = true) -> "N/A"
        normalizedValue == "0" || normalizedValue.lowercase() == "free" || normalizedValue.lowercase() == "open" -> "Free"
        normalizedValue.lowercase().contains("sat") -> normalizedValue
        priceCurrency.lowercase() == "usd" -> "$${normalizedValue}"
        priceCurrency.lowercase() == "sats" || priceCurrency.isBlank() -> {
            if (normalizedValue.all { it.isDigit() }) {
                val amount = normalizedValue.toLongOrNull()
                when {
                    amount == null -> normalizedValue
                    amount < 1000 -> "${amount} sats"
                    amount < 1000000 -> String.format("%.1fK sats", amount / 1000.0)
                    else -> String.format("%.1fM sats", amount / 1000000.0)
                }
            } else {
                normalizedValue
            }
        }
        else -> "$normalizedValue $priceCurrency"
    }
}
