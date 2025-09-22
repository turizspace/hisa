package com.hisa.auth

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.hisa.HisaApp
import com.hisa.ui.navigation.AppNavGraph
import com.hisa.ui.util.LocalProfileMetaUtil
import com.hisa.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HisaApp
        setContent {
            val isDarkThemeState = authViewModel.darkTheme.collectAsState()
            val isDarkTheme = isDarkThemeState.value

            // Log theme changes for debugging
            LaunchedEffect(isDarkTheme) {
                android.util.Log.i("LoginActivity", "isDarkTheme changed: $isDarkTheme, authViewModelHash=${authViewModel.hashCode()}")
            }

            androidx.compose.runtime.CompositionLocalProvider(
                LocalProfileMetaUtil provides app.profileMetaUtil
            ) {
                // Apply theme at the root of the composition
                MaterialTheme(
                    colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()
                ) {
                    AppNavGraph(
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