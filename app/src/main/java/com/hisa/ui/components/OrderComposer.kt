package com.hisa.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun OrderComposerDialog(
    open: Boolean,
    sellerDisplayName: String,
    sellerPubkey: String,
    itemReference: String,
    itemName: String,
    unitPriceLabel: String,
    unitPriceSats: Long?,
    initialQuantity: Int = 1,
    isSending: Boolean = false,
    errorMessage: String? = null,
    onCancel: () -> Unit,
    onSubmit: (
        quantity: Int,
        notes: String,
        shippingOption: String?,
        shippingAddress: String?,
        buyerEmail: String?,
        buyerPhone: String?
    ) -> Unit
) {
    if (!open) return

    var quantityText by remember { mutableStateOf(initialQuantity.toString()) }
    var notes by remember { mutableStateOf("") }
    var shippingOption by remember { mutableStateOf("") }
    var shippingAddress by remember { mutableStateOf("") }
    var buyerEmail by remember { mutableStateOf("") }
    var buyerPhone by remember { mutableStateOf("") }

    val quantity = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val totalText = if (unitPriceSats != null) {
        val total = unitPriceSats * quantity
        "$total sats"
    } else {
        "N/A"
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Column {
                Text(
                    text = "Order $itemName",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Seller: $sellerDisplayName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Unit price", style = MaterialTheme.typography.labelLarge)
                        Text(unitPriceLabel, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Total", style = MaterialTheme.typography.labelLarge)
                        Text(totalText, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { input ->
                        quantityText = input.filter { it.isDigit() }.takeIf { it.isNotBlank() } ?: "1"
                    },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Order notes") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    maxLines = 6
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = shippingOption,
                    onValueChange = { shippingOption = it },
                    label = { Text("Shipping option (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = shippingAddress,
                    onValueChange = { shippingAddress = it },
                    label = { Text("Shipping address (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = buyerEmail,
                    onValueChange = { buyerEmail = it },
                    label = { Text("Email (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = buyerPhone,
                    onValueChange = { buyerPhone = it },
                    label = { Text("Phone (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )

                if (!errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        quantity,
                        notes,
                        shippingOption.takeIf { it.isNotBlank() },
                        shippingAddress.takeIf { it.isNotBlank() },
                        buyerEmail.takeIf { it.isNotBlank() },
                        buyerPhone.takeIf { it.isNotBlank() }
                    )
                },
                enabled = !isSending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isSending) "Sending order..." else "Submit order")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

fun parseSatsAmount(priceValue: String?, currency: String? = "SATS"): Long? {
    val normalized = (priceValue ?: "").trim().lowercase().replace("sats", "").replace("sat", "").replace("\$", "").replace(",", "").trim()
    if (normalized.isBlank()) return null
    val digits = normalized.filter { it.isDigit() }
    return digits.toLongOrNull()
}

fun formatOrderPrice(priceValue: String, currency: String?): String {
    if (priceValue.isBlank()) return "—"
    return if (!currency.isNullOrBlank() && currency.uppercase() != "SATS") {
        "$priceValue ${currency.uppercase()}"
    } else {
        if (priceValue.any { it.isLetter() }) priceValue else "$priceValue sats"
    }
}
