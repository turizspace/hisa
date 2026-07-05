package com.hisa.ui.screens.shop

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.hisa.data.cache.UiResumeStateStore
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.ui.components.StallPreviewCard
import com.hisa.ui.navigation.Routes
import com.hisa.viewmodel.StallsViewModel

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun StallsTab(
    navController: NavController,
    userPubkey: String,
    nostrClient: NostrClient,
    subscriptionManager: SubscriptionManager,
    privateKey: ByteArray?,
    searchQuery: String = ""
) {
    val context = LocalContext.current
    val resumeStateStore = remember { UiResumeStateStore(context.applicationContext) }
    val viewModel: StallsViewModel = hiltViewModel()
    val stalls by viewModel.stalls.collectAsState()
    var searchText by remember { mutableStateOf(resumeStateStore.stallsSearchQuery.ifBlank { searchQuery }) }
    val normalizedQuery = remember(searchText) { searchText.trim() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = resumeStateStore.stallsListFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = resumeStateStore.stallsListFirstVisibleItemOffset
    )

    LaunchedEffect(searchQuery) {
        if (searchQuery != searchText) {
            searchText = searchQuery
        }
    }

    LaunchedEffect(searchText) {
        resumeStateStore.saveStallsSearchQuery(searchText)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                resumeStateStore.saveStallsScrollPosition(index, offset)
            }
    }
    val filteredStalls = remember(stalls, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            stalls
        } else {
            val query = normalizedQuery.lowercase()
            stalls.filter { stall ->
                stall.name.lowercase().contains(query) ||
                    stall.description.lowercase().contains(query) ||
                    stall.ownerDisplayName.lowercase().contains(query) ||
                    stall.categories.any { it.lowercase().contains(query) }
            }
        }
    }

    if (filteredStalls.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (normalizedQuery.isBlank()) "No stalls found yet." else "No stalls match \"$normalizedQuery\".",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = filteredStalls,
            key = { "${it.ownerPubkey}:${it.id}" }
        ) { stall ->
            StallPreviewCard(
                stall = stall,
                modifier = Modifier.padding(horizontal = 8.dp),
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
