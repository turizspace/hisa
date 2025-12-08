package com.hisa.ui.screens.main

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import com.hisa.HisaApp
import com.hisa.ui.navigation.AppNavGraph
import com.hisa.ui.navigation.Routes
import com.hisa.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HisaApp
        setContent {
            val navController = rememberNavController()
            val pubKeyState = authViewModel.pubKey.collectAsState(initial = "")

            // App-level dark theme state (persisted via AuthViewModel)
            val isDarkThemeState = authViewModel.darkTheme.collectAsState()
            val isDarkTheme = isDarkThemeState.value

            // Log theme changes so we can verify MainActivity observes toggles
            LaunchedEffect(isDarkTheme) {
                Log.i("MainActivity", "isDarkTheme changed: $isDarkTheme, authViewModelHash=${authViewModel.hashCode()}")
            }

            // Log authViewModel identity at composition time so we can compare instances
            LaunchedEffect(Unit) {
                Log.i("MainActivity", "AuthViewModel instance hash=${authViewModel.hashCode()}")
            }

            androidx.compose.runtime.CompositionLocalProvider(
                com.hisa.ui.util.LocalProfileMetaUtil provides app.profileMetaUtil
            ) {
                // Directly read isDarkTheme so Compose will recompose MaterialTheme when it changes.
                val colors = if (isDarkTheme) darkColorScheme() else lightColorScheme()
                // Log at composition time so we can verify MaterialTheme recomposition happens.
                LaunchedEffect(isDarkTheme) {
                    Log.i("MainActivity", "Compose MaterialTheme recomposing. isDarkTheme=$isDarkTheme, authViewModelHash=${authViewModel.hashCode()}")
                }
                MaterialTheme(colorScheme = colors) {
                    AppNavGraph(
                        navController = navController,
                        authViewModel = authViewModel,
                        nostrClient = app.nostrClient,
                        subscriptionManager = app.subscriptionManager,
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = { authViewModel.toggleDarkTheme() }
                    )
                }
            }
        }
    }
}