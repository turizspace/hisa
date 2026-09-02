package com.hisa.ui.screens.splash

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.hisa.auth.LoginActivity
import com.hisa.ui.screens.main.MainActivity
import com.hisa.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class StartupUiState {
    Loading,
    Recovery
}

@AndroidEntryPoint
class SplashActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()
    private var isKeepingSplashScreen = true
    private var routingJob: Job? = null
    private var didNavigate = false
    private val startupUiState = mutableStateOf(StartupUiState.Loading)
    private val startupError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            StartupScreen(
                state = startupUiState.value,
                errorMessage = startupError.value,
                onRetry = {
                    authViewModel.retryInitialization()
                    beginRouting()
                }
            )
        }

        // Keep showing the platform splash only for the bounded local restore.
        splashScreen.setKeepOnScreenCondition { isKeepingSplashScreen }
        beginRouting()
    }

    private fun beginRouting() {
        routingJob?.cancel()
        startupUiState.value = StartupUiState.Loading
        startupError.value = null
        routingJob = lifecycleScope.launch {
            val initState = try {
                withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
                    authViewModel.initState.first { state ->
                        state is AuthViewModel.InitState.Ready ||
                        state is AuthViewModel.InitState.Failed
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SplashActivity", "Failed to resolve initial route", e)
                null
            }

            when (initState) {
                is AuthViewModel.InitState.Ready -> navigate(initState.initialRoute)
                is AuthViewModel.InitState.Failed -> showRecovery(initState.message)
                null -> showRecovery("Startup is taking longer than expected.")
                is AuthViewModel.InitState.Loading -> showRecovery("Startup is taking longer than expected.")
            }
        }
    }

    private fun navigate(initialRoute: String) {
        if (didNavigate) return
        didNavigate = true
        val intent = if (initialRoute == "main") {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, LoginActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        isKeepingSplashScreen = false
        startActivity(intent)
        overridePendingTransition(0, 0)
        finish()
    }

    private fun showRecovery(message: String) {
        isKeepingSplashScreen = false
        startupError.value = message
        startupUiState.value = StartupUiState.Recovery
    }

    override fun onDestroy() {
        routingJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val STARTUP_TIMEOUT_MS = 3_000L
    }
}

@androidx.compose.runtime.Composable
private fun StartupScreen(
    state: StartupUiState,
    errorMessage: String?,
    onRetry: () -> Unit
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (state == StartupUiState.Loading) {
                        CircularProgressIndicator()
                        Text(
                            text = "Restoring your local session…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            text = "Hisa could not finish starting",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = errorMessage ?: "Please try again.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}
