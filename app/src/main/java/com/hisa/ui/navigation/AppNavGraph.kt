package com.hisa.ui.navigation

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
//noinspection UsingMaterialAndMaterial3Libraries
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResult
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import com.hisa.data.nostr.ExternalSignerManager
import com.hisa.ui.screens.login.LoginScreen
import com.hisa.ui.screens.main.MainScreen
import com.hisa.ui.screens.profile.ProfileScreen
import com.hisa.ui.screens.settings.SettingsScreen
import com.hisa.ui.screens.conversation.ConversationScreen
import com.hisa.ui.screens.create.CreateServiceScreen
import com.hisa.ui.screens.signup.SignupScreen
import com.hisa.viewmodel.AuthViewModel
import com.hisa.viewmodel.MessagesViewModel
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.ui.screens.channels.ChannelChatScreen
import com.hisa.ui.screens.shop.ShopScreen
import com.hisa.ui.screens.donate.DonateScreen
import com.hisa.ui.screens.lists.ChannelDetailScreen
import com.hisa.ui.screens.faq.FAQScreen

object Routes {
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val MAIN = "main?showDialog={showDialog}"
    const val PROFILE = "profile/{pubkey}"
    const val CHANNEL_DETAIL = "channelDetail"
    const val CHANNEL_CHAT = "channelChat"
    const val SETTINGS = "settings"
    const val CONVERSATION = "conversation/{conversationId}"
    const val DM = "dm/{pubkey}"
    const val SERVICE_DETAIL = "serviceDetail/{eventId}/{pubkey}"
    const val CREATE_SERVICE = "createService"
    const val CREATE_CHANNEL = "createChannel"
    const val SHOP = "shop"
    const val FAQ = "faq"
    const val DONATE = "donate"
    const val UPLOAD = "upload"
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun SettingsScreenWrapper(
    navController: NavHostController,
    messagesViewModel: MessagesViewModel,
    authViewModel: AuthViewModel,
    isDarkTheme: Boolean = false,
    onToggleTheme: () -> Unit = {}
) {
    var didLogout by remember { mutableStateOf(false) }
    if (didLogout) {
        LaunchedEffect(Unit) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
            didLogout = false
        }
    }
    val relayList by authViewModel.relays.collectAsState()
    val currentPubKey by authViewModel.pubKey.collectAsState(initial = "")
    androidx.compose.runtime.LaunchedEffect(currentPubKey, isDarkTheme) {
        android.util.Log.i("AppNavGraph", "Opening Settings: pubKey=$currentPubKey isDarkTheme=$isDarkTheme")
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        android.util.Log.i("AppNavGraph", "SettingsScreenWrapper authViewModel hash=${authViewModel.hashCode()}")
    }
    SettingsScreen(
        authViewModel = authViewModel,
        onLogout = {
            messagesViewModel.clearMessages()
            authViewModel.logout()
            didLogout = true
        },
        onLoggedOut = { },
        onExportKey = {},
        isDarkTheme = isDarkTheme,
        onToggleTheme = onToggleTheme
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AppNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    authViewModel: AuthViewModel,
    nostrClient: NostrClient,
    subscriptionManager: SubscriptionManager,
    isDarkTheme: Boolean = false,
    onToggleTheme: () -> Unit = {}
) {
    // Ensure theme changes trigger recomposition across all screens
    LaunchedEffect(isDarkTheme) {
        android.util.Log.i("AppNavGraph", "Theme changed in AppNavGraph: isDarkTheme=$isDarkTheme, authViewModelHash=${authViewModel.hashCode()}")
    }
    val initState by authViewModel.initState.collectAsState()
    val pubKeyState = authViewModel.pubKey.collectAsState(initial = "")
    val pubKey = pubKeyState.value ?: ""
    val privateKeyState = authViewModel.privateKey.collectAsState(initial = "")
    val privateKey = privateKeyState.value ?: ""

    val messagesViewModel: MessagesViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = pubKey)

