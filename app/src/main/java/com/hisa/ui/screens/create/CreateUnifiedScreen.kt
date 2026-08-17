package com.hisa.ui.screens.create

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hisa.data.model.ShippingZone
import com.hisa.ui.components.HisaScreenScaffold
import com.hisa.ui.components.HisaTabRow
import com.hisa.ui.navigation.NAV_RESULT_EDIT_STALL_PAYLOAD

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
    onCreateStall: (stallId: String, title: String, summary: String, description: String, currency: String, shippingZones: List<ShippingZone>, tags: List<List<String>>, onSuccess: () -> Unit) -> Unit,
    onCreateProduct: ((stallId: String, name: String, description: String, price: String, currency: String, tags: List<List<String>>, onSuccess: () -> Unit) -> Unit)? = null,
    onNavigateBack: () -> Unit,
    navController: NavHostController? = null
) {
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(navController) {
        val currentHandle = navController?.currentBackStackEntry?.savedStateHandle
        val previousHandle = navController?.previousBackStackEntry?.savedStateHandle
        val stallEditPayload = currentHandle?.get<String>(NAV_RESULT_EDIT_STALL_PAYLOAD)
            ?: previousHandle?.get<String>(NAV_RESULT_EDIT_STALL_PAYLOAD)
        if (!stallEditPayload.isNullOrBlank()) {
            selectedTabIndex = 1
        }
    }
    
    val tabs = listOf("Create Service", "Create Stall")

    HisaScreenScaffold(
        title = null,
        onBackClick = onNavigateBack,
        topBarContent = {
            HisaTabRow(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { selectedTabIndex = it },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTabIndex) {
                0 -> {
                    CreateServiceScreen(
                        onCreateService = onCreateService,
                        onNavigateBack = onNavigateBack,
                        navController = navController
                    )
                }
                1 -> {
                    CreateStallScreen(
                        onCreateStall = onCreateStall,
                        onCreateProduct = onCreateProduct,
                        onNavigateBack = onNavigateBack,
                        navController = navController
                    )
                }
            }
        }
    }
}
