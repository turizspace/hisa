package com.hisa.ui.screens.create

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Unified Create screen that allows users to switch between creating:
 * - Services/Products (kind 30402 - NIP-99 classified listings)
 * - Stalls/Shops (kind 30017 - marketplace stalls)
 *
 * Both modes use the same CreateServiceScreen UI but route to different
 * ViewModel functions (createService vs createStall).
 */
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateUnifiedScreen(
    onCreateService: (title: String, summary: String, description: String, tags: List<List<String>>, onSuccess: () -> Unit) -> Unit,
    onCreateStall: (title: String, summary: String, description: String, tags: List<List<String>>, onSuccess: () -> Unit) -> Unit,
    onNavigateBack: () -> Unit,
    navController: NavHostController? = null
) {
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    
    val tabs = listOf("Create Service", "Create Stall")

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with back button and title
        TopAppBar(
            title = { Text(tabs[selectedTabIndex]) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
        )
        
        // Tab selector
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = index == selectedTabIndex,
                    onClick = { selectedTabIndex = index },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    text = {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }
        }

        // Content based on selected tab
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 0.dp)
        ) {
            when (selectedTabIndex) {
                0 -> {
                    // Create Service tab (NIP-99)
                    CreateServiceScreen(
                        onCreateService = onCreateService,
                        onNavigateBack = onNavigateBack,
                        navController = navController
                    )
                }
                1 -> {
                    // Create Stall tab (NIP-15)
                    CreateStallScreen(
                        onCreateStall = onCreateStall,
                        onNavigateBack = onNavigateBack,
                        navController = navController
                    )
                }
            }
        }
    }
}
