package com.hisa.ui.screens.shop

import androidx.annotation.RequiresApi
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import com.hisa.viewmodel.ShopViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hisa.data.repository.ServiceRepository
import com.hisa.ui.components.ServiceCard
import com.hisa.util.cleanPubkeyFormat
import org.bitcoinj.core.Bech32

/**
 * Simple placeholder screen for the user's Shop. Add UI and actions as needed.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ShopScreen(
    navController: NavController,
    userPubkey: String
) {
    // Helper: convert bech32 npub -> hex (32 bytes hex)
    fun npubToHex(npub: String): String? {
        return try {
            val bech = Bech32.decode(npub)
            if (bech.hrp != "npub") return null
            val data = bech.data
            var acc = 0
            var bits = 0
            val out = mutableListOf<Byte>()
            for (v in data) {
                val value = v.toInt() and 0xff
                acc = (acc shl 5) or value
                bits += 5
                while (bits >= 8) {
                    bits -= 8
                    out.add(((acc shr bits) and 0xff).toByte())
                }
            }
            if (bits >= 5 || ((acc shl (8 - bits)) and 0xff) != 0) return null
            out.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    // Normalize a pubkey: accept hex, compressed/uncompressed, or npub bech32
    fun normalizePubkey(p: String): String {
        val trimmed = p.trim()
        return when {
            trimmed.startsWith("npub", true) -> npubToHex(trimmed) ?: cleanPubkeyFormat(trimmed)
            else -> cleanPubkeyFormat(trimmed)
        }
    }

    val ownerHex = remember(userPubkey) { normalizePubkey(userPubkey) }

    val shopViewModel: ShopViewModel = hiltViewModel()
    androidx.compose.runtime.LaunchedEffect(ownerHex) {
        shopViewModel.subscribeToOwner(ownerHex)
    }

    val myListings by shopViewModel.services.collectAsState(initial = emptyList())

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(8.dp)) {           
            if (myListings.isEmpty()) {
                Text(text = "Loading listings.", modifier = Modifier.padding(12.dp))
            } else {
                LazyColumn(modifier = Modifier.padding(horizontal = 4.dp)) {
                    items(myListings.sortedByDescending { it.createdAt }, key = { it.eventId }) { service ->
                        ServiceCard(service = service, showTags = true, onClick = { eventId ->
                            // navigate to service detail
                            val route = com.hisa.ui.navigation.Routes.SERVICE_DETAIL
                                .replace("{eventId}", eventId)
                                .replace("{pubkey}", service.pubkey)
                            navController.navigate(route)
                        }, onMessageClick = { pubkey, _ ->
                            // open DM with the service author
                            val target = cleanPubkeyFormat(pubkey ?: service.pubkey)
                            navController.navigate("dm/$target")
                        })
                    }
                }
            }
        }
    }
}
