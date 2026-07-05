package com.hisa.data.nostr.blossom

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.hisa.data.nostr.NostrSigningService
import com.hisa.util.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.File

/**
 * Blossom.band HTTP client with NIP-98 authentication support
 * 
 * Features:
 * - File upload with NIP-98 signed events
 * - Progress reporting for streaming uploads
 * - Support for File and URI (Android Content Provider)
 * - Multiple auth header formats for server compatibility
 * 
 * Best Practices:
 * - Uses streaming for large files (memory-safe)
 * - Delegates signing to NostrSigningService
 * - Validates signed events before sending to server
 * - Includes detailed logging for debugging
 */
class BlossomClient(
    val baseUrl: String = "https://blossom.band",
    private val http: OkHttpClient = OkHttpClient(),
    private val signingService: NostrSigningService
) {

    data class UploadResult(val ok: Boolean, val statusCode: Int, val body: String?)

    private companion object {
        const val TAG = "BlossomClient"
    }

    /**
     * Sign a BUD event using internal signing service
     * 
     * Handles both local signing (if private key available) and
     * external signing (via Amber/Quartz)
     */
    suspend fun signEvent(
        eventJsonRaw: String,
        pubkeyHex: String,
        privKey: ByteArray? = null,
        externalSignerPubkey: String? = null,
        externalSignerPackage: String? = null,
        timeoutMs: Long = 60000L
    ): String {
        val signingContext = signingService.resolveSigningContext(
            pubkeyHint = pubkeyHex,
            localPrivateKeyBytesHint = privKey,
            externalSignerPubkeyHint = externalSignerPubkey,
            externalSignerPackageHint = externalSignerPackage
        )
        
        if (!signingContext.canSign) {
            throw IllegalStateException("No signing credentials available")
        }
        
        // Parse event to extract kind, content, tags
        val event = JSONObject(eventJsonRaw)
        val kind = event.getInt("kind")
        val content = event.getString("content")
        val tagsArray = event.getJSONArray("tags")
        val tags = mutableListOf<List<String>>()
        for (i in 0 until tagsArray.length()) {
            val tag = tagsArray.getJSONArray(i)
            val tagList = mutableListOf<String>()
            for (j in 0 until tag.length()) {
                tagList.add(tag.getString(j))
            }
            tags.add(tagList)
        }
        
        Log.d(TAG, "Signing event (kind=$kind, content_len=${content.length})")
        
        // Sign via service (which calls NostrEventSigner internally)
        val signedEventJson = signingService.signEvent(
            signingContext = signingContext,
            kind = kind,
            content = content,
            tags = tags
        )
        
        return signedEventJson.toString()
    }

    /**
     * Upload a file using streaming RequestBody
     * Best Practice: Uses streaming for memory efficiency on large files
     */
    suspend fun uploadFile(
        file: File,
        contentType: String,
        signedEvent: String,
        endpoint: String = "upload",
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): UploadResult = withContext(Dispatchers.IO) {
        val totalBytes = file.length()
        val authHeaderValue = CryptoUtils.encodeAuthHeaderValue(signedEvent)
        val url = "$baseUrl/${endpoint.trimStart('/')}"

        val mediaType = contentType.toMediaTypeOrNull() 
            ?: "application/octet-stream".toMediaTypeOrNull()

        val requestBody = object : RequestBody() {
            override fun contentType() = mediaType

            override fun contentLength(): Long = totalBytes

            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var sent = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                        sent += bytesRead
                        onProgress?.invoke(sent, totalBytes)
                    }
                }
            }
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .put(requestBody)
            .addHeader("Content-Type", contentType)
            // NIP-98 authorization header
            .addHeader("Authorization", authHeaderValue)
            // Compatibility headers for different Blossom implementations
            .addHeader("Blossom-Authorization", authHeaderValue)
            .addHeader("BlossomAuthorization", authHeaderValue)
        
        val req = reqBuilder.build()

        try {
            Log.d(TAG, "Uploading to: $url (size: $totalBytes bytes)")
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                Log.d(TAG, "Upload response: ${resp.code} (body_len=${body?.length ?: 0})")
                return@withContext UploadResult(resp.isSuccessful, resp.code, body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            return@withContext UploadResult(false, 0, e.message)
        }
    }

    /**
     * Upload a file from URI (Android Content Provider)
     * Supports scoped storage on Android Q+
     */
    suspend fun uploadFileFromUri(
        context: Context,
        uri: Uri,
        contentType: String,
        signedEvent: String,
        endpoint: String = "upload",
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): UploadResult = withContext(Dispatchers.IO) {
        // Get file size
        val totalBytes = context.contentResolver.openFileDescriptor(uri, "r")
            ?.use { it.statSize }
            ?: throw IllegalArgumentException("Cannot determine file size for URI: $uri")

        val authHeaderValue = CryptoUtils.encodeAuthHeaderValue(signedEvent)
        val url = "$baseUrl/${endpoint.trimStart('/')}"

        val mediaType = contentType.toMediaTypeOrNull() 
            ?: "application/octet-stream".toMediaTypeOrNull()

        val requestBody = object : RequestBody() {
            override fun contentType() = mediaType

            override fun contentLength(): Long = totalBytes

            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var sent = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                        sent += bytesRead
                        onProgress?.invoke(sent, totalBytes)
                    }
                } ?: throw IllegalArgumentException("Cannot open URI: $uri")
            }
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .put(requestBody)
            .addHeader("Content-Type", contentType)
            .addHeader("Authorization", authHeaderValue)
            .addHeader("Blossom-Authorization", authHeaderValue)
            .addHeader("BlossomAuthorization", authHeaderValue)
        
        val req = reqBuilder.build()

        try {
            Log.d(TAG, "Uploading URI to: $url (size: $totalBytes bytes)")
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                return@withContext UploadResult(resp.isSuccessful, resp.code, body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "URI upload failed", e)
            return@withContext UploadResult(false, 0, e.message)
        }
    }

    /**
     * HEAD /<sha> to check file existence
     */
    suspend fun headFile(shaHex: String, endpointPrefix: String = ""): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val url = if (endpointPrefix.isBlank()) {
            "$baseUrl/$shaHex"
        } else {
            "$baseUrl/${endpointPrefix.trimStart('/')}/$shaHex"
        }
        val req = Request.Builder().url(url).head().build()
        try {
            http.newCall(req).execute().use { resp ->
                return@withContext Pair(resp.isSuccessful, resp.code)
            }
        } catch (e: Exception) {
            Log.e(TAG, "HEAD request failed", e)
            return@withContext Pair(false, 0)
        }
    }

    /**
     * DELETE /<sha> with NIP-98 authentication
     */
    suspend fun deleteFile(
        shaHex: String,
        signedEvent: String,
        endpoint: String = ""
    ): UploadResult = withContext(Dispatchers.IO) {
        val authHeaderValue = CryptoUtils.encodeAuthHeaderValue(signedEvent)
        val url = if (endpoint.isBlank()) {
            "$baseUrl/$shaHex"
        } else {
            "$baseUrl/${endpoint.trimStart('/')}/$shaHex"
        }
        val reqBuilder = Request.Builder().url(url).delete()
            .addHeader("Authorization", authHeaderValue)
            .addHeader("Blossom-Authorization", authHeaderValue)
            .addHeader("BlossomAuthorization", authHeaderValue)

        val req = reqBuilder.build()
        try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                return@withContext UploadResult(resp.isSuccessful, resp.code, body)
            }
        } catch (e: Exception) {
            return@withContext UploadResult(false, 0, e.message)
        }
    }

    /**
     * Parse upload response to extract URL
     * Handles both NIP-94 nested format and flat JSON format
     */
    fun parseUploadUrl(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null
        try {
            val root = JSONObject(responseBody)
            
            // Try NIP-94 format
            if (root.has("nip94")) {
                val nip94 = root.getJSONArray("nip94")
                for (i in 0 until nip94.length()) {
                    val entry = nip94.get(i)
                    if (entry is JSONArray && entry.length() >= 2) {
                        if (entry.getString(0) == "url") {
                            return entry.getString(1)
                        }
                    }
                }
            }
            
            // Try flat format
            if (root.has("url")) {
                return root.getString("url")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse response as JSON", e)
        }
        
        // Fallback regex for unstructured responses
        try {
            val regex = Regex(""""url"\s*:\s*"([^"]+)""")
            val match = regex.find(responseBody)
            if (match != null) {
                return match.groupValues[1]
            }
        } catch (e: Exception) {
            // ignore
        }
        
        return null
    }
}
