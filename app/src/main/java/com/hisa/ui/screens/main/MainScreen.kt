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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.BottomAppBar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.Message
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hisa.ui.navigation.Routes
import com.hisa.ui.screens.feed.FeedTab
import com.hisa.ui.screens.shop.StallsTab
import com.hisa.ui.screens.messages.MessagesTab
import com.hisa.R
import androidx.hilt.navigation.compose.hiltViewModel
import com.hisa.data.cache.UiResumeStateStore
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.ui.components.SearchBar
import com.hisa.ui.components.OrderNotificationsDrawer
import com.hisa.util.Constants
import com.hisa.viewmodel.FeedViewModel
import com.hisa.viewmodel.MessagesViewModel
import com.hisa.viewmodel.OrderNotificationsViewModel

@Composable
fun DrawerNavActionItem(
    label: String,
    icon: ImageVector,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationDrawerItem(
        label = { M3Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            unselectedContainerColor = Color.Transparent,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = "$label Icon",
                modifier = Modifier.size(22.dp)
            )
        }
    )
}

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
    val notificationsViewModel: OrderNotificationsViewModel = hiltViewModel()
    
    val context = LocalContext.current
    val resumeStateStore = remember { UiResumeStateStore(context.applicationContext) }

    // Try to restore previously selected tab from NavController's SavedStateHandle so navigating
    // away and back (for example opening a channel chat) returns to the same tab.
    val currentEntry = navController.currentBackStackEntry
    val savedStateHandle = currentEntry?.savedStateHandle
    val restoredTab = savedStateHandle?.get<Int>("selectedTab") ?: resumeStateStore.selectedTab
    var selectedTab by rememberSaveable { mutableStateOf(restoredTab) }
    val tabStateHolder = rememberSaveableStateHolder()

    // Keep the saved state handle in sync whenever the selected tab changes
    LaunchedEffect(selectedTab) {
        savedStateHandle?.set("selectedTab", selectedTab)
        resumeStateStore.saveSelectedTab(selectedTab)
    }
    
    // Start feed loading once when the main screen is shown so the tab switch stays lightweight.
    LaunchedEffect(Unit) {
        feedViewModel.subscribeToFeed()
    }

    // Start listening for seller orders when user pubkey changes
    LaunchedEffect(userPubkey) {
        if (userPubkey.isNotBlank()) {
            notificationsViewModel.startListeningForOrders(userPubkey)
        }
    }

    var searchQuery by rememberSaveable { mutableStateOf(resumeStateStore.searchQuery) }
    var showDialog by remember { mutableStateOf(showWelcomeDialog) }
    var showNotificationsDrawer by remember { mutableStateOf(false) }
    // Order changed so Create is in the middle and MyShop is at the end: Feed | Messages | Create | Stalls | MyShop
    val tabs = listOf("Feed", "Messages", "Create", "Stalls", "My Shop")
    val tabIcons = listOf(
        Icons.Filled.Home,
        Icons.AutoMirrored.Filled.Message,
        Icons.Filled.AddCircle,
        Icons.Filled.Storefront,
        Icons.Filled.ShoppingBag
    )
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var feedAtTop by remember { mutableStateOf(true) }

    LaunchedEffect(searchQuery) {
        resumeStateStore.saveSearchQuery(searchQuery)
    }

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
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 320.dp),
                drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            brush = Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                )
                            )
                        )
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.png_hisa),
                            contentDescription = "Hisa logo",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            M3Text(
                                text = "Hisa",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            M3Text(
                                text = "Your local marketplace",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val drawerItems = listOf(
                    Triple("Profile", Icons.Default.PersonOutline, true) to { 
                        scope.launch { drawerState.close() }
                        navController.navigate("profile/$userPubkey")
                    },
                    Triple("Settings", Icons.Filled.Settings, false) to {
                        scope.launch { drawerState.close() }
                        navController.navigate(Routes.SETTINGS)
                    },
                    Triple("FAQs", Icons.AutoMirrored.Filled.HelpOutline, false) to {
                        scope.launch { drawerState.close() }
                        navController.navigate(Routes.FAQ)
                    },
                    Triple("Donate", Icons.Default.WaterDrop, false) to {
                        scope.launch { drawerState.close() }
                        navController.navigate(Routes.DONATE)
                    },
                    Triple("Support", Icons.Default.SupportAgent, false) to {
                        scope.launch { drawerState.close() }
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(Constants.SUPPORT_EMAIL))
                            putExtra(Intent.EXTRA_SUBJECT, Constants.SUPPORT_SUBJECT)
                        }
                        navController.context.startActivity(Intent.createChooser(intent, "Send email"))
                    }
                )

                drawerItems.forEachIndexed { _, item ->
                    val (label, icon, selected) = item.first
                    DrawerNavActionItem(
                        label = label,
                        icon = icon,
                        selected = selected,
                        onClick = item.second
                    )
                }
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
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        )
                    },
                    actions = {
                        // Notification bell with badge
                        val unreadCount = notificationsViewModel.unreadCount.collectAsState().value
                        Box {
                            IconButton(onClick = { showNotificationsDrawer = true }) {
                                Icon(
                                    Icons.Default.NotificationsActive,
                                    contentDescription = "Notifications",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            
                            // Unread count badge
                            if (unreadCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .align(Alignment.TopEnd)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                            fontSize = 10.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onError
                                    )
                                }
                            }
                        }
                    },
                    // remove separate navigation icon for the new in-field menu
                )
            },
            floatingActionButton = {
                // FAB removed - Create is now a bottom tab
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 2.dp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    tabs.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (index == 2) {
                                    navController.navigate(Routes.CREATE_SERVICE)
                                } else if (selectedTab != index) {
                                    selectedTab = index
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = tabIcons[index],
                                    contentDescription = title,
                                    modifier = Modifier.size(if (index == 2) 24.dp else 20.dp)
                                )
                            },
                            label = { Text(title, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ),
                            alwaysShowLabel = true
                        )
                    }
                }
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
                        0 -> tabStateHolder.SaveableStateProvider(key = "feed_tab") {
                            FeedTab(
                                navController = navController,
                                userPubkey = userPubkey,
                                searchQuery = searchQuery,
                                feedViewModel = feedViewModel,
                                onAtTopChange = { atTop -> feedAtTop = atTop },
                                onSeeAllStalls = {
                                    selectedTab = 3
                                    navController.currentBackStackEntry?.savedStateHandle?.set("stalls_searchQuery", "")
                                }
                            )
                        }
                        1 -> tabStateHolder.SaveableStateProvider(key = "messages_tab") {
                            MessagesTab(
                                navController = navController,
                                userPubkey = userPubkey,
                                privateKey = privateKey,
                                messagesViewModel = messagesViewModel
                            )
                        }
                        2 -> { /* Create tab – navigation handled on click */ }
                        3 -> tabStateHolder.SaveableStateProvider(key = "stalls_tab") {
                            com.hisa.ui.screens.shop.StallsTab(
                                navController = navController,
                                userPubkey = userPubkey,
                                nostrClient = nostrClient,
                                subscriptionManager = subscriptionManager,
                                privateKey = privateKey.encodeToByteArray(),
                                searchQuery = searchQuery
                            )
                        }
                        4 -> tabStateHolder.SaveableStateProvider(key = "shop_tab") {
                            com.hisa.ui.screens.shop.ShopScreen(
                                navController = navController,
                                userPubkey = userPubkey
                            )
                        }
                    }
                }

            }
        }
        
        // Order Notifications Drawer
        if (showNotificationsDrawer) {
            OrderNotificationsDrawer(
                notificationsViewModel = notificationsViewModel,
                onDismiss = { showNotificationsDrawer = false },
                onOrderClick = { order ->
                    showNotificationsDrawer = false
                    val conversationPubkey = order.counterpartyPubkey(userPubkey)
                    if (conversationPubkey.isNotBlank()) {
                        navController.navigate(
                            Routes.DM_ORDER
                                .replace("{pubkey}", Uri.encode(conversationPubkey))
                                .replace("{orderId}", Uri.encode(order.orderId))
                        )
                    }
                }
            )
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
