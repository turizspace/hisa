package com.hisa.ui.screens.splash

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
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
            try {
                val initState = authViewModel.initState.first { it is AuthViewModel.InitState.Ready }
                val intent = when (initState) {
                    is AuthViewModel.InitState.Ready -> {
                        if (initState.initialRoute == "main") {
                            Intent(this@SplashActivity, MainActivity::class.java)
                        } else {
                            Intent(this@SplashActivity, LoginActivity::class.java)
                        }
                    }
                    is AuthViewModel.InitState.Loading -> Intent(this@SplashActivity, LoginActivity::class.java)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                isKeepingSplashScreen = false
                startActivity(intent)
                overridePendingTransition(0, 0)
                finish()
            } catch (e: Exception) {
                Log.e("SplashActivity", "Failed to resolve initial route", e)
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
