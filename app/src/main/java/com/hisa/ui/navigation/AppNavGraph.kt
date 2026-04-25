package com.hisa.ui.navigation

import android.net.Uri
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
import com.hisa.ui.screens.shop.ShopScreen
import com.hisa.ui.screens.donate.DonateScreen
import com.hisa.ui.screens.lists.StallDetailScreen
import com.hisa.ui.screens.faq.FAQScreen
import com.hisa.util.hexToByteArrayOrNull

object Routes {
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val MAIN = "main?showDialog={showDialog}"
    const val PROFILE = "profile/{pubkey}"
    const val STALL_DETAIL = "stallDetail/{stallId}/{ownerPubkey}/{eventId}"
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

    fun stallDetail(stallId: String, ownerPubkey: String, eventId: String): String =
        "stallDetail/${Uri.encode(stallId)}/${Uri.encode(ownerPubkey)}/${Uri.encode(eventId)}"
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
        // Trigger retry of any pending decryptions that were queued
        messagesViewModel.retryPendingDecryptions()
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
            com.hisa.ui.screens.upload.UploadScreen(
                uploadViewModel = uploadViewModel,
                navController = navController,
                onUploadComplete = { url ->
                    try {
                        navController.deliverUploadResult(url)
                    } catch (e: Exception) {
                        android.util.Log.w("AppNavGraph", "Failed to deliver upload result: ${e.message}")
                    }
                    try {
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
            // Reuse create channel route to create stalls (shops) using CreateServiceScreen -> createStall
            val vm: com.hisa.ui.screens.create.CreateServiceViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            com.hisa.ui.screens.create.CreateServiceScreen(
                onCreateService = { title, summary, description, tags, onSuccess ->
                    vm.createStall(title, summary, description, tags, if (privateKey.isBlank()) null else privateKey, pubKey) {
                        onSuccess()
                    }
                },
                onNavigateBack = { navController.popBackStack() },
                navController = navController
            )
        }
        composable(
            route = Routes.STALL_DETAIL,
            arguments = listOf(
                androidx.navigation.navArgument("stallId") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("ownerPubkey") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("eventId") { type = androidx.navigation.NavType.StringType }
            )
        ) { backStackEntry ->
            val stallId = backStackEntry.arguments?.getString("stallId")
            val ownerPubkey = backStackEntry.arguments?.getString("ownerPubkey")
            val eventId = backStackEntry.arguments?.getString("eventId")
            if (!stallId.isNullOrBlank() && !ownerPubkey.isNullOrBlank() && !eventId.isNullOrBlank()) {
                StallDetailScreen()
            } else {
                Text("Stall not found")
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
