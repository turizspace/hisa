package com.hisa.ui.screens.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.hisa.data.model.ServiceListing
import com.hisa.data.repository.ServiceRepository
import com.hisa.ui.components.EmptyFeedState
import com.hisa.ui.components.FeedSkeletonLoader
import com.hisa.ui.components.ServicePreviewCard
import com.hisa.ui.components.SearchEmptyState
import com.hisa.ui.components.StallPreviewCard
import com.hisa.ui.components.rememberTabLoadingVisibility
import com.hisa.ui.navigation.Routes
import com.hisa.ui.util.LocalProfileRepository
import com.hisa.viewmodel.FeedViewModel
import com.hisa.viewmodel.StallsViewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

private const val PREVIEW_ITEM_COUNT = 6

@Composable
fun FeedTab(
    navController: NavController,
    userPubkey: String,
    searchQuery: String,
    feedViewModel: FeedViewModel,
    onAtTopChange: ((Boolean) -> Unit)? = null,
    onSeeAllStalls: () -> Unit = {}
) {
    val stallsViewModel: StallsViewModel = hiltViewModel()
    val services by feedViewModel.services.collectAsState()
    val stalls by stallsViewModel.stalls.collectAsState()
    val isLoading by feedViewModel.isLoading.collectAsState()
    val profileRepository = LocalProfileRepository.current
    val profiles by profileRepository.profiles.collectAsState()
    val showLoading = rememberTabLoadingVisibility(isLoading = isLoading)

    val saved = navController.currentBackStackEntry?.savedStateHandle
    var searchText by rememberSaveable { mutableStateOf(saved?.get<String>("feed_searchQuery") ?: searchQuery) }
    var showAllServices by rememberSaveable { mutableStateOf(saved?.get<Boolean>("feed_showAllServices") ?: false) }

    LaunchedEffect(searchQuery) {
        if (searchQuery != searchText) searchText = searchQuery
    }

    LaunchedEffect(showAllServices) {
        saved?.set("feed_showAllServices", showAllServices)
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(services) {
        profileRepository.ensureProfiles(services.map { it.pubkey }.toSet())
    }

    val sortedServices = services.sortedByDescending { it.createdAt }
    val sortedStalls = stalls.sortedByDescending { it.createdAt }
    val normalizedQuery = searchText.trim()
    val isSearching = normalizedQuery.isNotEmpty()
    val showingDiscovery = !isSearching && !showAllServices

    val filteredServices = if (isSearching) {
        sortedServices.filter { service ->
            service.title.contains(normalizedQuery, ignoreCase = true) ||
                (service.summary ?: "").contains(normalizedQuery, ignoreCase = true) ||
                service.tags.any { it.contains(normalizedQuery, ignoreCase = true) }
        }
    } else {
        sortedServices
    }

    val filteredStalls = if (isSearching) {
        sortedStalls.filter { stall ->
            stall.name.contains(normalizedQuery, ignoreCase = true) ||
                stall.description.contains(normalizedQuery, ignoreCase = true) ||
                stall.ownerDisplayName.contains(normalizedQuery, ignoreCase = true) ||
                stall.categories.any { it.contains(normalizedQuery, ignoreCase = true) }
        }
    } else {
        sortedStalls
    }

    LaunchedEffect(listState, gridState, showingDiscovery, isSearching) {
        if (showingDiscovery || isSearching) {
            var previousIsAtTop = true
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    val isAtTop = index == 0 && offset == 0
                    if (isAtTop != previousIsAtTop) {
                        onAtTopChange?.invoke(isAtTop)
                        previousIsAtTop = isAtTop
                    }
                }
        } else {
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
    }

    if (showLoading && services.isEmpty() && stalls.isEmpty()) {
        FeedSkeletonLoader(
            modifier = Modifier.fillMaxSize(),
            itemCount = 5
        )
        return
    }

    when {
        showingDiscovery && services.isEmpty() && stalls.isEmpty() -> {
            EmptyFeedState(
                modifier = Modifier.fillMaxSize(),
                onRefresh = { feedViewModel.subscribeToFeed() }
            )
        }

        showingDiscovery -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    PreviewSectionHeader(
                        title = "Services",
                        subtitle = "${sortedServices.size} listings",
                        actionLabel = "See all",
                        onAction = { showAllServices = true }
                    )
                }

                item {
                    if (sortedServices.isEmpty()) {
                        PreviewSectionEmptyState(
                            text = "No services yet. Pull to refresh or check back soon."
                        )
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                items = sortedServices.take(PREVIEW_ITEM_COUNT),
                                key = { "${it.eventId}.${it.pubkey}" }
                            ) { service ->
                                Box(modifier = Modifier.width(236.dp)) {
                                    ServicePreviewCard(
                                        service = service,
                                        publisherMetadata = profiles[service.pubkey],
                                        showTags = false,
                                        onClick = {
                                            openServiceDetail(
                                                navController = navController,
                                                service = service,
                                                searchText = searchText
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    PreviewSectionHeader(
                        title = "Shops",
                        subtitle = "${sortedStalls.size} stalls",
                        actionLabel = "See all",
                        onAction = onSeeAllStalls
                    )
                }

                item {
                    if (sortedStalls.isEmpty()) {
                        PreviewSectionEmptyState(
                            text = "No stalls yet. New shops will show up here."
                        )
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                items = sortedStalls.take(PREVIEW_ITEM_COUNT),
                                key = { "${it.ownerPubkey}:${it.id}" }
                            ) { stall ->
                                StallPreviewCard(
                                    stall = stall,
                                    modifier = Modifier.width(236.dp),
                                    onClick = {
                                        navController.navigate(
                                            Routes.stallDetail(
                                                stallId = stall.id,
                                                ownerPubkey = stall.ownerPubkey,
                                                eventId = stall.eventId
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        isSearching && filteredServices.isEmpty() && filteredStalls.isEmpty() -> {
            SearchEmptyState(
                searchQuery = normalizedQuery,
                modifier = Modifier.fillMaxSize()
            )
        }

        isSearching -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (filteredServices.isNotEmpty()) {
                    item {
                        PreviewSectionHeader(
                            title = "Services",
                            subtitle = "${filteredServices.size} matches"
                        )
                    }
                    items(
                        items = filteredServices,
                        key = { "${it.eventId}.${it.pubkey}" }
                    ) { service ->
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            ServicePreviewCard(
                                service = service,
                                publisherMetadata = profiles[service.pubkey],
                                showTags = true,
                                onClick = {
                                    openServiceDetail(
                                        navController = navController,
                                        service = service,
                                        searchText = searchText
                                    )
                                }
                            )
                        }
                    }
                }

                if (filteredStalls.isNotEmpty()) {
                    item {
                        PreviewSectionHeader(
                            title = "Shops",
                            subtitle = "${filteredStalls.size} matches",
                            actionLabel = if (filteredStalls.size > PREVIEW_ITEM_COUNT) "See all" else null,
                            onAction = if (filteredStalls.size > PREVIEW_ITEM_COUNT) onSeeAllStalls else null
                        )
                    }
                    items(
                        items = filteredStalls,
                        key = { "${it.ownerPubkey}:${it.id}" }
                    ) { stall ->
                        StallPreviewCard(
                            stall = stall,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            onClick = {
                                navController.navigate(
                                    Routes.stallDetail(
                                        stallId = stall.id,
                                        ownerPubkey = stall.ownerPubkey,
                                        eventId = stall.eventId
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }

        filteredServices.isEmpty() -> {
            EmptyFeedState(
                modifier = Modifier.fillMaxSize(),
                onRefresh = { feedViewModel.subscribeToFeed() }
            )
        }

        else -> {
            Column(modifier = Modifier.fillMaxSize()) {
                PreviewSectionHeader(
                    title = "All Services",
                    subtitle = "${filteredServices.size} listings",
                    actionLabel = "Back",
                    onAction = { showAllServices = false }
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = filteredServices,
                        key = { "${it.eventId}.${it.pubkey}" }
                    ) { service ->
                        ServicePreviewCard(
                            service = service,
                            publisherMetadata = profiles[service.pubkey],
                            showTags = false,
                            onClick = {
                                openServiceDetail(
                                    navController = navController,
                                    service = service,
                                    searchText = searchText
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun openServiceDetail(
    navController: NavController,
    service: ServiceListing,
    searchText: String
) {
    ServiceRepository.cacheService(service)
    navController.navigate(
        Routes.SERVICE_DETAIL
            .replace("{eventId}", service.eventId)
            .replace("{pubkey}", service.pubkey)
    )
    navController.currentBackStackEntry?.savedStateHandle?.set("feed_searchQuery", searchText)
}

@Composable
private fun PreviewSectionHeader(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun PreviewSectionEmptyState(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.large
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 22.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun FeedTabPreview() {
    Spacer(modifier = Modifier.height(1.dp))
}
