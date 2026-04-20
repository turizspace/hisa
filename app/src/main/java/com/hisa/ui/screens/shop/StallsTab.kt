package com.hisa.ui.screens.shop

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.ui.components.StallCard
import com.hisa.ui.util.LocalProfileMetaUtil
import com.hisa.ui.navigation.Routes
import com.hisa.viewmodel.StallsViewModel
import com.hisa.viewmodel.StallsViewModelFactory

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun StallsTab(
    navController: NavController,
    userPubkey: String,
    nostrClient: NostrClient,
    subscriptionManager: SubscriptionManager,
    privateKey: ByteArray?,
    searchQuery: String = ""
) {
    val profileMetaUtil = LocalProfileMetaUtil.current
    val viewModel: StallsViewModel = viewModel(factory = StallsViewModelFactory(nostrClient, subscriptionManager, profileMetaUtil))
    val stalls by viewModel.stalls.collectAsState()

    LazyColumn {
        items(stalls) { stall ->
            StallCard(
                stall = stall,
                onClick = {
                    // Set the stall arguments in savedStateHandle before navigating
                    navController.currentBackStackEntry?.savedStateHandle?.set("channelEventId", stall.id)
                    navController.navigate(Routes.STALL_DETAIL)
                }
            )
        }
    }
}
