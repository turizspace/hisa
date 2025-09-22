package com.hisa.ui.screens.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hisa.auth.LoginActivity
import com.hisa.ui.screens.main.MainActivity
import com.hisa.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SplashActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()
    private var isKeepingSplashScreen = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Keep showing the splash screen until we're ready
        splashScreen.setKeepOnScreenCondition { isKeepingSplashScreen }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                lifecycleScope.launch {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                        try {
                            // Wait for initialization to complete
                            val initState = authViewModel.initState.first { it is AuthViewModel.InitState.Ready }
                            // Use the route from initState to determine where to go
                            val intent = when (initState) {
                                is AuthViewModel.InitState.Ready -> {
                                    if (initState.initialRoute == "main") {
                                        Intent(this@SplashActivity, MainActivity::class.java)
                                    } else {
                                        Intent(this@SplashActivity, LoginActivity::class.java)
                                    }
                                }
                                is AuthViewModel.InitState.Loading -> {
                                    Intent(this@SplashActivity, LoginActivity::class.java)
                                }
                            }
                            // Set flags to clear task and start fresh
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            // Only remove splash screen when we're ready to transition
                            isKeepingSplashScreen = false
                            startActivity(intent)
                            // No animation for transition
                            overridePendingTransition(0, 0)
                            finish()
                        } catch (e: Exception) {
                            // Log the error
                            e.printStackTrace()
                            // If all else fails, go to login
                            val loginIntent = Intent(this@SplashActivity, LoginActivity::class.java)
                            loginIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            isKeepingSplashScreen = false
                            startActivity(loginIntent)
                            overridePendingTransition(0, 0)
                            finish()
                        }
                    }
                }
            }
        }
    }
}
