package com.hisa.data.nostr.blossom

import android.util.Base64
import com.hisa.data.nostr.NostrEventSigner
import com.hisa.data.repository.MessageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Minimal Blossom.band client to upload media authenticated by a signed Nostr event.
 * Uses NIP-01 signing via existing `NostrEventSigner` and underlying Schnorr sign in MessageRepository.
 */
class BlossomClient(
    val baseUrl: String = "https://blossom.band",
    private val http: OkHttpClient = OkHttpClient()
) {

    data class UploadResult(val ok: Boolean, val statusCode: Int, val body: String?)

    private fun sha256Hex(bytes: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(bytes)
        return h.joinToString("") { "%02x".format(it) }
    }

    private fun sha256HexFromFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var read: Int
            while (true) {
                read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // Build a BUD-compliant authorization event (kind 24242) and return the raw JSON string
    private suspend fun buildAuthEventJson(
        pubkey: String,
        privKey: ByteArray,
        verb: String,
        fileSha256Hex: String? = null,
        content: String = ""
    ): String {
        val kind = 24242
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("t", verb))
        // expiration: 5 minutes from now
        val expiration = ((System.currentTimeMillis() / 1000L) + 300L).toString()
        tags.add(listOf("expiration", expiration))
        if (!fileSha256Hex.isNullOrBlank()) {
            tags.add(listOf("x", fileSha256Hex))
        }
    val evt = NostrEventSigner.signEvent(kind, content, tags, pubkey, privKey)
    return evt.toString()
    }

    // Base64-encode the event JSON and format as Authorization: Nostr <base64>
    private fun buildAuthorizationHeaderValue(eventJson: String): String {
        return "Nostr ${Base64.encodeToString(eventJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}"
    }

    /**
     * Upload a file using streaming RequestBody and report progress via [onProgress].
     * endpoint can be "upload", "media", or "mirror" depending on Blossom API.
     */
    suspend fun uploadFile(
        file: File,
        contentType: String,
        pubkeyHex: String,
        privKey: ByteArray,
        endpoint: String = "upload",
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): UploadResult = withContext(Dispatchers.IO) {
    val totalBytes = file.length()
    val sha = sha256HexFromFile(file)
    val authEventJson = if (pubkeyHex.isNotBlank() && privKey.isNotEmpty()) buildAuthEventJson(pubkeyHex, privKey, verb = "upload", fileSha256Hex = sha, content = "Upload ${file.name}") else ""
    val authHeaderValue = if (authEventJson.isNotBlank()) buildAuthorizationHeaderValue(authEventJson) else ""

        val url = "$baseUrl/${endpoint.trimStart('/') }"

        val mediaType = contentType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()

        val requestBody = object : RequestBody() {
            override fun contentType() = mediaType

            override fun contentLength(): Long = totalBytes

            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    var sent = 0L
                    while (true) {
                        read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        sent += read
                        onProgress?.invoke(sent, totalBytes)
                    }
                }
            }
        }

        val reqBuilder = Request.Builder().url(url).put(requestBody).addHeader("Content-Type", contentType)
        if (authHeaderValue.isNotBlank()) {
            // Primary BUD header
            reqBuilder.addHeader("Authorization", authHeaderValue)
            // Compatibility headers
            reqBuilder.addHeader("Blossom-Authorization", authHeaderValue)
            reqBuilder.addHeader("BlossomAuthorization", authHeaderValue)
            // Also include the raw event JSON for servers that expect X-Nostr-Auth
            // Encode the JSON to ensure it contains only valid header characters
            val encodedAuthJson = java.net.URLEncoder.encode(authEventJson, "UTF-8")
            reqBuilder.addHeader("X-Nostr-Auth", encodedAuthJson)
        }
        val req = reqBuilder.build()

        try {
            http.newCall(req).execute().use { resp ->
                val b = resp.body?.string()
                return@withContext UploadResult(resp.isSuccessful, resp.code, b)
            }
        } catch (e: Exception) {
            return@withContext UploadResult(false, 0, e.message)
        }
    }

    /**
     * HEAD /<sha> to check existence. Returns pair(ok, statusCode)
     */
    suspend fun headFile(shaHex: String, endpointPrefix: String = ""): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val url = if (endpointPrefix.isBlank()) "$baseUrl/$shaHex" else "$baseUrl/${endpointPrefix.trimStart('/')}/$shaHex"
        val req = Request.Builder().url(url).head().build()
        try {
            http.newCall(req).execute().use { resp ->
                return@withContext Pair(resp.isSuccessful, resp.code)
            }
        } catch (e: Exception) {
            return@withContext Pair(false, 0)
        }
    }

    /**
     * DELETE /<sha> with authentication
     */
    suspend fun deleteFile(
        shaHex: String,
        pubkeyHex: String,
        privKey: ByteArray,
        endpointPrefix: String = ""
    ): UploadResult = withContext(Dispatchers.IO) {
        val authEventJson = if (pubkeyHex.isNotBlank() && privKey.isNotEmpty()) buildAuthEventJson(pubkeyHex, privKey, verb = "delete", fileSha256Hex = shaHex, content = "Delete $shaHex") else ""
        val authHeaderValue = if (authEventJson.isNotBlank()) buildAuthorizationHeaderValue(authEventJson) else ""
        val url = if (endpointPrefix.isBlank()) "$baseUrl/$shaHex" else "$baseUrl/${endpointPrefix.trimStart('/')}/$shaHex"
        val reqBuilder = Request.Builder().url(url).delete()
        if (authHeaderValue.isNotBlank()) {
            reqBuilder.addHeader("Authorization", authHeaderValue)
            reqBuilder.addHeader("Blossom-Authorization", authHeaderValue)
            reqBuilder.addHeader("BlossomAuthorization", authHeaderValue)
            reqBuilder.addHeader("X-Nostr-Auth", authEventJson)
        }
        val req = reqBuilder.build()
        try {
            http.newCall(req).execute().use { resp ->
                val b = resp.body?.string()
                return@withContext UploadResult(resp.isSuccessful, resp.code, b)
            }
        } catch (e: Exception) {
            return@withContext UploadResult(false, 0, e.message)
        }
    }

    // Parse blossom upload response body and prefer NIP-94 nested url, fallback to top-level url.
    fun parseUploadUrl(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null
        try {
            val root = JSONObject(responseBody)
            if (root.has("nip94")) {
                val nip94 = root.getJSONArray("nip94")
                for (i in 0 until nip94.length()) {
                    val entry = nip94.get(i)
                    if (entry is JSONArray && entry.length() >= 2) {
                        val key = entry.getString(0)
                        if (key == "url") return entry.getString(1)
                    }
                }
            }
            if (root.has("url")) return root.getString("url")
        } catch (e: Exception) {
            // ignore
        }
        // Last-ditch regex fallback
        try {
            val regex = Regex("\\[\\s*\"url\"\\s*,\\s*\"([^\"]+)\"")
            val m = regex.find(responseBody)
            if (m != null && m.groups.size > 1) return m.groups[1]!!.value
        } catch (e: Exception) {}
        return null
    }
}
