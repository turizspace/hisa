package com.hisa.ui.screens.donate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.hisa.data.model.Metadata
import com.hisa.data.repository.BadgeAward
import com.hisa.data.repository.BadgeAwardPolicy
import com.hisa.data.repository.DonationTargetsRepository
import com.hisa.data.repository.MetadataRepository
import com.hisa.data.repository.PaymentTarget
import com.hisa.data.repository.ProfileRepository
import com.hisa.data.repository.RemoteRelayDirectory
import com.hisa.data.repository.ZapSponsor
import com.hisa.data.repository.ZapSponsorsRepository
import com.hisa.ui.components.SkeletonBox
import com.hisa.data.nostr.NostrClient
import com.hisa.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Bech32
import org.json.JSONObject
import java.util.EnumMap

@HiltViewModel
class DonateViewModel @Inject constructor(
    private val zapSponsorsRepository: ZapSponsorsRepository,
    private val donationTargetsRepository: DonationTargetsRepository,
    private val metadataRepository: MetadataRepository,
    private val nostrClient: NostrClient,
    private val profileRepository: ProfileRepository,
    private val remoteRelayDirectory: RemoteRelayDirectory
) : ViewModel() {
    private val _invoice = MutableStateFlow<String?>(null)
    val invoice: StateFlow<String?> = _invoice
    private val _invoiceError = MutableStateFlow<String?>(null)
    val invoiceError: StateFlow<String?> = _invoiceError
    private val _isGeneratingInvoice = MutableStateFlow(false)
    val isGeneratingInvoice: StateFlow<Boolean> = _isGeneratingInvoice
    private val _lightningAddress = MutableStateFlow<String?>(null)
    val lightningAddress: StateFlow<String?> = _lightningAddress
    @Volatile
    private var lightningLnurlPayUrl: String? = null
    private val _isLoadingLightningAddress = MutableStateFlow(true)
    val isLoadingLightningAddress: StateFlow<Boolean> = _isLoadingLightningAddress

    val sponsors: StateFlow<List<ZapSponsor>> = zapSponsorsRepository.sponsors
    val isLoadingSponsors: StateFlow<Boolean> = zapSponsorsRepository.isLoading
    val paymentTargets: StateFlow<List<PaymentTarget>> = donationTargetsRepository.paymentTargets
    val badgeAwards: StateFlow<List<BadgeAward>> = donationTargetsRepository.badgeAwards
    val isLoadingTargets: StateFlow<Boolean> = donationTargetsRepository.isLoading

    init {
        zapSponsorsRepository.start()
        donationTargetsRepository.start()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                nostrClient.refreshStoredRelays()
                nostrClient.connect()
                remoteRelayDirectory.ensureRelayCoverage(Constants.HISA_DEV_PUBKEY)
                // A cached profile lets the donation form become usable immediately.
                // The relay fetch below remains authoritative and refreshes it.
                profileRepository.getCachedProfile(Constants.HISA_DEV_PUBKEY)
                    ?.lightningDonationTarget()
                    ?.let(::applyLightningTarget)

                profileRepository.ensureProfiles(setOf(Constants.HISA_DEV_PUBKEY))
                val fetchedMetadata = metadataRepository
                    .getMetadataForPubkey(Constants.HISA_DEV_PUBKEY)
                sequenceOf(
                    fetchedMetadata,
                    profileRepository.getCachedProfile(Constants.HISA_DEV_PUBKEY)
                ).mapNotNull { it?.lightningDonationTarget() }
                    .firstOrNull()
                    ?.let(::applyLightningTarget)
            } finally {
                _isLoadingLightningAddress.value = false
            }
        }
    }

    override fun onCleared() {
        donationTargetsRepository.stop()
        zapSponsorsRepository.stop()
        super.onCleared()
    }

    fun generateInvoice(amount: Long) {
        val address = _lightningAddress.value
        val lnurlPayUrl = lightningLnurlPayUrl
        if (amount <= 0L) {
            _invoiceError.value = "Enter an amount greater than zero."
            return
        }
        if (address.isNullOrBlank() || lnurlPayUrl.isNullOrBlank()) {
            _invoiceError.value = "The developer has not published a usable Lightning address in Nostr yet."
            return
        }

        viewModelScope.launch {
            _isGeneratingInvoice.value = true
            _invoice.value = null
            _invoiceError.value = null
            try {
                _invoice.value = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient()
                    val metadataResponse = client.newCall(okhttp3.Request.Builder().url(lnurlPayUrl).build()).execute()
                    metadataResponse.use {
                        check(it.isSuccessful) { "Could not reach the Lightning service" }
                        val response = JSONObject(it.body?.string().orEmpty())
                        val callback = response.getString("callback")
                        val invoiceUrl = Uri.parse(callback).buildUpon()
                            .appendQueryParameter("amount", (amount * 1_000L).toString())
                            .build()
                        val invoiceResponse = client.newCall(okhttp3.Request.Builder().url(invoiceUrl.toString()).build()).execute()
                        invoiceResponse.use { invoiceResult ->
                            check(invoiceResult.isSuccessful) { "Could not create the Lightning invoice" }
                            JSONObject(invoiceResult.body?.string().orEmpty()).getString("pr")
                        }
                    }
                }
            } catch (error: Exception) {
                _invoiceError.value = error.message ?: "Could not create an invoice."
            } finally {
                _isGeneratingInvoice.value = false
            }
        }
    }

    fun refresh() {
        zapSponsorsRepository.refresh()
        donationTargetsRepository.refresh()
    }

    private fun applyLightningTarget(target: LightningDonationTarget) {
        _lightningAddress.value = target.displayValue
        lightningLnurlPayUrl = target.lnurlPayUrl
    }
}

