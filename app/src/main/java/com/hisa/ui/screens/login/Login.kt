package com.hisa.ui.screens.login

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.hisa.R
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import com.hisa.viewmodel.AuthViewModel
import com.hisa.ui.screens.signup.SignupScreen


@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoginSuccess: () -> Unit = {},
    // Called when signup completes while LoginScreen is showing the signup overlay.
    // Implementations should navigate to MAIN (or equivalent) and remove LOGIN from the back stack.
    onSignupSuccessNavigate: () -> Unit = {},
    // Called when the signup screen requests navigation to Settings (Backup Keys Now).
    onNavigateToSettings: () -> Unit = {},
    // (removed) openUpload
    // Optional NavController to forward into Signup so it can observe saved state
    navController: androidx.navigation.NavHostController? = null
) {
    var nsec by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var showSignup by rememberSaveable { mutableStateOf(false) }

    val loginSuccess by viewModel.loginSuccess.collectAsState()
    val isLoading = attempted && !loginSuccess
    val isLoggingOut by viewModel.isLoggingOut.collectAsState()

    var clearingData by remember { mutableStateOf(false) }



    // Handler to navigate directly after signup completes when the signup UI is shown as an overlay.
    // We intentionally navigate directly so the underlying LOGIN UI isn't briefly revealed.
    val handleSignupSuccess = {
    // Navigate first so LOGIN is popped immediately and won't briefly flash
    // underneath the signup overlay; then hide the overlay locally.
    onSignupSuccessNavigate()
    showSignup = false
    }

    // Upload-to-signup coordination removed — signup overlay is opened only by UI actions.

    // External signer (Amber) launcher -------------------------------------------------
    val externalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val resultData = result.data
        if (result.resultCode == Activity.RESULT_OK && resultData != null) {
            try {
                val pubkey = resultData.getStringExtra("result")
                val signerPackage = resultData.getStringExtra("package")
                if (!pubkey.isNullOrBlank() && !signerPackage.isNullOrBlank()) {
                    // Persist and trigger login via ViewModel
                    viewModel.updateKeyFromExternal(pubkey)
                    viewModel.loginWithExternalSigner(signerPackage)
                } else {
                    Log.w("ExternalSigner", "Invalid result from signer app")
                }
            } catch (e: Exception) {
                Log.e("ExternalSigner", "Error parsing signer result", e)
            }
        } else {
            Log.i("ExternalSigner", "Signer activity cancelled or no data")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showSignup) {
            SignupScreen(
                viewModel = viewModel,
                onSignupSuccess = handleSignupSuccess,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToLogin = { showSignup = false },
                navController = navController,
            )
        } else {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(32.dp))
                    // App Logo
                    Image(
                        painter = painterResource(id = R.drawable.png_hisa),
                        contentDescription = "Hisa App Logo",
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(24.dp))
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    // Welcome Text
                    Text(
                        text = "Welcome to Hisa",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Sell skills and Other stuff. Secure, Social, Simple",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    // Login Form
                    Text(stringResource(R.string.login_with_nostr_nsec), style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = nsec,
                        onValueChange = {
                            nsec = it
                            attempted = false
                        },
                        label = { Text(stringResource(R.string.nsec)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            attempted = true
                            viewModel.loginWithNsec(nsec)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(stringResource(R.string.login), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    // Login with Amber (external signer)
                    Button(
                        onClick = {
                            try {
                                // Build an intent that matches the Amber/Quartz GET_PUBLIC_KEY request shape.
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, "nostrsigner:".toUri())
                                intent.putExtra("type", "get_public_key")
                                // Minimal permissions payload; Amber will accept and show the user the request.
                                intent.putExtra("permissions", "[]")
                                externalLauncher.launch(intent)
                            } catch (e: Exception) {
                                Log.e("ExternalSigner", "Error launching signer", e)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text("Login with Amber")
                    }
                    if (clearingData) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Clearing previous data...", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (attempted && !loginSuccess && nsec.isNotEmpty() && !isLoading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.invalid_nsec_or_login_failed), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("Don't have an account? ")
                        TextButton(onClick = { if (!isLoggingOut) showSignup = true }, enabled = !isLoggingOut) {
                            Text(if (isLoggingOut) "Please wait..." else "Create One", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}
