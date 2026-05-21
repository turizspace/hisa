package com.hisa.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Agriculture
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Plumbing
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.hisa.util.humanizeCategoryLabel
import com.hisa.util.normalizeCategory

/**
 * UI-aware category metadata used to render a semantic icon + label mapping.
 */
data class CategoryUi(
    val label: String,
    val icon: ImageVector,
    val color: Color
)

private val defaultCategoryUi = CategoryUi(
    label = "Other",
    icon = Icons.Rounded.Tag,
    color = Color(0xFF607D8B)
)

private val categoryRegistry = mapOf(
    // Services
    "plumbing" to CategoryUi(
        label = "Plumbing",
        icon = Icons.Rounded.Plumbing,
        color = Color(0xFF6750A4)
    ),
    "electrician" to CategoryUi(
        label = "Electrician",
        icon = Icons.Rounded.ElectricalServices,
        color = Color(0xFFF9A825)
    ),
    "services" to CategoryUi(
        label = "Services",
        icon = Icons.Rounded.Work,
        color = Color(0xFF795548)
    ),
    "repair" to CategoryUi(
        label = "Repair",
        icon = Icons.Rounded.Build,
        color = Color(0xFF795548)
    ),
    // Food & Beverage
    "food" to CategoryUi(
        label = "Food",
        icon = Icons.Rounded.Restaurant,
        color = Color(0xFFD84315)
    ),
    // Transport & Delivery
    "delivery" to CategoryUi(
        label = "Delivery",
        icon = Icons.Rounded.LocalShipping,
        color = Color(0xFF009688)
    ),
    // Marketplace & Commerce
    "shop" to CategoryUi(
        label = "Shop",
        icon = Icons.Rounded.Storefront,
        color = Color(0xFF6D4C41)
    ),
    // Art, Creative & Design
    "design" to CategoryUi(
        label = "Design",
        icon = Icons.Rounded.Palette,
        color = Color(0xFF6A1B9A)
    ),
    // Cleaning & Maintenance
    "cleaning" to CategoryUi(
        label = "Cleaning",
        icon = Icons.Rounded.CleaningServices,
        color = Color(0xFF00796B)
    ),
    // Farming & Agriculture
    "farming" to CategoryUi(
        label = "Farming",
        icon = Icons.Rounded.Agriculture,
        color = Color(0xFF2E7D32)
    ),
    // Beauty & Grooming
    "beauty" to CategoryUi(
        label = "Beauty",
        icon = Icons.Rounded.ContentCut,
        color = Color(0xFFAD1457)
    ),
    // Tech & Development
    "tech" to CategoryUi(
        label = "Tech",
        icon = Icons.Rounded.Computer,
        color = Color(0xFF1E88E5)
    ),
    // Fashion & Apparel
    "fashion" to CategoryUi(
        label = "Fashion",
        icon = Icons.Rounded.Checkroom,
        color = Color(0xFF7B1FA2)
    ),
    // Entertainment & Gaming
    "gaming" to CategoryUi(
        label = "Gaming",
        icon = Icons.Rounded.SportsEsports,
        color = Color(0xFFC41C3B)
    ),
    // Health & Fitness
    "health" to CategoryUi(
        label = "Health",
        icon = Icons.Rounded.FitnessCenter,
        color = Color(0xFF00695C)
    ),
    // Bitcoin & Crypto
    "bitcoin" to CategoryUi(
        label = "Bitcoin",
        icon = Icons.Rounded.CurrencyBitcoin,
        color = Color(0xFFF7931A)
    ),
    // Home & Furniture
    "home" to CategoryUi(
        label = "Home",
        icon = Icons.Rounded.Home,
        color = Color(0xFF558B2F)
    ),
    // Miscellaneous
    "other" to CategoryUi(
        label = "Other",
        icon = Icons.Rounded.MoreHoriz,
        color = Color(0xFF607D8B)
    )
)

/**
 * Return the UI metadata for a raw tag value.
 */
fun categoryUiFor(tag: String): CategoryUi {
    val normalized = normalizeCategory(tag)
    return categoryRegistry[normalized]?.copy(label = categoryRegistry[normalized]?.label ?: humanizeCategoryLabel(normalized))
        ?: defaultCategoryUi.copy(label = humanizeCategoryLabel(normalized.ifBlank { tag }))
}

/**
 * Return UI metadata for a normalized category key.
 */
fun categoryUiForCategoryKey(categoryKey: String): CategoryUi {
    val normalized = normalizeCategory(categoryKey)
    return categoryRegistry[normalized] ?: defaultCategoryUi.copy(label = humanizeCategoryLabel(normalized))
}

@Composable
fun CategoryAssistChip(
    category: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val categoryUi = categoryUiFor(category)

    AssistChip(
        onClick = onClick,
        label = { Text(categoryUi.label) },
        leadingIcon = { Icon(categoryUi.icon, contentDescription = null) },
        modifier = modifier,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = categoryUi.color.copy(alpha = 0.14f),
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = categoryUi.color
        )
    )
}
