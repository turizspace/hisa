package com.hisa.ui.screens.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hisa.data.model.ServiceListing
import com.hisa.ui.components.MiniProductTile
import com.hisa.ui.components.ShopHeroBanner

@Composable
fun ShopDashboard(
    listings: List<ServiceListing>
) {
    if (listings.isEmpty()) return

    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(listings.take(10), key = { it.eventId }) { svc ->
            MiniProductTile(
                title = svc.title,
                price = try {
                    svc.rawTags.firstOrNull { it.size > 1 && it[0] == "price" }?.getOrNull(1) as? String
                } catch (_: Exception) { null },
                imageUrl = svc.rawTags.firstOrNull { it.isNotEmpty() && it[0] == "image" }?.getOrNull(1) as? String,
                onClick = {}
            )
        }
    }
}
