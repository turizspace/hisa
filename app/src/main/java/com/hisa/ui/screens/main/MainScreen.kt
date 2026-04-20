package com.hisa.ui.screens.main


import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.BottomAppBar
import androidx.compose.material.icons.filled.Feed
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Store
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.FloatingActionButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hisa.ui.navigation.Routes
import com.hisa.ui.screens.feed.FeedTab
import com.hisa.ui.screens.shop.StallsTab
import com.hisa.ui.screens.messages.MessagesTab
import com.hisa.R
import androidx.hilt.navigation.compose.hiltViewModel
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.ui.components.SearchBar
import com.hisa.util.Constants
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
    // Initialize ViewModels first
    val feedViewModel: FeedViewModel = hiltViewModel()
    
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
    
    // Subscribe to feed on initial load if Feed tab is selected
    LaunchedEffect(Unit) {
        if (selectedTab == 0) {
            feedViewModel.subscribeToFeed()
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(showWelcomeDialog) }
    // Order changed so Create is in the middle and MyShop is at the end: Feed | Messages | Create | Stalls | MyShop
    val tabs = listOf("Feed", "Messages", "Create", "Stalls", "My Shop")
    val tabIcons = listOf(
        Icons.Filled.Feed,
        Icons.Filled.Mail,
        Icons.Filled.Add,
        Icons.Filled.Store,        // Stalls: Store icon (merchants/shops)
        Icons.Filled.ShoppingCart  // My Shop: Shopping cart (user's shop/listings)
    )
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var feedAtTop by remember { mutableStateOf(true) }

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
                                // Keep stalls saved state in sync when user types while on Stalls tab
                                if (selectedTab == 3) {
                                    navController.currentBackStackEntry?.savedStateHandle?.set("stalls_searchQuery", searchQuery)
                                }
                            },
                            onClearSearch = {
                                searchQuery = ""
                                if (selectedTab == 0) {
                                    feedViewModel.refreshFeed()
                                }
                                // Also clear any saved stalls search so clearing the bar truly resets Stalls list
                                navController.currentBackStackEntry?.savedStateHandle?.set("stalls_searchQuery", "")
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
                // FAB removed - Create is now a bottom tab
            },
            bottomBar = {
                // Empty - floating nav handled as overlay
            }
        ) { innerPadding ->
            val focusManager = LocalFocusManager.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Main content column – NO bottom padding so content can scroll under the nav menu
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { focusManager.clearFocus() })
                        }
                ) {
                    // Tab content – each tab MUST add its own bottom content padding
                    // (e.g., 80.dp) to prevent the last items from being hidden
                    // behind the floating navigation menu.
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
                        2 -> { /* Create tab – navigation handled on click */ }
                        3 -> com.hisa.ui.screens.shop.StallsTab(
                            navController = navController,
                            userPubkey = userPubkey,
                            nostrClient = nostrClient,
                            subscriptionManager = subscriptionManager,
                            privateKey = privateKey.encodeToByteArray(),
                            searchQuery = searchQuery
                        )
                        4 -> com.hisa.ui.screens.shop.ShopScreen(
                            navController = navController,
                            userPubkey = userPubkey
                        )
                    }
                }

                // Floating Navigation Menu – modern luxury design
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                        )
                        .shadow(
                            elevation = 12.dp,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                            clip = false
                        )
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(Color.Transparent)
                                .clickable {
                                    when (index) {
                                        0 -> {
                                            selectedTab = 0
                                            feedViewModel.subscribeToFeed()
                                        }
                                        1 -> selectedTab = 1
                                        2 -> {
                                            selectedTab = 0
                                            navController.navigate(Routes.CREATE_SERVICE)
                                        }
                                        3 -> selectedTab = 3
                                        4 -> selectedTab = 4
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val iconModifier = if (index == 2) {
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .padding(8.dp)
                            } else {
                                Modifier.size(24.dp)
                            }

                            Icon(
                                imageVector = tabIcons[index],
                                contentDescription = title,
                                tint = when {
                                    isSelected && index == 2 -> MaterialTheme.colorScheme.onPrimary
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = iconModifier
                            )
                        }
                    }
                }
            }
        }
    }
}
// (Only one MainScreen composable is used by AppNavGraph; the full signature above is the canonical entrypoint.)

@Preview(
    name = "MainScreen - Light Mode",
    showBackground = true,
    showSystemUi = true
)
@Composable
fun MainScreenPreview() {
    // Note: Preview is for UI layout visualization only.
    // Actual navigation and data operations require proper runtime initialization.
}