private data class LightningDonationTarget(
    val displayValue: String,
    val lnurlPayUrl: String
)

private fun Metadata.lightningDonationTarget(): LightningDonationTarget? {
    lud16?.usableLightningAddress()?.let { address ->
        val (localPart, domain) = address.split("@", limit = 2)
        return LightningDonationTarget(
            displayValue = address,
            lnurlPayUrl = "https://$domain/.well-known/lnurlp/$localPart"
        )
    }
    lud06?.decodeLnurlPayUrl()?.let { url ->
        return LightningDonationTarget(displayValue = "LNURL Pay", lnurlPayUrl = url)
    }
    return null
}

private fun String.usableLightningAddress(): String? {
    val candidate = trim()
    val parts = candidate.split("@", limit = 2)
    return candidate.takeIf {
        parts.size == 2 &&
            parts[0].isNotBlank() &&
            parts[1].isNotBlank() &&
            !parts[0].contains('/') &&
            !parts[1].contains('/') &&
            !parts[1].contains(' ')
    }
}

private fun String.decodeLnurlPayUrl(): String? = runCatching {
    val decoded = Bech32.decode(trim().lowercase())
    if (decoded.hrp != "lnurl") return null

    var accumulator = 0
    var bitCount = 0
    val bytes = ArrayList<Byte>()
    decoded.data.forEach { word ->
        accumulator = (accumulator shl 5) or (word.toInt() and 0x1f)
        bitCount += 5
        while (bitCount >= 8) {
            bitCount -= 8
            bytes += ((accumulator shr bitCount) and 0xff).toByte()
        }
    }
    if (bitCount >= 5 || ((accumulator shl (8 - bitCount)) and 0xff) != 0) return null
    bytes.toByteArray().decodeToString().trim().takeIf {
        it.startsWith("https://", ignoreCase = true)
    }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateScreen(navController: NavHostController? = null) {
    val context = LocalContext.current
    val viewModel: DonateViewModel = hiltViewModel()
    val invoice by viewModel.invoice.collectAsState()
    val invoiceError by viewModel.invoiceError.collectAsState()
    val isGeneratingInvoice by viewModel.isGeneratingInvoice.collectAsState()
    val lightningAddress by viewModel.lightningAddress.collectAsState()
    val isLoadingLightningAddress by viewModel.isLoadingLightningAddress.collectAsState()
    val paymentTargets by viewModel.paymentTargets.collectAsState()
    val badgeAwards by viewModel.badgeAwards.collectAsState()
    val sponsors by viewModel.sponsors.collectAsState()
    val isLoadingSponsors by viewModel.isLoadingSponsors.collectAsState()
    val isLoadingTargets by viewModel.isLoadingTargets.collectAsState()
    val profileRepository = com.hisa.ui.util.LocalProfileRepository.current
    val profiles by profileRepository.profiles.collectAsState()
    var amount by remember { mutableStateOf(Constants.DEFAULT_DONATION_AMOUNT_SATS.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Support Hisa") },
                navigationIcon = {
                    IconButton(onClick = { navController?.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isLoadingSponsors || isLoadingTargets || isLoadingLightningAddress) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Loading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "Hisa is open-source software. Your donations help keep development active and support new features.",
                style = MaterialTheme.typography.bodyLarge
            )
            HisaSponsorsTicker(sponsors, profiles, isLoadingSponsors)
            PublishedBadges(
                awards = badgeAwards,
                profiles = profiles,
                isLoading = isLoadingTargets,
                onProfileClick = { navController?.navigate("profile/$it") }
            )
            PublishedPaymentTargets(
                targets = paymentTargets.filter { it.type != "lightning" },
                isLoading = isLoadingTargets,
                onCopy = { copyTarget(context, it) }
            )
            LightningDonationCard(
                address = lightningAddress,
                isLoadingAddress = isLoadingLightningAddress,
                amount = amount,
                onAmountChange = { amount = it },
                invoice = invoice,
                error = invoiceError,
                isGenerating = isGeneratingInvoice,
                onGenerate = { viewModel.generateInvoice(amount.toLongOrNull() ?: 0L) },
                onCopy = { invoice?.let { copyText(context, "Lightning invoice", it) } },
                onPay = { invoice?.let { openLightningPayment(context, it) } }
            )
        }
    }
}

@Composable
private fun LightningDonationCard(
    address: String?,
    isLoadingAddress: Boolean,
    amount: String,
    onAmountChange: (String) -> Unit,
    invoice: String?,
    error: String?,
    isGenerating: Boolean,
    onGenerate: () -> Unit,
    onCopy: () -> Unit,
    onPay: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(
            title = "Lightning support",
            subtitle = "Generate an invoice using the team's Lightning address."
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoadingAddress) {
                LightningCardSkeleton()
            } else if (address != null) {
                Text(
                    text = if (address == "LNURL Pay") "Sending sats using the developer's LNURL Pay endpoint"
                    else "Sending sats to $address",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = onAmountChange,
                    label = { Text("Amount (sats)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = onGenerate, enabled = !isGenerating, modifier = Modifier.fillMaxWidth()) {
                    if (isGenerating) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Generate invoice")
                }
            } else {
                Text(
                    "The developer has not published a usable Lightning address in Nostr yet.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            invoice?.let {
                val bitmap = rememberQrBitmap(it, MaterialTheme.colorScheme.onSurface.toArgb(), MaterialTheme.colorScheme.surface.toArgb())
                if (bitmap != null) {
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Lightning invoice QR code", modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy invoice")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy")
                    }
                    Button(onClick = onPay, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Outlined.CurrencyBitcoin, contentDescription = "Pay invoice")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pay")
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun PublishedPaymentTargets(
    targets: List<PaymentTarget>,
    isLoading: Boolean,
    onCopy: (PaymentTarget) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(
            title = "Other ways to support Hisa",
            subtitle = "Choose another payment method published by the Hisa team."
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (targets.isEmpty() && isLoading) {
                    PaymentTargetSkeletons()
                } else if (targets.isEmpty()) {
                    Text("No additional payment targets are currently published.")
                }
                targets.forEach { target ->
                    val visual = paymentTargetVisual(target.type)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(visual.tint.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (visual.logoRes != null) {
                                Image(
                                    painter = painterResource(visual.logoRes),
                                    contentDescription = "${visual.label} logo",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(30.dp).clip(CircleShape)
                                )
                            } else {
                                Text(visual.symbol, color = visual.tint, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(visual.label, style = MaterialTheme.typography.labelLarge)
                            Text(target.address, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onCopy(target) }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy ${target.type} target")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class PaymentTargetVisual(
    val label: String,
    val symbol: String,
    val tint: Color,
    val logoRes: Int? = null
)

private fun paymentTargetVisual(type: String): PaymentTargetVisual {
    return when (type.trim().lowercase()) {
        "bitcoin", "btc" -> PaymentTargetVisual("Bitcoin", "₿", Color(0xFFF7931A), com.hisa.R.drawable.btc_logo)
        "lightning" -> PaymentTargetVisual("Lightning", "ϟ", Color(0xFFF6B800))
        "nano" -> PaymentTargetVisual("Nano", "Ӿ", Color(0xFF4A90E2))
        "monero", "xmr" -> PaymentTargetVisual("Monero", "ɱ", Color(0xFFFF6600), com.hisa.R.drawable.xmr_logo)
        "ethereum", "eth" -> PaymentTargetVisual("Ethereum", "Ξ", Color(0xFF627EEA), com.hisa.R.drawable.eth_logo)
        "solana" -> PaymentTargetVisual("Solana", "◎", Color(0xFF14F195), com.hisa.R.drawable.sol_logo)
        "zcash" -> PaymentTargetVisual("Zcash", "ⓩ", Color(0xFFF4B728), com.hisa.R.drawable.zec_logo)
        "litecoin" -> PaymentTargetVisual("Litecoin", "Ł", Color(0xFF345D9D), com.hisa.R.drawable.ltc_logo)
        "cashme" -> PaymentTargetVisual("Cash App", "$", Color(0xFF00D632))
        "venmo" -> PaymentTargetVisual("Venmo", "$", Color(0xFF3D95CE))
        "revolut" -> PaymentTargetVisual("Revolut", "R", Color(0xFF111111))
        else -> {
            val fallback = type.trim().take(1).uppercase().ifBlank { "?" }
            PaymentTargetVisual(type.replaceFirstChar { it.uppercase() }, fallback, MaterialThemeFallbackTint)
        }
    }
}

private val MaterialThemeFallbackTint = Color(0xFF68707A)

@Composable
private fun PublishedBadges(
    awards: List<BadgeAward>,
    profiles: Map<String, Metadata>,
    isLoading: Boolean,
    onProfileClick: (String) -> Unit
) {
    val displayableAwards = awards.filter(BadgeAwardPolicy::isDisplayable)
    if (displayableAwards.isEmpty() && !isLoading) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("The Mission Badges", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Different levels. Same mission. Recognition for people helping shape Hisa’s early journey.",
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (displayableAwards.isEmpty() && isLoading) {
            BadgeSkeletons()
        }
        displayableAwards.forEach { award ->
            val metadata = profiles[award.recipientPubkey]
            val displayName = metadata?.displayName?.takeIf(String::isNotBlank)
                ?: metadata?.name?.takeIf(String::isNotBlank)
                ?: award.recipientPubkey.take(12) + "..."
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onProfileClick(award.recipientPubkey) }) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!metadata?.picture.isNullOrBlank()) {
                        AsyncImage(model = metadata?.picture, contentDescription = "$displayName profile picture", contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(CircleShape))
                    } else {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(44.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(displayName, style = MaterialTheme.typography.titleMedium)
                        Text("${award.name}", style = MaterialTheme.typography.bodySmall)
                        if (award.description.isNotBlank()) {
                            Text(
                                text = award.description,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (!award.imageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = award.imageUrl,
                            contentDescription = "${award.name} badge",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HisaSponsorsTicker(
    sponsors: List<ZapSponsor>,
    profiles: Map<String, Metadata>,
    isLoading: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(
            title = "Supporters & Patrons",
            subtitle = "Lightning zaps sent to the developer's Nostr profile."
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (sponsors.isEmpty() && isLoading) {
                    SponsorSkeletons()
                } else if (sponsors.isEmpty()) {
                    Text("No validated sponsors have reached the display threshold yet.")
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                animationMode = MarqueeAnimationMode.Immediately
                            ),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        sponsors.forEach { sponsor ->
                            SponsorIdentity(sponsor = sponsor, metadata = profiles[sponsor.pubkey])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SponsorIdentity(
    sponsor: ZapSponsor,
    metadata: Metadata?
) {
    val displayName = metadata?.displayName?.takeIf(String::isNotBlank)
        ?: metadata?.name?.takeIf(String::isNotBlank)
        ?: sponsor.pubkey.take(12) + "..."
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .widthIn(max = 160.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!metadata?.picture.isNullOrBlank()) {
                AsyncImage(
                    model = metadata?.picture,
                    contentDescription = "$displayName profile picture",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    painter = rememberVectorPainter(Icons.Default.AccountCircle),
                    contentDescription = "$displayName profile picture",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = 112.dp)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${sponsor.totalMilliSats / 1_000L} sats",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SponsorSkeletons() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBox(modifier = Modifier.size(36.dp), shape = RoundedCornerShape(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                SkeletonBox(modifier = Modifier.width(120.dp).height(14.dp))
            }
        }
    }
}

@Composable
private fun BadgeSkeletons() {
    repeat(2) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonBox(modifier = Modifier.size(44.dp), shape = RoundedCornerShape(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonBox(modifier = Modifier.width(132.dp).height(14.dp))
                SkeletonBox(modifier = Modifier.width(92.dp).height(11.dp))
            }
        }
    }
}

@Composable
private fun PaymentTargetSkeletons() {
    repeat(2) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonBox(modifier = Modifier.size(44.dp), shape = RoundedCornerShape(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonBox(modifier = Modifier.width(110.dp).height(14.dp))
                SkeletonBox(modifier = Modifier.fillMaxWidth().height(11.dp))
            }
        }
    }
}

@Composable
private fun LightningCardSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SkeletonBox(modifier = Modifier.fillMaxWidth(0.72f).height(13.dp))
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(54.dp))
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(42.dp))
    }
}

private fun copyTarget(context: Context, target: PaymentTarget) =
    copyText(context, "${target.type} payment target", target.address)

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun openLightningPayment(context: Context, invoice: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$invoice")))
    }.onFailure {
        copyText(context, "Lightning invoice", invoice)
    }
}

private fun rememberQrBitmap(invoice: String, foreground: Int, background: Int): android.graphics.Bitmap? =
    runCatching {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            this[EncodeHintType.MARGIN] = 1
        }
        val matrix = QRCodeWriter().encode(invoice, BarcodeFormat.QR_CODE, 512, 512, hints)
        android.graphics.Bitmap.createBitmap(matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) foreground else background)
                }
            }
        }
    }.getOrNull()
