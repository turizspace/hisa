package com.hisa.ui.screens.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
    val listState = rememberLazyListState()

    // Notify parent when the list is at the top (so parent can show tabs / FAB)
    LaunchedEffect(listState) {
        var previousIsAtTop = true
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
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
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = {
                        selectedCategory = null
                        feedViewModel.setSelectedCategory(null)
                    },
                    label = { Text("All") }
                )

                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = {
                            selectedCategory = category
                            feedViewModel.setSelectedCategory(category)
                        },
                        label = { Text(category) },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    // No shimmer animation; use static placeholders when loading

    LazyColumn(state = listState) {
                // Always render whatever services are available; no shimmer or brush.
                // Show newest services first by sorting descending on createdAt,
                // then apply the search filter and category filter on the sorted list.
                val sortedServices = services.sortedByDescending { it.createdAt }
                val filteredBySearch = if (searchText.isEmpty()) {
                    sortedServices
                } else {
                    sortedServices.filter { service ->
                        service.title.contains(searchText, ignoreCase = true) ||
                        service.summary.contains(searchText, ignoreCase = true) ||
                        service.tags.any { it.contains(searchText, ignoreCase = true) }
                    }
                }

                val filteredServices = if (selectedCategory.isNullOrBlank()) {
                    filteredBySearch
                } else {
                    filteredBySearch.filter { service ->
                        // match topic tags (rawTags with "t") to selectedCategory
                        val topicTags = service.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                            .mapNotNull { it.getOrNull(1) as? String }
                        topicTags.contains(selectedCategory)
                    }
                }
                items(
                    items = filteredServices,
                    key = { "${it.eventId}.${it.pubkey}" } // Making key unique by combining eventId and pubkey (dot-separated to match ServiceCard)
                ) { service ->
                    // Determine whether to show topic tags for this service while searching.
                        val topicTagsForService = service.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                        .mapNotNull { it.getOrNull(1) as? String }
                    val showTags = if (searchText.isEmpty()) {
                        true
                    } else {
                        // Only show tags if any topic tag contains the search query
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
                                // Persist UI search/category so when user navigates back we can restore
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
// Preview for Compose UI visualization
@Preview(showBackground = true)
@Composable
fun FeedTabPreview() {
    // You may need to provide a fake ViewModel or mock data for a full preview
}