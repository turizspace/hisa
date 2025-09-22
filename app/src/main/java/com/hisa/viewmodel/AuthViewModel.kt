package com.hisa.viewmodel

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.MutableState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.util.KeyGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.bitcoinj.core.ECKey
import com.hisa.util.Constants
import java.util.*

@HiltViewModel
class AuthViewModel @Inject constructor(
    application: Application,
    val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager
) : AndroidViewModel(application) {
    
    // Relay management
    private val RELAYS_KEY = "relays"
    private val _relays = MutableStateFlow<List<String>>(emptyList())
    val relays: StateFlow<List<String>> = _relays
    init {
        viewModelScope.launch {
            _relays.value = getStoredRelays()
        }
    }

    private fun getStoredRelays(): List<String> {
        val prefs = sharedPrefs
        if (prefs == null) {
            // If sharedPrefs isn't available, try the fallback file before returning default
            return try {
                val fallback = getApplication<Application>().applicationContext.getSharedPreferences("secure_prefs_fallback", android.content.Context.MODE_PRIVATE)
                val fallbackString = fallback.getString(RELAYS_KEY, null)
                fallbackString?.split("\n")?.filter { it.isNotBlank() } ?: Constants.ONBOARDING_RELAYS
            } catch (e: Exception) {
                Constants.ONBOARDING_RELAYS
            }
        }
        val relaysString = try {
            prefs.getString(RELAYS_KEY, null)
        } catch (e: Exception) {
            null
        }
        if (!relaysString.isNullOrBlank()) {
            return relaysString.split("\n").filter { it.isNotBlank() }
        }
        // If primary prefs didn't have relays (or read failed), try fallback
        return try {
            val fallback = getApplication<Application>().applicationContext.getSharedPreferences("secure_prefs_fallback", android.content.Context.MODE_PRIVATE)
            val fallbackString = fallback.getString(RELAYS_KEY, null)
            fallbackString?.split("\n")?.filter { it.isNotBlank() } ?: Constants.ONBOARDING_RELAYS
        } catch (e: Exception) {
            Constants.ONBOARDING_RELAYS
        }
    }

    fun addRelay(relay: String) {
        val current = getStoredRelays().toMutableList()
        if (!current.contains(relay)) {
            current.add(relay)
            // Persist and update in-memory state immediately so UI reflects
            // the change without waiting for SharedPreferences apply() to be visible.
            // Write to the primary (possibly encrypted) prefs
            try {
                sharedPrefs.edit().putString(RELAYS_KEY, current.joinToString("\n")).apply()
            } catch (e: Exception) {
                android.util.Log.w("AuthViewModel", "Failed to write relays to primary prefs: ${e.localizedMessage}")
            }
            // Also write to a consistent fallback prefs file to survive cases where
            // EncryptedSharedPreferences can't be created on subsequent launches.
            try {
                val fallback = getApplication<Application>().applicationContext.getSharedPreferences("secure_prefs_fallback", android.content.Context.MODE_PRIVATE)
                fallback.edit().putString(RELAYS_KEY, current.joinToString("\n")).apply()
            } catch (e: Exception) {
                android.util.Log.w("AuthViewModel", "Failed to write relays to fallback prefs: ${e.localizedMessage}")
            }
            _relays.value = current.toList()
            // Update NostrClient with new relay list
            nostrClient.updateRelays(_relays.value)
        }
    }

    fun removeRelay(relay: String) {
        val current = getStoredRelays().toMutableList()
        if (current.remove(relay)) {
            try {
                sharedPrefs.edit().putString(RELAYS_KEY, current.joinToString("\n")).apply()
            } catch (e: Exception) {
                android.util.Log.w("AuthViewModel", "Failed to write relays to primary prefs: ${e.localizedMessage}")
            }
            try {
                val fallback = getApplication<Application>().applicationContext.getSharedPreferences("secure_prefs_fallback", android.content.Context.MODE_PRIVATE)
                fallback.edit().putString(RELAYS_KEY, current.joinToString("\n")).apply()
            } catch (e: Exception) {
                android.util.Log.w("AuthViewModel", "Failed to write relays to fallback prefs: ${e.localizedMessage}")
            }
            _relays.value = current.toList()
            // Update NostrClient with new relay list
            nostrClient.updateRelays(_relays.value)
        }
    }

    private val _signupSuccess = MutableStateFlow(false)
    val signupSuccess: StateFlow<Boolean> = _signupSuccess
    // Guard to ensure signupSuccess is only set once per signup flow
    private val signupAtom = java.util.concurrent.atomic.AtomicBoolean(false)

    fun generateNewKeyPair(): Pair<String, String> {
        return try {
            val (privateKey, publicKey) = KeyGenerator.generateKeyPair()
            val nsec = KeyGenerator.privateKeyToNsec(privateKey)
            val npub = KeyGenerator.publicKeyToNpub(publicKey)
            // KeyGenerator returns hex strings; there is no mutable byte[] here to zero directly.
            // If the underlying generator exposed raw bytes, we would zero them there. Return values are safe strings.
            Pair(nsec, npub)
        } catch (e: Exception) {
            android.util.Log.e("AuthViewModel", "Error generating key pair", e)
            throw e
        }
    }

    fun completeSignup(nsec: String, metadata: com.hisa.data.model.Metadata) {
        viewModelScope.launch {
            try {
                // Do not reset _signupSuccess here; only set to true on confirmed success
                // Avoid flipping global login success immediately; perform a silent login
                // so navigation driven by loginSuccess does not dismiss the signup dialog.
                loginWithNsecSilently(nsec)
                if (_pubKey.value != null && _privateKey.value != null) {
                    sharedPrefs.edit().putString("nsec", nsec).apply()
                        // Ensure the shared/injected NostrClient is configured with onboarding relays
                        // if the current configured relays are empty. This seeds a usable set of
                        // relays for new users while allowing advanced users to change relays later.
                        val currentRelays = try { nostrClient.configuredRelays() } catch (e: Exception) { emptyList<String>() }
                        if (currentRelays.isEmpty()) {
                            try {
                                nostrClient.updateRelays(Constants.ONBOARDING_RELAYS)
                                // Persist the seeded relays so they become the configured relays
                                try {
                                    sharedPrefs?.edit()?.putString("relays", Constants.ONBOARDING_RELAYS.joinToString("\n"))?.apply()
                                } catch (e: Exception) {
                                    android.util.Log.w("AuthViewModel", "Failed to persist seeded relays: ${e.localizedMessage}")
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("AuthViewModel", "Failed to seed onboarding relays: ${e.localizedMessage}")
                            }
                        }
                        // Connect the shared client if not already connected
                        if (nostrClient.connectionState.value != com.hisa.data.nostr.NostrClient.ConnectionState.CONNECTED) {
                            nostrClient.connect()
                        }
                    val metadataJson = kotlinx.serialization.json.Json.encodeToString(
                        com.hisa.data.model.Metadata.serializer(),
                        metadata
                    )
                    val privateKeyHex = _privateKey.value
                    val pubkeyHex = _pubKey.value
                    if (privateKeyHex.isNullOrBlank() || pubkeyHex.isNullOrBlank()) {
                        throw Exception("Missing privateKey or pubkey")
                    }
                    val privateKeyBytes = privateKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val event = com.hisa.data.nostr.NostrEventSigner.signEvent(
                        kind = 0,
                        content = metadataJson,
                        tags = emptyList(),
                        pubkey = pubkeyHex,
                        privKey = privateKeyBytes
                    )
                    val nostrEvent = com.hisa.data.nostr.NostrEvent(
                        id = event.getString("id"),
                        pubkey = event.getString("pubkey"),
                        createdAt = event.getLong("created_at"),
                        kind = event.getInt("kind"),
                        tags = (0 until event.getJSONArray("tags").length()).map { i ->
                            val tagArr = event.getJSONArray("tags").getJSONArray(i)
                            (0 until tagArr.length()).map { tagArr.getString(it) }
                        },
                        content = event.getString("content"),
                        sig = event.getString("sig")
                    )
                    nostrClient.publishEvent(nostrEvent)
                    // Set signup success only once to avoid races with background tasks
                    if (signupAtom.compareAndSet(false, true)) {
                        _signupSuccess.value = true
                    }
                } else {
                    throw Exception("Login failed during signup")
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthViewModel", "Error completing signup", e)
                // Don't flip signupSuccess back to false here; leave handling to UI or explicit logout
                _loginSuccess.value = false
                throw e
            }
        }
    }

    /**
     * Clear signup success flag so the UI doesn't re-trigger the signup dialog later.
     */
    fun clearSignupSuccess() {
        signupAtom.set(false)
        _signupSuccess.value = false
    }

    /**
     * Persisted theme preference helpers. Stored alongside other secure prefs so the
     * UI can read/write the user's theme choice and restore it on next launch.
     */
    fun isDarkThemePersisted(): Boolean {
        return try {
            sharedPrefs?.getBoolean("dark_theme", false) ?: false
        } catch (e: Exception) {
            android.util.Log.w("AuthViewModel", "Unable to read dark_theme from prefs: ${e.localizedMessage}")
            false
        }
    }
    // Reactive persisted dark theme state so UI can observe and react to changes.
    // Initialize to false here and populate from sharedPrefs inside init to avoid
    // referencing sharedPrefs during property initialization (which causes a compile error).
    private val _darkTheme = MutableStateFlow(false)
    val darkTheme: StateFlow<Boolean> = _darkTheme

    fun setDarkThemePersisted(value: Boolean) {
        try {
            sharedPrefs?.edit()?.putBoolean("dark_theme", value)?.apply()
        } catch (e: Exception) {
            android.util.Log.w("AuthViewModel", "Unable to persist dark_theme: ${e.localizedMessage}")
        }
        // Update reactive state so observers recompose
    _darkTheme.value = value
    // Log for debugging theme changes
    android.util.Log.i("AuthViewModel", "dark_theme set to: $value")
    }

    fun toggleDarkTheme() {
    val newValue = !_darkTheme.value
    android.util.Log.i("AuthViewModel", "toggleDarkTheme -> $newValue")
    setDarkThemePersisted(newValue)
    }

    /**
     * Clears all user-specific data from memory, cache, and storage.
     * Call this on logout and after login to ensure a clean state.
     * Expand this as you add more repositories or ViewModels that cache user data.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun clearAllUserData(messagesViewModel: com.hisa.viewmodel.MessagesViewModel? = null) {
        // Clear encrypted shared preferences (auth)
        sharedPrefs.edit().clear().apply()
        // Clear conversations
        com.hisa.data.repository.ConversationRepository.clearAllConversations()
        // Clear in-memory messages (if provided)
        messagesViewModel?.clearMessages()
        // TODO: Add calls to clearCache() for any other repositories that cache user data
        // e.g., ServiceRepository.clearCache(), FeedViewModel.clear(), etc.
    }

    // Helper function for non-suspend callers
    @RequiresApi(Build.VERSION_CODES.O)
    fun launchClearAllUserData(messagesViewModel: com.hisa.viewmodel.MessagesViewModel? = null) {
        viewModelScope.launch {
            clearAllUserData(messagesViewModel)
        }
    }

    // Don't expose raw nsec directly; provide a safe accessor that callers must opt into
    fun getStoredNsec(): String? = try {
        sharedPrefs?.getString("nsec", null)
    } catch (e: Exception) {
        android.util.Log.w("AuthViewModel", "Unable to read nsec from prefs: ${e.localizedMessage}")
        null
    }

    // Secure storage (may fall back to regular prefs if encrypted prefs are unavailable)
    private val sharedPrefs = run {
        val context = getApplication<Application>().applicationContext
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // If EncryptedSharedPreferences fails, fallback to regular shared prefs but log the event.
            android.util.Log.w("AuthViewModel", "EncryptedSharedPreferences unavailable, falling back: ${e.localizedMessage}")
            context.getSharedPreferences("secure_prefs_fallback", android.content.Context.MODE_PRIVATE)
        }
    }

    sealed class InitState {
        object Loading : InitState()
        data class Ready(val initialRoute: String) : InitState()
    }

    private val _initState = MutableStateFlow<InitState>(InitState.Loading)
    val initState: StateFlow<InitState> = _initState

    private val _pubKey = MutableStateFlow<String?>(null)
    val pubKey: StateFlow<String?> = _pubKey
    private val _privateKey = MutableStateFlow<String?>(null)
    val privateKey: StateFlow<String?> = _privateKey
    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess

    // Indicates when a logout operation is in progress so UI can avoid starting new
    // authentication/signup flows while teardown is happening.
    private val _isLoggingOut = MutableStateFlow(false)
    val isLoggingOut: StateFlow<Boolean> = _isLoggingOut

    init {
        // Populate reactive dark theme from persisted prefs once sharedPrefs is available
        _darkTheme.value = sharedPrefs?.getBoolean("dark_theme", false) ?: false

        viewModelScope.launch {
            try {
                val savedNsec = sharedPrefs.getString("nsec", null)
                if (!savedNsec.isNullOrBlank()) {
                    loginWithNsec(savedNsec)
                    // After obtaining pubkey, attempt to fetch preferred relays (NIP-65/kind 10002)
                    _pubKey.value?.let { pub ->
                        try {
                            subscribeToPreferredRelays(pub)
                        } catch (e: Exception) {
                            android.util.Log.w("AuthViewModel", "Failed to subscribe to preferred relays: ${e.localizedMessage}")
                        }
                    }
                    _initState.value = InitState.Ready("main")
                } else {
                    // Try persisted external signer (Amber) login first
                    val savedExternalPackage = sharedPrefs.getString("external_signer_package", null)
                    val savedExternalPub = sharedPrefs.getString("external_signer_pubkey", null)
                    if (!savedExternalPackage.isNullOrBlank() && !savedExternalPub.isNullOrBlank()) {
                        // Restore pubkey in-memory and trigger external signer login flow
                        updateKeyFromExternal(savedExternalPub)
                        loginWithExternalSigner(savedExternalPackage)
                        _initState.value = InitState.Ready("main")
                    } else {
                        // Explicitly set to empty strings when no credentials found
                        _pubKey.value = ""
                        _privateKey.value = ""
                        _loginSuccess.value = false
                        _initState.value = InitState.Ready("login")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthViewModel", "Error during initialization", e)
                // Ensure state is cleared on error
                _pubKey.value = ""
                _privateKey.value = ""
                _loginSuccess.value = false
                _initState.value = InitState.Ready("login")
            }
        }
    }

    private fun subscribeToPreferredRelays(pubkeyHex: String) {
        // Subscribe via SubscriptionManager; the handler runs on SubscriptionManager's scope
        subscriptionManager.subscribeToPreferredRelays(pubkeyHex, onEvent = { event ->
            try {
                // NIP-65: event content is newline-separated relays
                val relays = event.content.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                if (relays.isNotEmpty()) {
                    // Persist to secure prefs and fallback
                    try {
                        sharedPrefs?.edit()?.putString(RELAYS_KEY, relays.joinToString("\n"))?.apply()
                    } catch (e: Exception) {
                        android.util.Log.w("AuthViewModel", "Failed to persist relays from NIP-65: ${e.localizedMessage}")
                    }
                    try {
                        val fallback = getApplication<Application>().applicationContext.getSharedPreferences("secure_prefs_fallback", android.content.Context.MODE_PRIVATE)
                        fallback.edit().putString(RELAYS_KEY, relays.joinToString("\n"))?.apply()
                    } catch (e: Exception) {
                        android.util.Log.w("AuthViewModel", "Failed to persist relays to fallback prefs: ${e.localizedMessage}")
                    }
                    // Update in-memory state and NostrClient
                    _relays.value = relays
                    try {
                        nostrClient.updateRelays(relays)
                    } catch (e: Exception) {
                        android.util.Log.w("AuthViewModel", "Failed to update NostrClient relays from NIP-65: ${e.localizedMessage}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthViewModel", "Error handling preferred relays event", e)
            }
        })
    }

    /**
     * Public API for UI to request a refresh of preferred relays (NIP-65/kind 10002).
     * If a pubkey is available, re-run the subscription flow which will update persisted
     * relays and NostrClient when an event is received.
     */
    fun refreshPreferredRelays() {
        val pub = _pubKey.value
        if (pub.isNullOrBlank()) {
            android.util.Log.w("AuthViewModel", "refreshPreferredRelays called but pubkey is not available")
            return
        }
        try {
            subscribeToPreferredRelays(pub)
        } catch (e: Exception) {
            android.util.Log.w("AuthViewModel", "Failed to refresh preferred relays: ${e.localizedMessage}")
        }
    }

    fun loginWithNsec(nsec: String) {
        try {
            val privKey = com.hisa.util.KeyGenerator.nsecToPrivateKey(nsec)
            if (privKey.size != 32) {
                _pubKey.value = null
                _loginSuccess.value = false
                return
            }
            val ecKey = ECKey.fromPrivate(privKey)
            // Use correct Nostr pubkey: x-only (32 bytes, 64 hex chars)
            val uncompressed = ecKey.decompress().pubKeyPoint.getEncoded(false)
            val xOnly = uncompressed.copyOfRange(1, 33)
            val pubkeyHex = xOnly.joinToString("") { "%02x".format(it) }
            val privKeyHex = privKey.joinToString("") { "%02x".format(it) }
            _pubKey.value = pubkeyHex
            _privateKey.value = privKeyHex
            try {
                sharedPrefs?.edit()?.putString("nsec", nsec)?.apply()
            } catch (e: Exception) {
                android.util.Log.w("AuthViewModel", "Failed to persist nsec to prefs: ${e.localizedMessage}")
            }
            // Zero raw private key bytes as soon as we've consumed them
            for (i in privKey.indices) privKey[i] = 0
            _loginSuccess.value = true
            // After we've set pubkey and private key, subscribe to preferred relays (NIP-65) so
            // the client's relay list can be updated if the user has published preferred relays.
            try {
                // Ensure client has at least the onboarding relays configured so the subscription can be sent
                val currentRelays = try { nostrClient.configuredRelays() } catch (e: Exception) { emptyList<String>() }
                if (currentRelays.isEmpty()) {
                    try {
                        nostrClient.updateRelays(Constants.ONBOARDING_RELAYS)
                        sharedPrefs?.edit()?.putString("relays", Constants.ONBOARDING_RELAYS.joinToString("\n"))?.apply()
                    } catch (e: Exception) {
                        android.util.Log.w("AuthViewModel", "Failed to seed onboarding relays before NIP-65 subscribe: ${e.localizedMessage}")
                    }
                }
                if (nostrClient.connectionState.value != com.hisa.data.nostr.NostrClient.ConnectionState.CONNECTED) {
                    nostrClient.connect()
                }
                subscribeToPreferredRelays(pubkeyHex)
            } catch (e: Exception) {
                android.util.Log.w("AuthViewModel", "Failed to subscribe to preferred relays after login: ${e.localizedMessage}")
            }
        } catch (e: Exception) {
            _pubKey.value = null
            _loginSuccess.value = false
        }
    }

    /**
     * Like [loginWithNsec] but does not flip [_loginSuccess].
     * Used during signup so auto-navigation driven by loginSuccess doesn't
     * remove the signup screen/dialog before the user can interact with it.
     */
    fun loginWithNsecSilently(nsec: String) {
        try {
            val privKey = com.hisa.util.KeyGenerator.nsecToPrivateKey(nsec)
            if (privKey.size != 32) {
                _pubKey.value = null
                return
            }
            val ecKey = ECKey.fromPrivate(privKey)
            val uncompressed = ecKey.decompress().pubKeyPoint.getEncoded(false)
            val xOnly = uncompressed.copyOfRange(1, 33)
            val pubkeyHex = xOnly.joinToString("") { "%02x".format(it) }
            val privKeyHex = privKey.joinToString("") { "%02x".format(it) }
            _pubKey.value = pubkeyHex
            _privateKey.value = privKeyHex
            try {
                sharedPrefs?.edit()?.putString("nsec", nsec)?.apply()
            } catch (e: Exception) {
                android.util.Log.w("AuthViewModel", "Failed to persist nsec to prefs: ${e.localizedMessage}")
            }
            // Zero raw private key bytes as soon as we've consumed them
            for (i in privKey.indices) privKey[i] = 0
            // Intentionally do NOT set _loginSuccess here
            // Also attempt to refresh preferred relays so a silent login still fetches the user's NIP-65 list.
            try {
                val currentRelays = try { nostrClient.configuredRelays() } catch (e: Exception) { emptyList<String>() }
                if (currentRelays.isEmpty()) {
                    try {
                        nostrClient.updateRelays(Constants.ONBOARDING_RELAYS)
                        sharedPrefs?.edit()?.putString("relays", Constants.ONBOARDING_RELAYS.joinToString("\n"))?.apply()
                    } catch (e: Exception) {
                        android.util.Log.w("AuthViewModel", "Failed to seed onboarding relays before NIP-65 subscribe (silent): ${e.localizedMessage}")
                    }
                }
                if (nostrClient.connectionState.value != com.hisa.data.nostr.NostrClient.ConnectionState.CONNECTED) {
                    nostrClient.connect()
                }
                subscribeToPreferredRelays(pubkeyHex)
            } catch (e: Exception) {
                android.util.Log.w("AuthViewModel", "Failed to subscribe to preferred relays after silent login: ${e.localizedMessage}")
            }
        } catch (e: Exception) {
            _pubKey.value = null
        }
    }

    /**
     * Called when an external signer (Amber) returns a public key.
     * Stores the pubkey in-memory and prepares for an external-signer login.
     */
    fun updateKeyFromExternal(pubkeyHex: String) {
        try {
            _pubKey.value = pubkeyHex
            // No private key is available when using external signer
            _privateKey.value = null
            try {
                sharedPrefs?.edit()?.putString("external_signer_pubkey", pubkeyHex)?.apply()
            } catch (e: Exception) {
                android.util.Log.w("AuthViewModel", "Failed to persist external signer pubkey: ${e.localizedMessage}")
            }
        } catch (e: Exception) {
            android.util.Log.e("AuthViewModel", "Error updating key from external signer", e)
        }
    }

    /**
     * Complete a login using an external signer package name. This does not store a private key;
     * it registers the external signer package and marks login as successful so the app can
     * proceed to the main UI. Signing operations should be delegated to the external signer.
     */
    fun loginWithExternalSigner(packageName: String) {
        viewModelScope.launch {
            try {
                // Persist the signer package so it can be used later when delegating signing
                try {
                    sharedPrefs?.edit()?.putString("external_signer_package", packageName)?.apply()
                } catch (e: Exception) {
                    android.util.Log.w("AuthViewModel", "Failed to persist external signer package: ${e.localizedMessage}")
                }

                // Ensure relays are seeded and client connects similar to nsec path
                val currentRelays = try { nostrClient.configuredRelays() } catch (e: Exception) { emptyList<String>() }
                if (currentRelays.isEmpty()) {
                    try {
                        nostrClient.updateRelays(Constants.ONBOARDING_RELAYS)
                        try {
                            sharedPrefs?.edit()?.putString("relays", Constants.ONBOARDING_RELAYS.joinToString("\n"))?.apply()
                        } catch (e: Exception) {
                            android.util.Log.w("AuthViewModel", "Failed to persist seeded relays: ${e.localizedMessage}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AuthViewModel", "Failed to seed onboarding relays: ${e.localizedMessage}")
                    }
                }

                if (nostrClient.connectionState.value != com.hisa.data.nostr.NostrClient.ConnectionState.CONNECTED) {
                    nostrClient.connect()
                }

                // Mark login success
                _loginSuccess.value = true
                // Configure the ExternalSignerManager with stored pubkey/package so signing calls can be delegated
                try {
                    val savedPub = sharedPrefs?.getString("external_signer_pubkey", null)
                    if (!savedPub.isNullOrBlank()) {
                        kotlinx.coroutines.runBlocking {
                            com.hisa.data.nostr.ExternalSignerManager.ensureConfigured(savedPub, packageName, getApplication<Application>().applicationContext.contentResolver)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AuthViewModel", "Failed to configure ExternalSignerManager: ${e.localizedMessage}")
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthViewModel", "Error logging in with external signer", e)
                _loginSuccess.value = false
            }
        }
    }

    // Expose persisted external signer info to other components
    fun getExternalSignerPackage(): String? = try { sharedPrefs?.getString("external_signer_package", null) } catch (e: Exception) { null }
    fun getExternalSignerPubkey(): String? = try { sharedPrefs?.getString("external_signer_pubkey", null) } catch (e: Exception) { null }

    private fun decodeNsec(nsec: String): ByteArray? {
        if (!nsec.lowercase().startsWith("nsec")) return null
        val (hrp, data) = bech32Decode(nsec) ?: return null
        if (hrp != "nsec") return null
        return convertBits(data.dropLast(6).toIntArray(), 5, 8, false)
            ?.map { it.toByte() }
            ?.toByteArray()
    }

    private fun bech32Decode(bech: String): Pair<String, List<Int>>? {
        val pos = bech.lastIndexOf('1')
        if (pos < 1 || pos + 7 > bech.length) return null
        val hrp = bech.substring(0, pos)
        val dataPart = bech.substring(pos + 1)
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val data = dataPart.map { charset.indexOf(it) }
        if (data.any { it == -1 }) return null
        return Pair(hrp, data)
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val ret = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            if (value < 0 || value shr fromBits != 0) return null
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add((acc shr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) {
                ret.add((acc shl (toBits - bits)) and maxv)
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }
        return ret
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun logout(messagesViewModel: com.hisa.viewmodel.MessagesViewModel? = null) {
        viewModelScope.launch {
            _isLoggingOut.value = true
            try {
                // Clear shared preferences first
                sharedPrefs.edit().clear().apply()
                clearAllUserData(messagesViewModel)
                
                // Reset all state
                _pubKey.value = ""
                _privateKey.value = ""
                _loginSuccess.value = false
                signupAtom.set(false)
                _signupSuccess.value = false
                
            } catch (e: Exception) {
                android.util.Log.e("AuthViewModel", "Error during logout", e)
                // Ensure state is cleared even on error
                _pubKey.value = ""
                _privateKey.value = ""
                _loginSuccess.value = false
                _signupSuccess.value = false
            }
            finally {
                _isLoggingOut.value = false
            }
        }
    }
}
