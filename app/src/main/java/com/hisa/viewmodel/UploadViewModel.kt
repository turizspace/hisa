package com.hisa.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.nostr.blossom.BlossomClient
import com.hisa.util.BudEventBuilder
import com.hisa.util.CryptoUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File
import kotlin.math.pow

/**
 * Upload ViewModel with comprehensive retry and error handling
 * 
 * Features:
 * - Exponential backoff retry (1.5x multiplier)
 * - Timeout management
 * - Progress reporting
 * - State management with StateFlow
 * - Detailed error information
 * 
 * Best Practices:
 * - Use StateFlow for reactive UI updates
 * - Implement exponential backoff (not linear)
 * - Timeout individual steps independently
 * - Trust external signer for verification
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blossomClient: BlossomClient
) : ViewModel() {

    sealed class UploadState {
        object Idle : UploadState()
        data class CalculatingHash(val progress: String = "Calculating file hash...") : UploadState()
        data class GeneratingEvent(val progress: String = "Generating upload descriptor...") : UploadState()
        data class WaitingForSigner(val progress: String = "Waiting for signature from Amber...") : UploadState()
        data class Uploading(val bytesSent: Long, val totalBytes: Long) : UploadState()
        data class Success(val url: String) : UploadState()
        data class Error(
            val message: String,
            val canRetry: Boolean = false
        ) : UploadState()
    }

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    // Retry configuration (tunable per business needs)
    private val maxRetries = 3
    private val initialDelayMs = 500L
    private val timeoutPerStepMs = 60000L  // 60 seconds per major step

    private companion object {
        const val TAG = "UploadViewModel"
    }

    /**
     * Upload file from File object with external signer
     * 
     * Flow:
     * 1. Validate file exists
     * 2. Calculate file SHA-256 (streamed)
     * 3. Generate BUD event (NIP-98 compliant)
     * 4. Request signature from external signer (NIP-55)
     * 5. Upload file with signed event to Blossom
     * 6. Retry with exponential backoff on transient failures
     */
    fun uploadFile(
        file: File,
        contentType: String,
        pubkeyHex: String,
        privKey: ByteArray?,
        endpoint: String = "upload",
        externalSignerPubkey: String? = null,
        externalSignerPackage: String? = null,
        onUrlCallback: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            var attempt = 0
            var lastError: String? = null

            while (attempt < maxRetries) {
                try {
                    if (attempt > 0) {
                        // Exponential backoff: 500ms, 750ms, 1.1s...
                        val delayMs = (initialDelayMs * 1.5.pow(attempt - 1)).toLong()
                        Log.d(TAG, "Retry attempt $attempt after ${delayMs}ms")
                        delay(delayMs)
                    }

                    // Step 1: Validate file
                    if (!file.exists()) {
                        throw IllegalArgumentException("File does not exist: ${file.absolutePath}")
                    }
                    if (file.length() == 0L) {
                        throw IllegalArgumentException("File is empty")
                    }

                    // Step 2: Calculate file hash (streamed, memory-safe)
                    _uploadState.value = UploadState.CalculatingHash("Calculating file hash...")
                    val fileSha256 = CryptoUtils.sha256HexFromFile(file)
                    Log.d(TAG, "File hash computed: ${fileSha256.take(16)}...")

                    // Step 3: Generate BUD event
                    _uploadState.value = UploadState.GeneratingEvent("Generating upload descriptor...")
                    val budEvent = BudEventBuilder.buildUnsignedEvent(
                        pubkey = pubkeyHex,
                        fileSha256 = fileSha256,
                        verb = "upload",
                        expirationSeconds = 300L,
                        filename = file.name
                    )

                    // Validate event before sending to signer
                    if (!BudEventBuilder.validateUnsignedEvent(budEvent)) {
                        throw IllegalStateException("Generated invalid BUD event - validation failed")
                    }
                    Log.d(TAG, "BUD event generated and validated")

                    // Step 4: Request signature from external signer
                    _uploadState.value = UploadState.WaitingForSigner("Waiting for signature from Amber...")
                    val signedEvent = blossomClient.signEvent(
                        eventJsonRaw = budEvent,
                        pubkeyHex = pubkeyHex,
                        privKey = privKey,
                        externalSignerPubkey = externalSignerPubkey,
                        externalSignerPackage = externalSignerPackage,
                        timeoutMs = timeoutPerStepMs
                    )

                    // Validate signed event before upload
                    if (!BudEventBuilder.validateSignedEvent(signedEvent)) {
                        throw IllegalStateException("Signed event validation failed - missing id or sig")
                    }
                    Log.d(TAG, "Event signed successfully and validated")

                    // Step 5: Upload file with signed event
                    _uploadState.value = UploadState.Uploading(0, file.length())
                    val result = blossomClient.uploadFile(
                        file = file,
                        contentType = contentType,
                        signedEvent = signedEvent,
                        endpoint = endpoint
                    ) { sent, total ->
                        _uploadState.value = UploadState.Uploading(sent, total)
                    }

                    if (result.ok && !result.body.isNullOrBlank()) {
                        val uploadedUrl = blossomClient.parseUploadUrl(result.body)
                            ?: result.body.take(100)
                        
                        Log.d(TAG, "Upload successful: ${uploadedUrl.take(40)}...")
                        _uploadState.value = UploadState.Success(uploadedUrl)
                        onUrlCallback?.invoke(uploadedUrl)
                        return@launch
                    } else {
                        lastError = "Upload failed: ${result.statusCode} - ${result.body ?: "no response"}"
                        Log.w(TAG, lastError)
                        throw Exception(lastError)
                    }

                } catch (e: Exception) {
                    lastError = when (e) {
                        is kotlinx.coroutines.TimeoutCancellationException ->
                            "Request timeout (${timeoutPerStepMs}ms) - check if Amber app is responsive"
                        is IllegalArgumentException -> e.message ?: "Invalid argument"
                        is IllegalStateException -> e.message ?: "Signer error"
                        else -> e.message ?: "Unknown error: ${e::class.simpleName}"
                    }

                    Log.e(TAG, "Upload attempt $attempt failed: $lastError", e)
                }

                attempt++
            }

            // All retries exhausted
            val finalError = lastError ?: "Upload failed after $maxRetries attempts"
            Log.e(TAG, "Upload failed permanently: $finalError")
            _uploadState.value = UploadState.Error(
                message = finalError,
                canRetry = true
            )
        }
    }

    /**
     * Upload file from URI (for Android Content Provider / scoped storage)
     */
    fun uploadFileFromUri(
        uri: Uri,
        contentType: String,
        pubkeyHex: String,
        privKey: ByteArray?,
        endpoint: String = "upload",
        externalSignerPubkey: String? = null,
        externalSignerPackage: String? = null,
        onUrlCallback: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            var attempt = 0
            var lastError: String? = null

            while (attempt < maxRetries) {
                try {
                    if (attempt > 0) {
                        val delayMs = (initialDelayMs * 1.5.pow(attempt - 1)).toLong()
                        Log.d(TAG, "Retry attempt $attempt after ${delayMs}ms")
                        delay(delayMs)
                    }

                    // Calculate file hash from URI
                    _uploadState.value = UploadState.CalculatingHash("Calculating file hash...")
                    val fileSha256 = CryptoUtils.sha256HexFromUri(context, uri)
                    Log.d(TAG, "File hash computed: ${fileSha256.take(16)}...")

                    // Generate BUD event
                    _uploadState.value = UploadState.GeneratingEvent("Generating upload descriptor...")
                    val filename = getFileNameFromUri(uri) ?: "upload"
                    val budEvent = BudEventBuilder.buildUnsignedEvent(
                        pubkey = pubkeyHex,
                        fileSha256 = fileSha256,
                        verb = "upload",
                        expirationSeconds = 300L,
                        filename = filename
                    )

                    if (!BudEventBuilder.validateUnsignedEvent(budEvent)) {
                        throw IllegalStateException("Generated invalid BUD event")
                    }

                    // Request signature
                    _uploadState.value = UploadState.WaitingForSigner("Waiting for signature from Amber...")
                    val signedEvent = blossomClient.signEvent(
                        eventJsonRaw = budEvent,
                        pubkeyHex = pubkeyHex,
                        privKey = privKey,
                        externalSignerPubkey = externalSignerPubkey,
                        externalSignerPackage = externalSignerPackage,
                        timeoutMs = timeoutPerStepMs
                    )

                    if (!BudEventBuilder.validateSignedEvent(signedEvent)) {
                        throw IllegalStateException("Signed event validation failed")
                    }

                    // Upload
                    _uploadState.value = UploadState.Uploading(0, 0)
                    val result = blossomClient.uploadFileFromUri(
                        context = context,
                        uri = uri,
                        contentType = contentType,
                        signedEvent = signedEvent,
                        endpoint = endpoint
                    ) { sent, total ->
                        _uploadState.value = UploadState.Uploading(sent, total)
                    }

                    if (result.ok && !result.body.isNullOrBlank()) {
                        val uploadedUrl = blossomClient.parseUploadUrl(result.body)
                            ?: result.body.take(100)
                        
                        Log.d(TAG, "Upload successful: ${uploadedUrl.take(40)}...")
                        _uploadState.value = UploadState.Success(uploadedUrl)
                        onUrlCallback?.invoke(uploadedUrl)
                        return@launch
                    } else {
                        lastError = "Upload failed: ${result.statusCode}"
                        throw Exception(lastError)
                    }

                } catch (e: Exception) {
                    lastError = e.message ?: "Unknown error"
                    Log.e(TAG, "Upload URI attempt $attempt failed: $lastError", e)
                }

                attempt++
            }

            _uploadState.value = UploadState.Error(
                message = lastError ?: "Upload failed after $maxRetries attempts",
                canRetry = true
            )
        }
    }

    /**
     * Get filename from URI (for logging/naming purposes)
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not get filename from URI", e)
            null
        }
    }

    fun reset() {
        _uploadState.value = UploadState.Idle
    }
}
