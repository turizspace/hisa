package com.hisa.ui.screens.signup

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import com.hisa.viewmodel.AuthViewModel
import com.hisa.data.model.Metadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignupScreen(
    viewModel: AuthViewModel,
    onSignupSuccess: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    navController: NavHostController? = null
) {
    var showSuccessDialog by rememberSaveable { mutableStateOf(false) }
    val generatedNsec = remember { viewModel.generateNewKeyPair().first }

    var name by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val signupSuccess by viewModel.signupSuccess.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // No navigation saved-state observation needed for Signup after removing picture upload

    LaunchedEffect(signupSuccess) {
        if (signupSuccess && !showSuccessDialog) {
            showSuccessDialog = true
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(
                progress = { 0.5f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("Create Your Profile", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = about,
                onValueChange = { about = it },
                label = { Text("About") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Spacer(modifier = Modifier.height(8.dp))

            Spacer(modifier = Modifier.height(24.dp))

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Button(
                onClick = {
                    try {
                        errorMessage = null
                        val metadata = Metadata(
                            name = name,
                            about = about,
                            picture = null,
                            nip05 = null,
                            website = null
                        )
                        viewModel.completeSignup(generatedNsec, metadata)
                    } catch (e: Exception) {
                        errorMessage = e.message ?: "An error occurred during signup"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && about.isNotBlank()
            ) {
                Text("Create Account")
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Already have an account?")
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { onNavigateToLogin() }) {
                    Text("Login")
                }
            }
        }

        if (showSuccessDialog) {
            AlertDialog(
                onDismissRequest = { },
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                ),
                title = { Text("Welcome to Hisa! 🎉") },
                text = {
                    Column {
                        Text("Your account has been created successfully!")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("To ensure you never lose access to your account, we highly recommend backing up your keys.")
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Would you like to do this now?")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                showSuccessDialog = false
                                viewModel.clearSignupSuccess()
                                delay(200)
                                onNavigateToSettings()
                            }
                        }
                    ) {
                        Text("Backup Keys")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                // Trigger navigation first and let the caller hide/unmount
                                // the Signup UI (for overlay case the Login host will
                                // dismiss the overlay). Clearing the signup flag is
                                // delayed to avoid racing with navigation.
                                onSignupSuccess()
                                delay(300)
                                viewModel.clearSignupSuccess()
                            }
                        }
                    ) {
                        Text("Not Now")
                    }
                }
            )
        }
    }
}
