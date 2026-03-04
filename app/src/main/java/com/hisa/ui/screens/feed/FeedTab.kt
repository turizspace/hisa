package com.hisa.ui.screens.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.hisa.viewmodel.ShopViewModel
import com.hisa.viewmodel.AuthViewModel
import com.hisa.ui.components.SearchBar
import com.hisa.ui.components.ServiceCard
import com.hisa.viewmodel.FeedViewModel
import com.hisa.data.model.ServiceListing
import androidx.navigation.NavController

import androidx.compose.ui.tooling.preview.Preview
import com.hisa.ui.components.CategoryChipRow
import com.hisa.ui.components.SectionedFeed
import com.hisa.ui.components.TabLoadingPlaceholder
import com.hisa.ui.components.rememberTabLoadingVisibility
import com.hisa.ui.components.FeedSkeletonLoader
import com.hisa.ui.components.EmptyFeedState
import com.hisa.ui.components.SearchEmptyState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.hisa.data.repository.ServiceRepository
import com.hisa.ui.navigation.Routes

@Composable
fun FeedTab(
    navController: NavController,
    userPubkey: String,
    searchQuery: String,
    feedViewModel: FeedViewModel,
    onAtTopChange: ((Boolean) -> Unit)? = null
) {
    val shopViewModel: ShopViewModel = hiltViewModel()
    val authViewModel: AuthViewModel = hiltViewModel()
    val privateKeyHex by authViewModel.privateKey.collectAsState()
    val services by feedViewModel.services.collectAsState()
    val isLoading by feedViewModel.isLoading.collectAsState()
    val showLoading = rememberTabLoadingVisibility(isLoading = isLoading)
    val categories by feedViewModel.categories.collectAsState()
    // Persist selected category in the ViewModel so it survives navigation
    val selectedCategoryState by feedViewModel.selectedCategory.collectAsState()
    // Try to restore search/category state if returning from navigation
    val saved = navController.currentBackStackEntry?.savedStateHandle
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(saved?.get<String>("feed_selectedCategory") ?: selectedCategoryState) }
    var searchText by rememberSaveable { mutableStateOf(saved?.get<String>("feed_searchQuery") ?: searchQuery) }
    LaunchedEffect(searchQuery) {
        if (searchQuery != searchText) searchText = searchQuery
    }
    val gridState = rememberLazyGridState()

    // Notify parent when the grid is at the top (so parent can show tabs / FAB)
    LaunchedEffect(gridState) {
        var previousIsAtTop = true
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val isAtTop = index == 0 && offset == 0
                if (isAtTop != previousIsAtTop) {
                    onAtTopChange?.invoke(isAtTop)
                    previousIsAtTop = isAtTop
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Categories horizontal scroll (topic tags) - hide when a search is active
        if (categories.isNotEmpty() && searchText.isEmpty()) {
            CategoryChipRow(
                categories = categories,
                selectedCategory = selectedCategory,
                onSelect = { cat ->
                    selectedCategory = cat
                    feedViewModel.setSelectedCategory(cat)
                }
            )
        }

        // When loading, show skeleton loaders
        if (showLoading) {
            FeedSkeletonLoader(
                modifier = Modifier.fillMaxSize(),
                itemCount = 5
            )
            return@Column
        }

        // Build sorted and filtered services
        val sortedServices = services.sortedByDescending { it.createdAt }
        val filteredBySearch = if (searchText.isEmpty()) {
            sortedServices
        } else {
            sortedServices.filter { service ->
                service.title.contains(searchText, ignoreCase = true) ||
                        (service.summary ?: "").contains(searchText, ignoreCase = true) ||
                        service.tags.any { it.contains(searchText, ignoreCase = true) }
            }
        }

        val filteredServices = if (selectedCategory.isNullOrBlank()) {
            filteredBySearch
        } else {
            filteredBySearch.filter { service ->
                val topicTags = service.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                    .mapNotNull { it.getOrNull(1) as? String }
                topicTags.contains(selectedCategory)
            }
        }

        // If not searching and there are categories, show sectioned horizontal feed
        if (searchText.isEmpty() && filteredServices.isNotEmpty()) {
            val grouped = filteredServices.groupBy { service ->
                val topicTags = service.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                    .mapNotNull { it.getOrNull(1) as? String }
                topicTags.firstOrNull() ?: "Uncategorized"
            }

            SectionedFeed(
                grouped = grouped,
                onItemClick = { service ->
                    ServiceRepository.cacheService(service)
                    navController.navigate(
                        Routes.SERVICE_DETAIL
                            .replace("{eventId}", service.eventId)
                            .replace("{pubkey}", service.pubkey)
                    )
                    navController.currentBackStackEntry?.savedStateHandle?.apply {
                        set("feed_searchQuery", searchText)
                        set("feed_selectedCategory", selectedCategory)
                    }
                },
                onSeeAll = { category ->
                    selectedCategory = category
                    feedViewModel.setSelectedCategory(category)
                },
                onMessageClick = { pubkey ->
                    navController.navigate(Routes.DM.replace("{pubkey}", pubkey))
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
                                val images = svc.rawTags.filter { it.isNotEmpty() && it[0] == "image" }.mapNotNull { it.getOrNull(1) as? String }
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
                    navController.navigate(Routes.CREATE_SERVICE)
                },
                onDelete = { svc ->
                    try {
                        shopViewModel.requestDeleteService(svc, privateKeyHex, onResult = { ok, err ->
                            if (ok) android.util.Log.i("FeedTab", "Deletion request sent for ${svc.eventId}")
                            else android.util.Log.w("FeedTab", "Deletion request failed: $err")
                        })
                    } catch (e: Exception) {
                        android.util.Log.w("FeedTab", "Failed to request delete: ${e.message}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                userPubkey = userPubkey
            )
        } else if (filteredServices.isEmpty()) {
            // Show empty state when no services match filter/search
            if (searchText.isNotEmpty()) {
                SearchEmptyState(
                    searchQuery = searchText,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                EmptyFeedState(
                    modifier = Modifier.fillMaxSize(),
                    onRefresh = {
                        feedViewModel.subscribeToFeed()
                    }
                )
            }
        } else {
            // Fall back to a two-column grid for search results or empty-category views
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 8.dp
                ),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = filteredServices,
                    key = { "${it.eventId}.${it.pubkey}" }
                ) { service ->
                    val topicTagsForService = service.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                        .mapNotNull { it.getOrNull(1) as? String }
                    val showTags = if (searchText.isEmpty()) {
                        true
                    } else {
                        topicTagsForService.any { it.contains(searchText, ignoreCase = true) }
                    }

                    ServiceCard(
                        service = service,
                        showTags = showTags,
                        onClick = {
                            ServiceRepository.cacheService(service)
                            navController.navigate(
                                Routes.SERVICE_DETAIL
                                    .replace("{eventId}", service.eventId)
                                    .replace("{pubkey}", service.pubkey)
                            )
                            navController.currentBackStackEntry?.savedStateHandle?.apply {
                                set("feed_searchQuery", searchText)
                                set("feed_selectedCategory", selectedCategory)
                            }
                        },
                        onMessageClick = { pubkey, profilePicture ->
                            navController.navigate(
                                Routes.DM
                                    .replace("{pubkey}", pubkey)
                            )
                        },
                        userPubkey = userPubkey
                    )
                }
            }
        }
    }
}
// Preview for Compose UI visualization
@Preview(showBackground = true)
@Composable
fun FeedTabPreview() {
    // You may need to provide a fake ViewModel or mock data for a full preview
}
