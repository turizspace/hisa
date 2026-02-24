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
import com.hisa.ui.components.SearchBar
import com.hisa.ui.components.ServiceCard
import com.hisa.viewmodel.FeedViewModel
import com.hisa.data.model.ServiceListing
import androidx.navigation.NavController

import androidx.compose.ui.tooling.preview.Preview
import com.hisa.ui.components.CategoryChipRow
import com.hisa.ui.components.SectionedFeed
import com.hisa.ui.components.FeedSkeleton
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
    val services by feedViewModel.services.collectAsState()
    val isLoading by feedViewModel.isLoading.collectAsState()
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

        // When loading, show skeletons
        if (isLoading) {
            FeedSkeleton(modifier = Modifier.fillMaxWidth())
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
                modifier = Modifier.fillMaxWidth()
            )
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
                        }
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