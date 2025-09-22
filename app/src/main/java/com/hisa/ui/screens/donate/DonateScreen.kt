package com.hisa.ui.screens.donate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hisa.util.Constants
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import java.net.URL
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@HiltViewModel
class DonateViewModel @Inject constructor() : ViewModel() {
    private val _invoice = MutableStateFlow<String?>(null)
    val invoice: StateFlow<String?> = _invoice

    fun generateInvoice(amount: Long, lightningAddress: String) {
        viewModelScope.launch {
            try {
                val invoice = withContext(Dispatchers.IO) {
                    val domain = lightningAddress.split("@")[1]
                    val username = lightningAddress.split("@")[0]
                    val url = "https://$domain/.well-known/lnurlp/$username"

                    val client = OkHttpClient()
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) throw Exception("Failed to fetch invoice")

                    val jsonResponse = JSONObject(response.body?.string() ?: "")
                    val callback = jsonResponse.getString("callback")
                    val invoiceUrl = "$callback?amount=${amount * 1000}"

                    val invoiceRequest = Request.Builder().url(invoiceUrl).build()
                    val invoiceResponse = client.newCall(invoiceRequest).execute()

                    if (!invoiceResponse.isSuccessful) throw Exception("Failed to fetch invoice")

                    val invoiceJson = JSONObject(invoiceResponse.body?.string() ?: "")
                    invoiceJson.getString("pr")
                }
                _invoice.value = invoice
            } catch (e: Exception) {
                _invoice.value = null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateScreen() {
    val context = LocalContext.current
    var amount by remember { mutableStateOf(Constants.DEFAULT_DONATION_AMOUNT_SATS.toString()) }
    val viewModel: DonateViewModel = viewModel()
    val invoice by viewModel.invoice.collectAsState()
    var showQr by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Support Hisa Development",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            "Hisa is open-source software. Your donations help keep development active and support new features.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Lightning donation section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    "Lightning Network",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (sats)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val amountLong = amount.toLongOrNull() ?: return@Button
                        viewModel.generateInvoice(amountLong, Constants.LIGHTNING_ADDRESS)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Generate Invoice")
                }

                if (invoice != null) {
                    // Capture theme colors in composition (non-callback) so they can be used inside remember/onClick
                    val qrForeground = MaterialTheme.colorScheme.onSurface
                    val qrBackground = MaterialTheme.colorScheme.surface
                    val fgArgb = qrForeground.toArgb()
                    val bgArgb = qrBackground.toArgb()

                    // Generate QR code using remember; uses pre-captured ARGB ints (no composable calls inside)
                    val generatedQrBitmap = remember(invoice, fgArgb, bgArgb) {
                        try {
                            val writer = QRCodeWriter()
                            val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java)
                            hints[com.google.zxing.EncodeHintType.MARGIN] = 1
                            hints[com.google.zxing.EncodeHintType.ERROR_CORRECTION] = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M

                            val bitMatrix = writer.encode(
                                invoice,
                                BarcodeFormat.QR_CODE,
                                512,
                                512,
                                hints
                            )
                            val width = bitMatrix.width
                            val height = bitMatrix.height
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            for (x in 0 until width) {
                                for (y in 0 until height) {
                                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) fgArgb else bgArgb)
                                }
                            }
                            bitmap
                        } catch (e: Exception) {
                            null
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (generatedQrBitmap != null) {
                            Image(
                                bitmap = generatedQrBitmap.asImageBitmap(),
                                contentDescription = "Invoice QR Code",
                                modifier = Modifier
                                    .size(200.dp)
                                    .padding(vertical = 16.dp)
                                    .border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        shape = MaterialTheme.shapes.medium
                                    )
                            )
                        } else {
                            Text(
                                "Failed to generate QR code",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Lightning Invoice", invoice)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Invoice copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy")
                        }

                        Button(
                            onClick = {
                                try {
                                    val lightningUri = "lightning:$invoice"
                                    val lightningIntent = Intent(Intent.ACTION_VIEW, Uri.parse(lightningUri))

                                    val plainTextIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, invoice)
                                    }

                                    // Create chooser with lightning intent as primary, plain text as alternative
                                    val chooser = Intent.createChooser(lightningIntent, "Pay with Lightning").apply {
                                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(plainTextIntent))
                                    }
                                    context.startActivity(chooser)

                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(
                                        context,
                                        "Error opening wallet: ${e.localizedMessage}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            // Use theme color instead of hard-coded orange so the button matches current theme
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)

                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CurrencyBitcoin,
                                contentDescription = "Pay with wallet"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pay")
                        }
                    }

                    var showQRDialog by remember { mutableStateOf(false) }

                    // QR code is now shown directly above the buttons
                }
            }
        }

        // Bitcoin onchain donation section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    "Bitcoin On-chain",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                var showOnchainQRDialog by remember { mutableStateOf(false) }
                var onchainQrBitmap by remember { mutableStateOf<Bitmap?>(null) }

                OutlinedTextField(
                    value = Constants.BITCOIN_ADDRESS,
                    onValueChange = { },
                    label = { Text("Bitcoin Address") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Bitcoin Address", Constants.BITCOIN_ADDRESS)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Address")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                        val onchainQrFg = MaterialTheme.colorScheme.onSurface
                        val onchainQrBg = MaterialTheme.colorScheme.surface
                        val onchainFg = onchainQrFg.toArgb()
                        val onchainBg = onchainQrBg.toArgb()

                        Button(
                    onClick = {
                                try {
                                    val writer = QRCodeWriter()
                                    val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java)
                                    hints[com.google.zxing.EncodeHintType.MARGIN] = 1
                                    hints[com.google.zxing.EncodeHintType.ERROR_CORRECTION] = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M

                                    val bitMatrix = writer.encode(
                                        "bitcoin:${Constants.BITCOIN_ADDRESS}",
                                        BarcodeFormat.QR_CODE,
                                        512,
                                        512,
                                        hints
                                    )
                                    val width = bitMatrix.width
                                    val height = bitMatrix.height
                                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    for (x in 0 until width) {
                                        for (y in 0 until height) {
                                            bitmap.setPixel(x, y, if (bitMatrix[x, y]) onchainFg else onchainBg)
                                        }
                                    }
                                    onchainQrBitmap = bitmap
                                    showOnchainQRDialog = true
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(context, "Failed to generate QR code: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Icon(Icons.Outlined.QrCode, contentDescription = "Show QR")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Show QR")
                }

                if (showOnchainQRDialog && onchainQrBitmap != null) {
                    AlertDialog(
                        onDismissRequest = { showOnchainQRDialog = false },
                        title = { Text("Bitcoin Address QR Code") },
                        text = {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Image(
                                        bitmap = onchainQrBitmap!!.asImageBitmap(),
                                        contentDescription = "QR Code",
                                        modifier = Modifier
                                            .size(280.dp)
                                            .padding(16.dp)
                                            .border(
                                                2.dp,
                                                MaterialTheme.colorScheme.primary,
                                                shape = MaterialTheme.shapes.medium
                                            )
                                    )
                                    Text(
                                        text = Constants.BITCOIN_ADDRESS,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Row {
                                TextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Bitcoin Address", Constants.BITCOIN_ADDRESS)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy")
                                }
                                TextButton(onClick = { showOnchainQRDialog = false }) {
                                    Text("Close")
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
