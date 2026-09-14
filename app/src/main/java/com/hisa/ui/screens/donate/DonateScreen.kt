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
import com.hisa.data.repository.BadgeAward
import com.hisa.data.repository.BadgeAwardPolicy
import com.hisa.data.repository.DonationTargetsRepository
import com.hisa.data.repository.DeveloperSupportProfile
import com.hisa.data.repository.DeveloperSupportRepository
import com.hisa.data.repository.DonationSourceStatus
import com.hisa.data.repository.DonationSourceState
import com.hisa.data.repository.DonationFailureCategory
import com.hisa.data.repository.PaymentTarget
import com.hisa.data.repository.ProfileRepository
import com.hisa.data.repository.ZapSponsor
import com.hisa.data.repository.ZapSponsorsRepository
import com.hisa.ui.components.SkeletonBox
import com.hisa.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.EnumMap
import java.util.Locale

@HiltViewModel
class DonateViewModel @Inject constructor(
    private val zapSponsorsRepository: ZapSponsorsRepository,
    private val donationTargetsRepository: DonationTargetsRepository,
    private val profileRepository: ProfileRepository,
    private val developerSupportRepository: DeveloperSupportRepository
) : ViewModel() {
    private val _invoice = MutableStateFlow<String?>(null)
    val invoice: StateFlow<String?> = _invoice
    private val _invoiceError = MutableStateFlow<String?>(null)
    val invoiceError: StateFlow<String?> = _invoiceError
    private val _isGeneratingInvoice = MutableStateFlow(false)
    val isGeneratingInvoice: StateFlow<Boolean> = _isGeneratingInvoice
    val uiState: StateFlow<DonateUiState> = run {
        val sources = combine(
            zapSponsorsRepository.sponsorState,
            donationTargetsRepository.paymentTargetState,
            donationTargetsRepository.badgeState,
            developerSupportRepository.sourceState,
        ) { sponsors, targets, badges, developer ->
            DonateSourceSnapshot(sponsors, targets, badges, developer)
        }
        combine(sources, _invoice, _invoiceError, _isGeneratingInvoice) {
                snapshot, invoice, invoiceError, isGenerating ->
            DonateUiState(
                sponsors = snapshot.sponsors.data,
                paymentTargets = snapshot.targets.data,
                badgeAwards = snapshot.badges.data,
                developer = snapshot.developer.data,
                isLoadingSponsors = snapshot.sponsors.isLoading,
                isLoadingDeveloper = snapshot.developer.isLoading,
                invoice = invoice,
                invoiceError = invoiceError,
                isGeneratingInvoice = isGenerating
                ,sponsorState = snapshot.sponsors
                ,paymentTargetState = snapshot.targets
                ,badgeState = snapshot.badges
                ,developerState = snapshot.developer
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DonateUiState())
    }

    init {
        zapSponsorsRepository.start()
        donationTargetsRepository.start()
        viewModelScope.launch(Dispatchers.IO) { developerSupportRepository.start() }
    }

    override fun onCleared() {
        donationTargetsRepository.stop()
        zapSponsorsRepository.stop()
        super.onCleared()
    }

    fun generateInvoice(amount: Long) {
        val support = developerSupportRepository.profile.value
        val address = support?.lightningAddress
        val lnurlPayUrl = support?.lnurlPayUrl
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
        viewModelScope.launch(Dispatchers.IO) { developerSupportRepository.refresh() }
    }

    fun refreshSponsors() = zapSponsorsRepository.refresh()
    fun refreshTargets() = donationTargetsRepository.refresh()
    fun refreshDeveloper() {
        viewModelScope.launch(Dispatchers.IO) { developerSupportRepository.refresh() }
    }

}

data class DonateUiState(
    val sponsors: List<ZapSponsor> = emptyList(),
    val paymentTargets: List<PaymentTarget> = emptyList(),
    val badgeAwards: List<BadgeAward> = emptyList(),
    val developer: DeveloperSupportProfile? = null,
    val isLoadingSponsors: Boolean = false,
    val isLoadingDeveloper: Boolean = false,
    val invoice: String? = null,
    val invoiceError: String? = null,
    val isGeneratingInvoice: Boolean = false
    ,val sponsorState: DonationSourceState<List<ZapSponsor>> = DonationSourceState(emptyList())
    ,val paymentTargetState: DonationSourceState<List<PaymentTarget>> = DonationSourceState(emptyList())
    ,val badgeState: DonationSourceState<List<BadgeAward>> = DonationSourceState(emptyList())
    ,val developerState: DonationSourceState<DeveloperSupportProfile?> = DonationSourceState(null)
)

private data class DonateSourceSnapshot(
    val sponsors: DonationSourceState<List<ZapSponsor>>,
    val targets: DonationSourceState<List<PaymentTarget>>,
    val badges: DonationSourceState<List<BadgeAward>>,
    val developer: DonationSourceState<DeveloperSupportProfile?>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateScreen(navController: NavHostController? = null) {
    val context = LocalContext.current
    val viewModel: DonateViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
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
            Text(
                "Hisa is open-source software. Your donations help keep development active and support new features.",
                style = MaterialTheme.typography.bodyLarge
            )
            HisaSponsorsTicker(
                sponsors = uiState.sponsors,
                profileRepository = com.hisa.ui.util.LocalProfileRepository.current,
                isLoading = uiState.isLoadingSponsors,
                state = uiState.sponsorState,
                onRetry = viewModel::refreshSponsors
            )
            PublishedBadges(
                awards = uiState.badgeAwards,
                profileRepository = com.hisa.ui.util.LocalProfileRepository.current,
                state = uiState.badgeState,
                onRetry = viewModel::refreshTargets,
                onProfileClick = { navController?.navigate("profile/$it") }
            )
            PublishedPaymentTargets(
                targets = uiState.paymentTargets.filter { it.type != "lightning" },
                state = uiState.paymentTargetState,
                onRetry = viewModel::refreshTargets,
                onCopy = { copyTarget(context, it) }
            )
            LightningDonationCard(
                address = uiState.developer?.lightningAddress,
                isLoadingAddress = uiState.isLoadingDeveloper,
                amount = amount,
                onAmountChange = { amount = it },
                invoice = uiState.invoice,
                error = uiState.invoiceError,
                isGenerating = uiState.isGeneratingInvoice,
                onGenerate = { viewModel.generateInvoice(amount.toLongOrNull() ?: 0L) },
                onCopy = { uiState.invoice?.let { copyText(context, "Lightning invoice", it) } },
                onPay = { uiState.invoice?.let { openLightningPayment(context, it) } },
                onRetry = viewModel::refreshDeveloper,
                sourceState = uiState.developerState
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
    onPay: () -> Unit,
    onRetry: () -> Unit,
    sourceState: DonationSourceState<DeveloperSupportProfile?>
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(
            title = "Lightning support",
            subtitle = "Generate an invoice using the team's Lightning address."
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoadingAddress && address == null) {
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
            sourceState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
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
    state: DonationSourceState<List<PaymentTarget>>,
    onRetry: () -> Unit,
    onCopy: (PaymentTarget) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(
            title = "Other ways to support Hisa",
            subtitle = "Choose another payment method published by the Hisa team."
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (targets.isEmpty() && state.status == DonationSourceStatus.LOADING) {
                    PaymentTargetSkeletons()
                } else if (targets.isEmpty() && state.status == DonationSourceStatus.FAILED) {
                    Text("Payment targets could not be loaded.")
                } else if (targets.isEmpty()) {
                    Text("No additional payment targets are currently published.")
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Retry") }
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
    profileRepository: ProfileRepository,
    state: DonationSourceState<List<BadgeAward>>,
    onRetry: () -> Unit,
    onProfileClick: (String) -> Unit
) {
    val displayableAwards = awards.filter(BadgeAwardPolicy::isDisplayable)
    if (displayableAwards.isEmpty() && state.status == DonationSourceStatus.EMPTY) return
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
        if (displayableAwards.isEmpty() && state.status == DonationSourceStatus.LOADING) {
            BadgeSkeletons()
        } else if (state.status == DonationSourceStatus.FAILED && displayableAwards.isEmpty()) {
            Text("Mission badges could not be loaded.")
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        displayableAwards.forEach { award ->
            val metadata by profileRepository
                .profileFlow(award.recipientPubkey)
                .collectAsState()
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
    profileRepository: ProfileRepository,
    isLoading: Boolean,
    state: DonationSourceState<List<ZapSponsor>> = DonationSourceState(sponsors),
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(
            title = "Supporters & Patrons",
            subtitle = "Lightning zaps sent to the developer's profile."
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (sponsors.isEmpty() && (state.status == DonationSourceStatus.LOADING || isLoading)) {
                    SponsorSkeletons()
                } else if (state.status == DonationSourceStatus.FAILED && sponsors.isEmpty()) {
                    Text("Supporters could not be loaded right now.")
                } else if (state.status == DonationSourceStatus.EMPTY) {
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
                            SponsorIdentity(sponsor = sponsor, profileRepository = profileRepository)
                        }
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun SponsorIdentity(
    sponsor: ZapSponsor,
    profileRepository: ProfileRepository
) {
    val metadata by profileRepository.profileFlow(sponsor.pubkey).collectAsState()
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
                text = "${formatSats(sponsor.totalMilliSats / 1_000L)} sats",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSats(amount: Long): String =
    String.format(Locale.US, "%,d", amount)

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
