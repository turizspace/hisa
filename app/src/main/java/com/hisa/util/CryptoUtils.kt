package com.hisa.util

import android.util.Base64
import com.hisa.data.nostr.NostrCanonicalJson
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Cryptographic utilities for Nostr events and Blossom uploads
 * 
 * Following NIP-01 & NIP-98 standards:
 * - Event ID calculation: SHA-256(canonical_json)
 * - File hashing: streamed SHA-256 (memory-safe)
 * - Authorization header: Base64(signed_event_json)
 * 
 * Best Practice: Keep crypto logic separate from business logic
 */
object CryptoUtils {
    
    /**
     * Calculate Nostr event ID (NIP-01)
     * ID = SHA-256(serialized event)
     * Serialized format: [0, pubkey, created_at, kind, tags, content]
     */
    fun calculateEventId(eventJson: String): String {
        return try {
            val event = JSONObject(eventJson)
            
            NostrCanonicalJson.computeEventId(event)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to calculate event ID: ${e.message}")
        }
    }
    
    /**
     * Calculate SHA-256 hash of a byte array
     * Used for file integrity & BUD event file hash tag
     */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Calculate SHA-256 hash from file (streamed for large files)
     * 
     * Best Practice: Use streaming to avoid loading entire file in memory
     * This prevents OutOfMemoryError on large files
     */
    fun sha256HexFromFile(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)  // 8KB chunks
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Calculate SHA-256 hash from URI (Android Content Provider)
     * Best Practice: Handle scoped storage on Android Q+
     */
    fun sha256HexFromUri(context: android.content.Context, uri: android.net.Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        } ?: throw IllegalArgumentException("Cannot open URI: $uri")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Encode event JSON as Base64 for NIP-98 Authorization header
     * 
     * Format: Authorization: Nostr <base64_no_wrap>
     */
    fun encodeAuthHeaderValue(eventJson: String): String {
        val encoded = Base64.encodeToString(
            eventJson.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP  // No line breaks - critical for HTTP headers
        )
        return "Nostr $encoded"
    }
    
    /**
     * Decode Authorization header value for debugging
     */
    fun decodeAuthHeaderValue(headerValue: String): String {
        val base64 = headerValue.removePrefix("Nostr ").trim()
        val decoded = Base64.decode(base64, Base64.NO_WRAP)
        return String(decoded, Charsets.UTF_8)
    }
}
