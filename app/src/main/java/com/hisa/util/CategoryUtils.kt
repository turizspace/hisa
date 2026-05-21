package com.hisa.util

private val categoryAliases = mapOf(
    // Food & Beverage
    "food" to "food",
    "restaurant" to "food",
    "meal" to "food",
    "meals" to "food",
    "dining" to "food",
    "cafe" to "food",
    "coffee" to "food",
    "pizza" to "food",
    "baked" to "food",
    "sourdough" to "food",
    "drinks" to "food",
    "drinkstr" to "food",
    "kitchen" to "food",
    "menu" to "food",
    "ingredients" to "food",
    "beverage" to "food",
    "catering" to "food",
    "gluten" to "food",
    "glutenfreediet" to "food",
    "vegan" to "food",
    "vegandiet" to "food",
    "vegetarian" to "food",
    "vegetariandiet" to "food",
    "whiskey" to "food",
    "drink" to "food",

    // Tech & Development
    "tech" to "tech",
    "computer" to "tech",
    "electronics" to "tech",
    "software" to "tech",
    "it" to "tech",
    "coding" to "tech",
    "code" to "tech",
    "dev" to "tech",
    "developer" to "tech",
    "devtools" to "tech",
    "api" to "tech",
    "terminal" to "tech",
    "tools" to "tech",
    "automation" to "tech",
    "bot" to "tech",
    "agents" to "tech",
    "agent" to "tech",
    "ai" to "tech",
    "llm" to "tech",
    "search" to "tech",
    "contract" to "tech",
    "prompt" to "tech",
    "template" to "tech",
    "templates" to "tech",
    "codereview" to "tech",
    "codeforge" to "tech",
    "codex" to "tech",
    "hardware" to "tech",
    "antminer" to "tech",
    "s9" to "tech",
    "device" to "tech",
    "screen" to "tech",
    "deck" to "tech",
    "flask" to "tech",
    "ollama" to "tech",
    "mcp" to "tech",

    // Fashion & Apparel
    "fashion" to "fashion",
    "clothing" to "fashion",
    "apparel" to "fashion",
    "accessories" to "fashion",
    "jersey" to "fashion",
    "vintage" to "fashion",

    // Art, Creative & Design
    "art" to "design",
    "design" to "design",
    "creative" to "design",
    "decor" to "design",
    "photography" to "design",
    "photo" to "design",
    "gallery" to "design",
    "collectible" to "design",
    "collectibles" to "design",
    "collector" to "design",
    "painting" to "design",
    "canvas" to "design",
    "image" to "design",
    "haiku" to "design",
    "poetry" to "design",
    "writing" to "design",
    "books" to "design",
    "story" to "design",
    "video" to "design",
    "art" to "design",

    // Delivery & Logistics
    "delivery" to "delivery",
    "courier" to "delivery",
    "shipping" to "delivery",
    "logistics" to "delivery",
    "transport" to "delivery",
    "car" to "delivery",
    "taxi" to "delivery",
    "ride" to "delivery",
    "rideshare" to "delivery",
    "moving" to "delivery",

    // Marketplace & Commerce
    "shop" to "shop",
    "store" to "shop",
    "market" to "shop",
    "storefront" to "shop",
    "retail" to "shop",
    "listing" to "shop",
    "product" to "shop",
    "products" to "shop",
    "stall" to "shop",
    "catalog" to "shop",
    "marketplace" to "shop",
    "checkout" to "shop",
    "plebeianmarket" to "shop",
    "shopstr" to "shop",
    "shopify" to "shop",
    "goods" to "shop",
    "offer" to "shop",
    "buyer" to "shop",
    "seller" to "shop",
    "vendor" to "shop",
    "inventory" to "shop",
    "garagesale" to "shop",
    "yardsale" to "shop",
    "resale" to "shop",

    // Services & Support
    "service" to "services",
    "services" to "services",
    "repair" to "services",
    "fix" to "services",
    "fixing" to "services",
    "handyman" to "services",
    "handyman-services" to "services",
    "freelance" to "services",
    "consulting" to "services",
    "employment" to "services",
    "support" to "services",
    "mentor" to "services",
    "handcraft" to "services",
    "handmade" to "services",
    "custom" to "services",
    "consulting" to "services",
    "engineering" to "services",
    "contractor" to "services",

    // Cleaning & Maintenance
    "cleaning" to "cleaning",
    "cleaner" to "cleaning",
    "housecleaning" to "cleaning",
    "maid" to "cleaning",

    // Farming & Agriculture
    "farming" to "farming",
    "agriculture" to "farming",
    "farm" to "farming",
    "produce" to "farming",
    "vegetables" to "farming",
    "regenerativeagriculture" to "farming",

    // Beauty & Grooming
    "beauty" to "beauty",
    "salon" to "beauty",
    "hair" to "beauty",
    "grooming" to "beauty",
    "cosmetics" to "beauty",

    // Health, Fitness & Wellness
    "health" to "health",
    "fitness" to "health",
    "wellness" to "health",
    "petcare" to "health",
    "pets" to "health",
    "petsitter" to "health",

    // Plumbing & Home Services
    "plumber" to "plumbing",
    "pipes" to "plumbing",
    "pipe-fixing" to "plumbing",
    "fundi" to "plumbing",
    "plumbing" to "plumbing",

    // Electrical & Power
    "electric" to "electrician",
    "electrical" to "electrician",
    "electrical-services" to "electrician",
    "electrician" to "electrician",
    "electrics" to "electrician",

    // Bitcoin, Lightning & Crypto
    "bitcoin" to "bitcoin",
    "lightning" to "bitcoin",
    "sats" to "bitcoin",
    "satoshi" to "bitcoin",
    "cashu" to "bitcoin",
    "nostr" to "bitcoin",
    "plebchain" to "bitcoin",
    "stackernews" to "bitcoin",
    "nip" to "bitcoin",
    "nip99" to "bitcoin",
    "l402" to "bitcoin",
    "zap" to "bitcoin",
    "v4v" to "bitcoin",
    "usdc" to "bitcoin",
    "usdt" to "bitcoin",
    "usd" to "bitcoin",
    "eur" to "bitcoin",

    // Entertainment & Gaming
    "game" to "gaming",
    "gaming" to "gaming",
    "sports" to "gaming",
    "skateboarding" to "gaming",
    "skateshop" to "gaming",
    "music" to "gaming",
    "entertainment" to "gaming",

    // Home & Furniture
    "furniture" to "home",
    "home" to "home",
    "bedroom" to "home",
    "office" to "home",
    "wall" to "home",
    "bath" to "home",
    "lighting" to "home",
    "decor" to "home",

    // Miscellaneous/Other
    "other" to "other",
    "misc" to "other",
    "test" to "other",
    "demo" to "other",
    "3d" to "other",
    "agent" to "other",
    "agora" to "other",
    "automation" to "other",
    "autonomy" to "other"
)

/**
 * Normalize user-supplied category or topic tags into canonical category keys.
 */
fun normalizeCategory(tag: String): String {
    val key = tag.trim().lowercase()
    if (key.isBlank()) return ""

    val normalizedKey = key
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    val firstToken = normalizedKey.split(Regex("\\s+")).firstOrNull().orEmpty()
    return categoryAliases[normalizedKey]
        ?: categoryAliases[firstToken]
        ?: firstToken
}

/**
 * Convert a canonical category key into a human-friendly display label.
 */
fun humanizeCategoryLabel(categoryKey: String): String {
    return categoryKey
        .trim()
        .replace('-', ' ')
        .replace('_', ' ')
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        .ifBlank { categoryKey }
}
