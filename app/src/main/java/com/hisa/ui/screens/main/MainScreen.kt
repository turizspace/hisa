package com.hisa.ui.screens.main


import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hisa.ui.navigation.Routes
import com.hisa.ui.screens.feed.FeedTab
import com.hisa.ui.screens.lists.ChannelsTab
import com.hisa.ui.screens.messages.MessagesTab
import com.hisa.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.ui.components.SearchBar
import com.hisa.util.Constants
import com.hisa.viewmodel.ChannelsViewModel
import com.hisa.viewmodel.ChannelsViewModelFactory
import com.hisa.viewmodel.FeedViewModel
import com.hisa.viewmodel.MessagesViewModel

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController,
    userPubkey: String,
    privateKey: String,
    nostrClient: NostrClient,
    subscriptionManager: SubscriptionManager,
    messagesViewModel: MessagesViewModel,
    showWelcomeDialog: Boolean = false,
    onDialogDismissed: (() -> Unit)? = null
) {
    // Try to restore previously selected tab from NavController's SavedStateHandle so navigating
    // away and back (for example opening a channel chat) returns to the same tab.
    val currentEntry = navController.currentBackStackEntry
    val savedStateHandle = currentEntry?.savedStateHandle
    val restoredTab = savedStateHandle?.get<Int>("selectedTab") ?: 0
    var selectedTab by rememberSaveable { mutableStateOf(restoredTab) }

    // Keep the saved state handle in sync whenever the selected tab changes
    LaunchedEffect(selectedTab) {
        savedStateHandle?.set("selectedTab", selectedTab)
    }
    var searchQuery by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(showWelcomeDialog) }
    val tabs = listOf("Feed", "Messages", "Channels")
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val feedViewModel: FeedViewModel = hiltViewModel()
    var feedAtTop by remember { mutableStateOf(true) }
    val pkBytesForChannels: ByteArray? = if (privateKey.isNotBlank()) {
        try { privateKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray() } catch (e: Exception) { null }
    } else null
    val channelsViewModel: ChannelsViewModel = viewModel(
        factory = ChannelsViewModelFactory(
            nostrClient = nostrClient,
            subscriptionManager = subscriptionManager,
            privateKey = pkBytesForChannels,
            pubkey = userPubkey
        )
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                onDialogDismissed?.invoke()
            },
            title = { M3Text("Welcome to Hisa!") },
            text = { M3Text("Your account has been created successfully! Don't forget to backup your keys in Settings.") },
            confirmButton = {
                Button(onClick = {
                    showDialog = false
                    onDialogDismissed?.invoke()
                }) {
                    M3Text("OK")
                }
            }
        )
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                // Drawer header with app icon, name and optional subtitle/version
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.png_hisa),
                        contentDescription = "Hisa logo",
                        modifier = Modifier
                            .width(48.dp)
                            .height(48.dp)
                            .clip(
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        M3Text(
                            text = "Hisa",
                            style = MaterialTheme.typography.titleLarge
                        )
                        M3Text(
                            text = "Sell skills and Other stuff",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                NavigationDrawerItem(
                    label = { M3Text("Profile") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        // Navigate to profile with just the pubkey
                        navController.navigate("profile/$userPubkey")
                    },
                    modifier = Modifier,
                    colors = NavigationDrawerItemDefaults.colors(),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.PersonOutline,
                            contentDescription = "Profile Icon"
                        )
                    }
                )
                NavigationDrawerItem(
                    label = { M3Text("Settings") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate(Routes.SETTINGS)
                    },
                    modifier = Modifier,
                    colors = NavigationDrawerItemDefaults.colors(),
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings Icon"
                        )
                    }
                )
                NavigationDrawerItem(
                    label = { M3Text("FAQs") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate(Routes.FAQ)
                    },
                    modifier = Modifier,
                    colors = NavigationDrawerItemDefaults.colors(),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "FAQ Icon"
                        )
                    }
                )

                NavigationDrawerItem(
                    label = { M3Text("Donate") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate(Routes.DONATE)
                    },
                    modifier = Modifier,
                    colors = NavigationDrawerItemDefaults.colors(),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.WaterDrop,
                            contentDescription = "Donate Icon"
                        )
                    }
                )

                NavigationDrawerItem(
                    label = { M3Text("Support") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(Constants.SUPPORT_EMAIL))
                            putExtra(Intent.EXTRA_SUBJECT, Constants.SUPPORT_SUBJECT)
                        }
                        navController.context.startActivity(
                            Intent.createChooser(intent, "Send email")
                        )
                    },
                    modifier = Modifier,
                    colors = NavigationDrawerItemDefaults.colors(),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.SupportAgent,
                            contentDescription = "Contact Support Icon"
                        )
                    }
                )
            }
            // Ensure channels subscription is active so channel chats can be fetched at any time
            LaunchedEffect(Unit) {
                channelsViewModel.ensureSubscribed()
            }
        }
    ) {
        Scaffold(
            topBar = {
                // Use a TopAppBar with the app name and a compact SearchBar next to it
                CenterAlignedTopAppBar(
                    title = {
                        val focusManager = LocalFocusManager.current
                        // Modern design: put the menu icon inside the search bar and let search fill the width
                        SearchBar(
                            value = searchQuery,
                            onValueChange = { new ->
                                searchQuery = new
                                // Keep channels saved state in sync when user types while on Channels tab
                                if (selectedTab == 2) {
                                    navController.currentBackStackEntry?.savedStateHandle?.set("channels_searchQuery", searchQuery)
                                }
                            },
                            onClearSearch = {
                                searchQuery = ""
                                if (selectedTab == 0) {
                                    feedViewModel.refreshFeed()
                                }
                                // Also clear any saved channels search so clearing the bar truly resets Channels list
                                navController.currentBackStackEntry?.savedStateHandle?.set("channels_searchQuery", "")
                                focusManager.clearFocus()
                            },
                            placeholder = "Search...",
                            onSearch = { query -> searchQuery = query },
                            // Provide the menu button as leading content so it's inside the field
                            leadingContent = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Menu")
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        )
                    },
                    // remove separate navigation icon for the new in-field menu
                )
            },
            floatingActionButton = {
                // Show FAB only in Feed tab and when the feed list is scrolled to top
                if (selectedTab == 0 && feedAtTop) {
                    FloatingActionButton(
                        onClick = { navController.navigate(Routes.CREATE_SERVICE) }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Create New Service"
                        )
                    }
                }
            },
        ) { innerPadding ->
            val focusManager = LocalFocusManager.current
            Column(modifier = Modifier
                .padding(innerPadding)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
            ) {

                AnimatedVisibility(visible = feedAtTop, enter = fadeIn(), exit = fadeOut()) {
                    TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = {
                                selectedTab = index
                                when (index) {
                                    0 -> feedViewModel.subscribeToFeed()
                                    1 -> messagesViewModel.getConversations()
                                    2 -> {
                                        // ChannelsViewModel subscribes once in its init; nothing required here
                                    }
                                }
                            },
                            text = { Text(title) }
                        )
                    }
                    }
                }
                
                // SearchBar is now embedded in the TopAppBar title area

                // Also handle programmatic tab changes
                LaunchedEffect(selectedTab) {
                    when (selectedTab) {
                        0 -> feedViewModel.subscribeToFeed()
                        1 -> messagesViewModel.getConversations()
                        2 -> {
                            // When switching to Channels tab, restore any saved channel-specific search
                            val restoredChannelsSearch = navController.currentBackStackEntry?.savedStateHandle?.get<String>("channels_searchQuery")
                            if (!restoredChannelsSearch.isNullOrEmpty()) {
                                searchQuery = restoredChannelsSearch
                            }
                        }
                    }
                }

                when (selectedTab) {
                    0 -> FeedTab(
                        navController = navController,
                        userPubkey = userPubkey,
                        searchQuery = searchQuery,
                        feedViewModel = feedViewModel,
                        onAtTopChange = { atTop -> feedAtTop = atTop }
                    )
                    1 -> MessagesTab(
                        navController = navController,
                        userPubkey = userPubkey,
                        privateKey = privateKey,
                        messagesViewModel = messagesViewModel
                    )
                    2 -> ChannelsTab(
                        navController = navController,
                        userPubkey = userPubkey,
                        nostrClient = nostrClient,
                        subscriptionManager = subscriptionManager,
                        privateKey = privateKey.encodeToByteArray(),
                        // Pass the live searchQuery from the top bar so ChannelsTab updates immediately
                        searchQuery = searchQuery
                    )
                }
            }
        }
    }
}
// (Only one MainScreen composable is used by AppNavGraph; the full signature above is the canonical entrypoint.)
