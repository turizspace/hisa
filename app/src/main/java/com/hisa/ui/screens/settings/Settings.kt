package com.hisa.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import com.hisa.viewmodel.AuthViewModel
import com.hisa.util.Constants
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController

@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel = hiltViewModel(),
    onLogout: () -> Unit,
    onLoggedOut: () -> Unit = {},
    onExportKey: () -> Unit = {},
    // onImportKey removed
    isDarkTheme: Boolean = false,
    onToggleTheme: () -> Unit = {}
) {
    // Use the provided AuthViewModel (defaulting to activity-scoped hiltViewModel)
    val pubkey by authViewModel.pubKey.collectAsState(initial = "")
    val nsec = authViewModel.getStoredNsec() ?: ""
    var newRelay by remember { mutableStateOf("") }
    var showPrivKey by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // importKey and showImportDialog removed
    var showLogoutDialog by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current
    var showCopiedSnackbar by remember { mutableStateOf(false) }

    // Observe relays directly from the shared ViewModel (NIP-65 fetched or persisted)
    val relayListState by authViewModel.relays.collectAsState()
    val relayList = relayListState

    val scrollState = rememberScrollState()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(scrollState)
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))

                // Relay management
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Relays", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { authViewModel.refreshPreferredRelays() }) {
                        Text("Refresh")
                    }
                }

                // Show relays fetched via NIP-65 or persisted ones
                relayList.forEach { relay ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(relay, modifier = Modifier.weight(1f))
                        IconButton(onClick = { authViewModel.removeRelay(relay) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove Relay")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newRelay,
                        onValueChange = { newRelay = it },
                        label = { Text("Add Relay URL") },
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        if (newRelay.isNotBlank()) {
                            authViewModel.addRelay(newRelay)
                            newRelay = ""
                        }
                    }) { Text("Add") }
                }

                Spacer(Modifier.height(16.dp))

                // Key management
                Text("Keys", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = pubkey ?: "",
                    onValueChange = {},
                    label = { Text("Public Key") },
                    readOnly = true,
                    trailingIcon = {
                            IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(pubkey ?: ""))
                            showCopiedSnackbar = true
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Public Key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Modern nsec/private key disclaimer
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Warning: Your nsec (private key) gives full access to your account.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Never share or reveal your nsec to anyone. If someone gets your nsec, they can control your account and assets. Only copy it if you know what you're doing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                OutlinedTextField(
                    value = nsec,
                    onValueChange = {},
                    label = { Text("Private Key (nsec)") },
                    readOnly = true,
                    visualTransformation = if (showPrivKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { showPrivKey = !showPrivKey }) {
                                Icon(
                                    imageVector = if (showPrivKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPrivKey) "Hide Private Key" else "Show Private Key"
                                )
                            }
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(nsec))
                                onExportKey()
                                showCopiedSnackbar = true
                            }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Private Key")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Import Key button and dialog removed

                Spacer(Modifier.height(16.dp))

                // Theme toggle
                            // Observe persisted reactive theme directly from AuthViewModel so the toggle
                            // remains interactive regardless of how Settings was reached (e.g., from signup dialog).
                            val darkThemeState by authViewModel.darkTheme.collectAsState()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Dark Theme")
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = darkThemeState,
                                    onCheckedChange = {
                                        // Log for debugging: ensure the handler is invoked.
                                        Log.i("Settings", "Theme switch clicked. currentValue=$darkThemeState, authViewModelHash=${authViewModel.hashCode()}")
                                        authViewModel.toggleDarkTheme()
                                        Log.i("Settings", "After toggle, viewModel darkTheme=${authViewModel.darkTheme.value}, authViewModelHash=${authViewModel.hashCode()}")
                                    }
                                )
                            }

                Spacer(Modifier.height(16.dp))

                // Logout
                Button(
                    onClick = { showLogoutDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Logout", color = MaterialTheme.colorScheme.onError)
                }

                if (showLogoutDialog) {
                    AlertDialog(
                        onDismissRequest = { showLogoutDialog = false },
                        title = { Text("Confirm Logout") },
                        text = { Text("Are you sure you want to logout? This will clear your credentials.") },
                        confirmButton = {
                            Button(onClick = {
                                onLogout()
                                showLogoutDialog = false
                                onLoggedOut()
                            }) { Text("Logout") }
                        },
                        dismissButton = {
                            Button(onClick = { showLogoutDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }

            // Snackbar should be placed inside Box's content to use Modifier.align
            if (showCopiedSnackbar) {
                LaunchedEffect(Unit) {
                    snackbarHostState.showSnackbar("Private key copied to clipboard")
                    showCopiedSnackbar = false
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
