package com.hisa.ui.screens.lists

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.hisa.ui.screens.lists.ChannelsChatGroup
import com.hisa.data.model.Channel
import com.hisa.data.model.Group
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.ui.components.ChannelChip
import com.hisa.ui.navigation.Routes
import com.hisa.viewmodel.ChannelsViewModel
import com.hisa.viewmodel.ChannelsViewModelFactory

@Composable
fun ChannelsTab(
    navController: NavController,
    userPubkey: String,
    nostrClient: NostrClient,
    subscriptionManager: SubscriptionManager,
    privateKey: ByteArray?,
    searchQuery: String = "",
    channelsViewModel: ChannelsViewModel = viewModel(
        factory = ChannelsViewModelFactory(
            nostrClient = nostrClient,
            subscriptionManager = subscriptionManager,
            privateKey = privateKey,
            pubkey = userPubkey
        )
    )
) {
    val channels by channelsViewModel.channels.collectAsState()
    val isLoading by channelsViewModel.isLoading.collectAsState()
    val categories by channelsViewModel.categories.collectAsState()
    val participantCounts by channelsViewModel.participantCounts.collectAsState()
    // Try to restore selectedCategory and searchQuery from SavedStateHandle (if returning from channel)
    val saved = navController.currentBackStackEntry?.savedStateHandle
    var selectedCategory by rememberSaveable { mutableStateOf(saved?.get<String>("channels_selectedCategory")) }
    // Local editable search text initialized from the passed-in searchQuery (so it can be cleared/edited locally)
    var searchText by rememberSaveable { mutableStateOf(saved?.get<String>("channels_searchQuery") ?: searchQuery) }
    // If the parent passes a different searchQuery (restored from SavedStateHandle), reflect it locally
    LaunchedEffect(searchQuery) {
        if (searchQuery != searchText) searchText = searchQuery
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Add button to create a new channel — hide when user scrolls down, show when scrolling up / at top
        // Preserve scroll position across navigation using rememberSaveable and LazyListState.Saver
        val listState = rememberSaveable(saver = LazyListState.Saver) {
            LazyListState()
        }
        val showCreateButton = remember { mutableStateOf(true) }

        // Detect scroll direction and toggle visibility with a small hysteresis to avoid jitter
        LaunchedEffect(listState) {
            var previous = listState.firstVisibleItemIndex * 100000 + listState.firstVisibleItemScrollOffset
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    val current = index * 100000 + offset
                    val delta = current - previous
                    val isAtTop = index == 0 && offset <= 20
                    when {
                        delta > 20 -> showCreateButton.value = false // scrolled down
                        delta < -20 || isAtTop -> showCreateButton.value = true // scrolled up or at top
                    }
                    previous = current
                }
        }

        AnimatedVisibility(
            visible = showCreateButton.value,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        ) {
            Button(
                onClick = { navController.navigate(Routes.CREATE_CHANNEL) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Channel")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Channel")
            }
        }
    // Categories horizontal scroll (hide when searching)
    if (categories.isNotEmpty() && searchText.isEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("All") }
                )

                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category) },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // First filter by search query if present, then apply selectedCategory
            val afterSearch = if (searchText.isNullOrEmpty()) {
                channels
            } else {
                channels.filter { ch ->
                    ch.name.contains(searchText, ignoreCase = true) ||
                    ch.about.contains(searchText, ignoreCase = true) ||
                    ch.categories.any { it.contains(searchText, ignoreCase = true) }
                }
            }

            val filteredChannels = if (selectedCategory != null && searchText.isEmpty()) {
                afterSearch.filter { it.categories.contains(selectedCategory) }
            } else {
                afterSearch
            }

            // Re-sort the filtered channels by participant count (popularity) desc, then by name
            val sortedChannelsByPopularity = remember(filteredChannels, participantCounts) {
                filteredChannels.sortedWith(
                    compareByDescending<com.hisa.data.model.Channel> { participantCounts[it.id] ?: 0 }
                        .thenBy { it.name }
                )
            }

            // NestedScrollConnection detects pull-down when the list is already at the top
            val nestedScrollConnection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        // available.y > 0 when user is dragging down
                        if (available.y > 0f && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                            showCreateButton.value = true
                        }
                        return Offset.Zero
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
                    .nestedScroll(nestedScrollConnection)
            ) {
                items(sortedChannelsByPopularity) { channel ->
                    val count = participantCounts[channel.id] ?: 0
                    ChannelsChatGroup(
                        channel = channel,
                        onOpen = { ch ->
                                // Persist UI state so when user navigates back we restore search/category and scroll
                            navController.currentBackStackEntry?.savedStateHandle?.apply {
                                set("channels_searchQuery", searchText)
                                set("channels_selectedCategory", selectedCategory)
                                // listState is already saved via rememberSaveable + Saver
                            }
                            // Set channel details for chat screen and navigate
                            navController.navigate(Routes.CHANNEL_CHAT)
                            navController.currentBackStackEntry?.savedStateHandle?.apply {
                                set("channelId", ch.id)
                                set("channelName", ch.name)
                                set("channelPicture", ch.picture)
                            }
                        },
                        participantCount = count
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ChannelsTabPreview() {
    val navController = rememberNavController()
    val sampleGroups = listOf(
        Group(id = "1", title = "Group 1", description = "Description 1", tag = "tag1"),
        Group(id = "2", title = "Group 2", description = "Description 2", tag = "tag2"),
        Group(id = "3", title = "Group 3", description = "Description 3", tag = "tag3")
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(sampleGroups) { group ->

        }
    }
}