    // ActivityResult launcher used to receive foreground responses from external signer apps
    val signerActivityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result: ActivityResult ->
            val data: Intent? = result.data
            if (data != null) {
                // Forward intent data to ExternalSignerManager so the registered NostrSignerExternal can process it
                ExternalSignerManager.newResponse(data)
            }
        }
    )

    // Register a foreground launcher that will receive Intents from NostrSignerExternal and launch them
    val foregroundLauncher: (Intent) -> Unit = { intent: Intent ->
        signerActivityLauncher.launch(intent)
    }

    // Register/unregister lifecycle-aware
    DisposableEffect(Unit) {
        ExternalSignerManager.registerForegroundLauncher(foregroundLauncher)
        onDispose {
            ExternalSignerManager.unregisterForegroundLauncher(foregroundLauncher)
        }
    }
    
    when (initState) {
        is AuthViewModel.InitState.Loading -> {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator()
            }
            return
        }
        is AuthViewModel.InitState.Ready -> {
            // Continue with navigation setup
        }
    }


    LaunchedEffect(authViewModel.loginSuccess.collectAsState().value) {
        val isLoggedIn = authViewModel.loginSuccess.value
        val currentRoute = navController.currentDestination?.route

        if (isLoggedIn && currentRoute == Routes.LOGIN) {
            navController.navigate(Routes.MAIN) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }

    }

    val startRoute = when (val state = initState) {
        is AuthViewModel.InitState.Loading -> Routes.LOGIN
        is AuthViewModel.InitState.Ready -> if (state.initialRoute == "main") Routes.MAIN else Routes.LOGIN
    }

    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                viewModel = authViewModel,
                onLoginSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onSignupSuccessNavigate = {


                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.SIGNUP) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToSettings = {


                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.SIGNUP) { inclusive = true }
                        launchSingleTop = true
                    }
                    navController.navigate(Routes.SETTINGS)
                },
                
                navController = navController
            )

        }

        composable(Routes.SIGNUP) {
            SignupScreen(
                viewModel = authViewModel,
                onSignupSuccess = {
                    navController.navigate("main?showDialog=true") {

                        popUpTo(Routes.SIGNUP) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToSettings = {

                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.SIGNUP) { inclusive = true }
                        launchSingleTop = true
                    }
                    navController.navigate(Routes.SETTINGS)
                },

                navController = navController
            )
        }

        composable(
            route = "main?showDialog={showDialog}",
            arguments = listOf(
                androidx.navigation.navArgument("showDialog") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = "false"
                }
            )
        ) { backStackEntry ->
            val showDialogArg = backStackEntry.arguments?.getString("showDialog") ?: "false"
            val showDialog = showDialogArg == "true"
            MainScreen(
                navController = navController,
                userPubkey = pubKey,
                privateKey = privateKey,
                nostrClient = nostrClient,
                subscriptionManager = subscriptionManager,
                messagesViewModel = messagesViewModel,
                showWelcomeDialog = showDialog,
                onDialogDismissed = { /* no-op */ }
            )
        }

        composable(Routes.PROFILE) { backStackEntry ->
            val pubkey = backStackEntry.arguments?.getString("pubkey") ?: ""
            if (pubkey.isNotBlank()) {
                ProfileScreen(pubkey, null, null, null, navController = navController)
            } else {
                Text("Profile not found")
            }
        }
        // Shop route for user's shop
        composable(Routes.SHOP) {
            ShopScreen(navController = navController, userPubkey = pubKey)
        }
        // ...existing code...
        composable(Routes.SETTINGS) {
            SettingsScreenWrapper(
                navController = navController,
                messagesViewModel = messagesViewModel,
                authViewModel = authViewModel,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme
            )
        }
        
        composable(Routes.FAQ) {
            FAQScreen()
        }

        composable(Routes.DONATE) {
            DonateScreen()
        }
        composable(Routes.UPLOAD) { backStackEntry ->
            // Hilt-injected view models
            val uploadViewModel: com.hisa.viewmodel.UploadViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val authVm: com.hisa.viewmodel.AuthViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            com.hisa.ui.screens.upload.UploadScreen(
                uploadViewModel = uploadViewModel,
                navController = navController,
                onUploadComplete = { url ->
                    android.util.Log.i("AppNavGraph", "UPLOAD onUploadComplete called with url=$url")
                    // Put URL into previous back stack entry's savedStateHandle so caller can read it.
                    // Also write into the current back stack entry as a robust fallback because
                    // some screens observe the current entry's SavedStateHandle instead of the previous one.
                    val previousEntry = navController.previousBackStackEntry
                    val currentEntry = navController.currentBackStackEntry
                    android.util.Log.i("AppNavGraph", "previousBackStackEntry route=${previousEntry?.destination?.route} currentRoute=${currentEntry?.destination?.route}")
                    try {
                        previousEntry?.savedStateHandle?.set("uploaded_media_url", url)
                        currentEntry?.savedStateHandle?.set("uploaded_media_url", url)
                        android.util.Log.i("AppNavGraph", "Set uploaded_media_url on target savedStateHandle(s)")
                    } catch (e: Exception) {
                        android.util.Log.w("AppNavGraph", "Failed to set uploaded_media_url on savedStateHandle: ${e.message}")
                    }
                    // Safety: ensure Upload screen is popped so the caller can observe the savedStateHandle
                    try {
                        android.util.Log.i("AppNavGraph", "Attempting popBackStack from AppNavGraph")
                        navController.popBackStack()
                    } catch (e: Exception) {
                        android.util.Log.w("AppNavGraph", "popBackStack failed: ${e.message}")
                    }
                }
            )
        }
    composable(Routes.CONVERSATION) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            ConversationScreen(
                conversationId = conversationId,
                userPubkey = pubKey,
        privateKey = privateKey,  // Using the non-null privateKey from top level
        messagesViewModel = messagesViewModel,
        navController = navController
            )
        }
        composable(Routes.CREATE_SERVICE) {
            val vm: com.hisa.ui.screens.create.CreateServiceViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            CreateServiceScreen(
                onCreateService = { title, summary, description, tags, onSuccess ->
                    // If there is no local private key (external signer login), pass null so
                    // CreateServiceViewModel delegates to the external signer path.
                    vm.createService(title, summary, description, tags, if (privateKey.isBlank()) null else privateKey, pubKey, onSuccess)
                },
                onNavigateBack = { navController.popBackStack() },
                navController = navController
            )
        }
        composable(Routes.CREATE_CHANNEL) {
            val vm: com.hisa.ui.screens.create.CreateChannelViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            // Get ChannelsViewModel for refresh
            val pkBytesForChannels: ByteArray? = if (privateKey.isNotBlank()) {
                try { privateKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray() } catch (e: Exception) { null }
            } else null
            val channelsViewModel: com.hisa.viewmodel.ChannelsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = com.hisa.viewmodel.ChannelsViewModelFactory(
                    nostrClient = nostrClient,
                    subscriptionManager = subscriptionManager,
                    privateKey = pkBytesForChannels,
                    pubkey = pubKey
                )
            )
                vm.userPubkey = pubKey
            com.hisa.ui.screens.create.CreateChannelScreen(
                onCreateChannel = { name, about, picture, relays, categories, onSuccess ->
                    vm.createChannel(
                        name = name,
                        about = about,
                        picture = picture,
                        relays = relays,
                        categories = categories,
                        privateKey = privateKey,
                        onSuccess = { newChannelId ->
                            onSuccess(newChannelId)
                        }
                    )
                },
                onNavigateToChannel = { channelId ->
                     if (channelId.isNotBlank()) {
                        // Find channel and navigate to chat
                        val channel = channelsViewModel.channels.value.find { it.id == channelId }
                        if (channel != null) {
                            navController.currentBackStackEntry?.savedStateHandle?.apply {
                                set("channelId", channel.id)
                                set("channelName", channel.name)
                                set("channelPicture", channel.picture)
                            }
                            navController.navigate(Routes.CHANNEL_CHAT)
                        } else {
                            navController.popBackStack()
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onRefreshChannels = { channelsViewModel.refreshChannels() },
                navController = navController
             )
        }
        composable(Routes.CHANNEL_CHAT) { backStackEntry ->
            val channelId = backStackEntry.savedStateHandle.get<String>("channelId")
            val channelName = backStackEntry.savedStateHandle.get<String>("channelName")
            val channelPicture = backStackEntry.savedStateHandle.get<String>("channelPicture")
            
            android.util.Log.d("AppNavGraph", "Composing CHANNEL_CHAT route with id: $channelId, name: $channelName")
            
            if (channelId != null && channelName != null) {
                val userPrivateKey by authViewModel.privateKey.collectAsState()
                val userPubkey by authViewModel.pubKey.collectAsState()

                // Store in local variables for smart casting
                val currentPrivateKey = userPrivateKey?.toString()
                val currentPubkey = userPubkey?.toString()

                // Allow composing ChannelChatScreen if we have at least the current pubkey.
                // If there's no local private key (external signer), pass an empty ByteArray so
                // the screen composes. Sending should be guarded in the ViewModel/UI.
                if (currentPubkey != null) {
                    val privateKeyBytes: ByteArray? = if (!currentPrivateKey.isNullOrBlank()) {
                        // Convert hex string to ByteArray
                        try {
                            currentPrivateKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }

                    val externalPub = authViewModel.getExternalSignerPubkey()
                    val externalPackage = authViewModel.getExternalSignerPackage()

                    ChannelChatScreen(
                        channelId = channelId,
                        channelName = channelName,
                        channelPicture = channelPicture ?: "",
                        userPubkey = currentPubkey,
                        privateKey = privateKeyBytes,
                        nostrClient = nostrClient,
                        subscriptionManager = subscriptionManager,
                        navController = navController,
                        externalSignerPubkey = externalPub,
                        externalSignerPackage = externalPackage
                    )
                }
            }
        }

        composable(Routes.CHANNEL_DETAIL) { backStackEntry ->
            // we store the kind-40 creation event id as "channelEventId" in SavedStateHandle
            val channelEventId = backStackEntry.savedStateHandle.get<String>("channelEventId")
            if (!channelEventId.isNullOrBlank()) {
                val pkBytesForDetail: ByteArray? = if (privateKey.isNotBlank()) {
                    try { privateKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray() } catch (e: Exception) { null }
                } else null
                ChannelDetailScreen(
                    channelId = channelEventId,
                    nostrClient = nostrClient,
                    subscriptionManager = subscriptionManager,
                    privateKey = pkBytesForDetail,
                    userPubkey = pubKey
                )
            } else {
                Text("Channel not found")
            }
        }
        composable(Routes.DM) { backStackEntry ->
            val pubkey = backStackEntry.arguments?.getString("pubkey") ?: ""
            ConversationScreen(
                conversationId = pubkey,
                userPubkey = pubKey,
                privateKey = privateKey,  // Using the non-null privateKey from top level
                messagesViewModel = messagesViewModel,
                navController = navController
            )
        }
        
        composable(
            route = Routes.SERVICE_DETAIL,
            arguments = listOf(
                androidx.navigation.navArgument("eventId") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("pubkey") { type = androidx.navigation.NavType.StringType }
            )
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getString("eventId")
            val pubkey = backStackEntry.arguments?.getString("pubkey")
            if (eventId != null && pubkey != null && pubkey.isNotBlank()) {
                com.hisa.ui.screens.details.ServiceDetailScreen(
                    eventId = eventId,
                    pubkey = pubkey,
                    onBack = { navController.popBackStack() },
                    navController = navController
                )
            } else {
                androidx.compose.material3.Text("Service not found.")
            }
        }
    }

    }
