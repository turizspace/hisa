package com.hisa.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import com.hisa.util.normalizeCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hisa.data.model.Product
import org.json.JSONArray

/**
 * Displays a single product card for a stall.
 * Shows: product image(s), name, description, price, categories.
 */
@Composable
fun ProductCard(product: Product, modifier: Modifier = Modifier, onAddToCart: (() -> Unit)? = null) {
    val normalizedPictures = remember(product.pictures) {
        product.pictures.mapNotNull(::normalizeProductImageUrl).distinct()
    }

    ElevatedCard(modifier = modifier.padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Product images carousel (if available)
            if (normalizedPictures.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(MaterialTheme.shapes.medium),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(normalizedPictures) { imageUrl ->
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = "Product image: ${product.name}",
                            modifier = Modifier
                                .width(120.dp)
                                .height(120.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            } else {
                // Placeholder when no images
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(MaterialTheme.shapes.medium),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        Icons.Default.ShoppingCart,
                        contentDescription = "No product image",
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            // Product name
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Product description
            if (product.description.isNotEmpty()) {
                Text(
                    text = product.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Price + Quantity + Categories Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Price and currency
                Text(
                    text = "${product.price} ${product.currency}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )

                // Quantity (if specified)
                if (product.quantity != null && product.quantity!! > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Qty: ${product.quantity}") },
                        modifier = Modifier.heightIn(max = 28.dp)
                    )
                }
            }

            // Categories
            val normalizedCategories = remember(product.categories) {
                product.categories
                    .map(::normalizeCategory)
                    .distinct()
            }
            if (normalizedCategories.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(normalizedCategories) { category ->
                        CategoryAssistChip(
                            category = category,
                            modifier = Modifier.heightIn(max = 24.dp)
                        )
                    }
                }
            }

            // Add to cart button (optional)
            if (onAddToCart != null) {
                Button(
                    onClick = onAddToCart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text("Add to Cart")
                }
            }
        }
    }
}

private fun normalizeProductImageUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    if (trimmed.startsWith("[")) {
        val firstFromArray = runCatching {
            JSONArray(trimmed).let { arr ->
                (0 until arr.length())
                    .mapNotNull { index -> arr.optString(index).trim().takeIf { it.isNotBlank() } }
                    .firstOrNull()
            }
        }.getOrNull()
        if (!firstFromArray.isNullOrBlank()) return upgradeProductImageUrl(firstFromArray)
    }
    val candidate = trimmed
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.isNotBlank() }
        ?: return null

    return upgradeProductImageUrl(candidate)
}

private fun upgradeProductImageUrl(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) {
        "https://${url.substringAfter("://")}"
    } else {
        url
    }

/**
 * Displays a list of products in a horizontal scrolling row.
 */
@Composable
fun ProductsCarousel(products: List<Product>, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        items(products) { product ->
            ProductCard(
                product = product,
                modifier = Modifier.width(200.dp)
            )
        }
    }
}

/**
 * Displays a grid of products.
 */
@Composable
fun ProductGrid(products: List<Product>, modifier: Modifier = Modifier, columns: Int = 2) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val rows = (products.size + columns - 1) / columns
        items(rows) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(columns) { col ->
                    val index = row * columns + col
                    if (index < products.size) {
                        ProductCard(
                            product = products[index],
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
