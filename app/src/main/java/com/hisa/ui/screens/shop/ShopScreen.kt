package com.hisa.ui.screens.shop

import androidx.annotation.RequiresApi
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.hisa.ui.components.CompactServiceCard
import com.hisa.ui.components.HisaTabRow
import com.hisa.ui.components.StallCard
import com.hisa.ui.navigation.Routes
import com.hisa.util.cleanPubkeyFormat
import com.hisa.util.normalizeNostrPubkey
import com.hisa.viewmodel.AuthViewModel
import com.hisa.viewmodel.ShopViewModel

/**
 * Simple placeholder screen for the user's Shop. Add UI and actions as needed.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ShopScreen(
    navController: NavController,
    userPubkey: String
) {
    val ownerHex = remember(userPubkey) { normalizeNostrPubkey(userPubkey) ?: cleanPubkeyFormat(userPubkey) }

    val shopViewModel: ShopViewModel = hiltViewModel()
    val authViewModel: AuthViewModel = hiltViewModel()
    var shopSubscriptionStarted by rememberSaveable { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(ownerHex) {
        shopViewModel.subscribeToOwner(ownerHex)
        shopSubscriptionStarted = true
    }

    val myListings by shopViewModel.services.collectAsState(initial = emptyList())
    val myStalls by shopViewModel.stalls.collectAsState(initial = emptyList())
    val listingsLoading by shopViewModel.listingsLoading.collectAsState(initial = false)
    val stallsLoading by shopViewModel.stallsLoading.collectAsState(initial = false)
    val privateKeyHex by authViewModel.privateKey.collectAsState()
    val sortedListings = remember(myListings) { myListings.sortedByDescending { it.createdAt } }
    val sortedStalls = remember(myStalls) { myStalls.sortedByDescending { it.createdAt } }
    var selectedViewIndex by rememberSaveable { mutableStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "My Shop",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${myListings.size} listings • ${myStalls.size} stalls",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }
                TextButton(onClick = { navController.navigate(Routes.CREATE_SERVICE) }) {
                    Text("Create")
                }
            }

            HisaTabRow(
                tabs = listOf("Listings", "Stalls"),
                selectedTabIndex = selectedViewIndex,
                onTabSelected = { index -> selectedViewIndex = index },
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Divider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                thickness = 1.dp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (selectedViewIndex == 0) {
                when {
                    listingsLoading || !shopSubscriptionStarted -> LoadingStateCard(title = "Loading")
                    sortedListings.isEmpty() -> EmptyStateCard(message = "No listings found")
                    else -> {
                        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            items(sortedListings, key = { it.eventId }) { service ->
                                CompactServiceCard(
                                    service = service,
                                    onClick = {
                                        val route = com.hisa.ui.navigation.Routes.SERVICE_DETAIL
                                            .replace("{eventId}", service.eventId)
                                            .replace("{pubkey}", service.pubkey)
                                        navController.navigate(route)
                                    },
                                    onMessageClick = { pubkey ->
                                        val target = cleanPubkeyFormat(pubkey ?: service.pubkey)
                                        navController.navigate("dm/$target")
                                    },
                                    onEdit = { svc ->
                                        try {
                                            val current = navController.currentBackStackEntry
                                            val previous = navController.previousBackStackEntry
                                            listOf(current, previous).forEach { entry ->
                                                try {
                                                    val existingD = try {
                                                        svc.rawTags.firstOrNull { it.isNotEmpty() && it[0] == "d" }?.getOrNull(1) as? String
                                                    } catch (_: Exception) { null }
                                                    if (!existingD.isNullOrBlank()) {
                                                        entry?.savedStateHandle?.set("edit_service_d", existingD)
                                                    }
                                                    entry?.savedStateHandle?.set("edit_service_title", svc.title)
                                                    entry?.savedStateHandle?.set("edit_service_summary", svc.summary ?: "")
                                                    entry?.savedStateHandle?.set("edit_service_description", svc.content ?: "")
                                                    val tagsJson = org.json.JSONArray()
                                                    svc.rawTags.forEach { tag ->
                                                        val arr = org.json.JSONArray()
                                                        tag.forEach { arr.put(it) }
                                                        tagsJson.put(arr)
                                                    }
                                                    entry?.savedStateHandle?.set("edit_service_tags", tagsJson.toString())
                                                    val images = svc.rawTags
                                                        .filter { it.isNotEmpty() && it[0] == "image" }
                                                        .mapNotNull { it.getOrNull(1) as? String } +
                                                        svc.rawTags
                                                            .filter { it.isNotEmpty() && it[0] == "imeta" }
                                                            .flatMap { tag ->
                                                                tag.drop(1).mapNotNull { part ->
                                                                    when {
                                                                        part.startsWith("url ") -> part.removePrefix("url ").trim()
                                                                        part.startsWith("http://") || part.startsWith("https://") -> part.trim()
                                                                        else -> null
                                                                    }
                                                                }
                                                            }
                                                    if (images.isNotEmpty()) entry?.savedStateHandle?.set("edit_service_image_urls", images.joinToString("\n"))
                                                    try {
                                                        val priceTag = svc.rawTags.firstOrNull { it.size > 1 && it[0] == "price" }
                                                        val pAmount = priceTag?.getOrNull(1) as? String
                                                        val pCurrency = priceTag?.getOrNull(2) as? String
                                                        val pFreq = priceTag?.getOrNull(3) as? String
                                                        if (!pAmount.isNullOrBlank()) entry?.savedStateHandle?.set("edit_service_price", pAmount)
                                                        if (!pCurrency.isNullOrBlank()) entry?.savedStateHandle?.set("edit_service_currency", pCurrency)
                                                        if (!pFreq.isNullOrBlank()) entry?.savedStateHandle?.set("edit_service_frequency", pFreq)
                                                    } catch (_: Exception) {}
                                                    try {
                                                        val locTag = svc.rawTags.firstOrNull { it.size > 1 && it[0] == "location" }
                                                        val loc = locTag?.getOrNull(1) as? String
                                                        if (!loc.isNullOrBlank()) entry?.savedStateHandle?.set("edit_service_location", loc)
                                                    } catch (_: Exception) {}
                                                } catch (_: Exception) {}
                                            }
                                        } catch (_: Exception) {}
                                        navController.navigate(com.hisa.ui.navigation.Routes.CREATE_SERVICE)
                                    },
                                    onDelete = { svc ->
                                        try {
                                            shopViewModel.requestDeleteService(svc, privateKeyHex, onResult = { ok, err ->
                                                if (ok) android.util.Log.i("ShopScreen", "Deletion request sent for ${svc.eventId}")
                                                else android.util.Log.w("ShopScreen", "Deletion request failed: $err")
                                            })
                                        } catch (e: Exception) {
                                            android.util.Log.w("ShopScreen", "Failed to request delete: ${e.message}")
                                        }
                                    },
                                    userPubkey = ownerHex
                                )
                            }
                        }
                    }
                }
            } else {
                when {
                    stallsLoading || !shopSubscriptionStarted -> LoadingStateCard(title = "Loading")
                    sortedStalls.isEmpty() -> EmptyStateCard(message = "No stalls found")
                    else -> {
                        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            items(sortedStalls, key = { it.eventId }) { stall ->
                                StallCard(
                                    stall = stall,
                                    onClick = {
                                        val route = com.hisa.ui.navigation.Routes.stallDetail(stall.id, stall.ownerPubkey, stall.eventId)
                                        navController.navigate(route)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingStateCard(title: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun EmptyStateCard(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
