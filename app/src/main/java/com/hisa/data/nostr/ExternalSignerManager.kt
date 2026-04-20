package com.hisa.data.nostr

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.hisa.util.normalizeNostrPubkey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ExternalSignerManager (revised)
 *
 * - Avoids placing JSON into the URI/data (prevents percent-encoding errors).
 * - Strips empty "sig" from outgoing events.
 * - Builds a minimal unsigned event for the signer.
 * - Uses unique extras (no duplicate "request" key).
 * - Validates signer responses before completing pending deferreds.
 * - Normalizes pubkeys from npub (bech32) to hex format.
 */
object ExternalSignerManager {
    private val responseLogCounter = AtomicLong(0)
    private var externalPubKey: String? = null
    private var externalPackage: String? = null
    private var launcher: ((Intent) -> Unit)? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<IntentResultLocal>>()

    /**
     * Convert npub (bech32) to hex public key format.
     * Returns the hex string if valid, otherwise null.
     */
    private fun npubToHex(npub: String): String? {
        return try {
            val bech = org.bitcoinj.core.Bech32.decode(npub)
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

    /**
     * Normalize a pubkey: convert npub to hex if needed, otherwise return as-is.
     */
    private fun normalizePubkey(pubkey: String?): String? {
        if (pubkey.isNullOrBlank()) return null
        return normalizeNostrPubkey(pubkey) ?: pubkey.trim()
    }

    data class IntentResultLocal(
        val id: String? = null,
        val result: String? = null,
        val event: String? = null,
        val packageName: String? = null,
    )

    fun registerForegroundLauncher(l: (Intent) -> Unit) {
        launcher = l
    }

    fun unregisterForegroundLauncher(l: (Intent) -> Unit) {
        if (launcher == l) launcher = null
    }

    fun isLauncherRegistered(): Boolean = launcher != null

    fun newResponse(intent: Intent) {
        try {
            // Some signers return an array of results in "results"
            val resultsJson = intent.getStringExtra("results")
            if (!resultsJson.isNullOrBlank()) {
                try {
                    val arr = org.json.JSONArray(resultsJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("id")
                        val result = obj.optString("result")
                        val event = obj.optString("event")
                        val pkg = obj.optString("package")
                        if (!id.isNullOrBlank() && (!event.isNullOrBlank() || !result.isNullOrBlank())) {
                            val res = IntentResultLocal(id = id, result = result, event = event, packageName = pkg)
                            pending.remove(id)?.complete(res)
                        } else {
                            android.util.Log.w("ExternalSigner", "Ignoring array entry with empty id and no event/result payload: id=$id")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ExternalSigner", "Failed to parse results array: ${e.message}")
                }
            }

            val id = intent.getStringExtra("id")
            var result = intent.getStringExtra("result")
            // Some signers use "event", some "event_json" or EXTRA_TEXT
            val event = intent.getStringExtra("event") ?: intent.getStringExtra("event_json") ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            val pkg = intent.getStringExtra("package")

            android.util.Log.d("ExternalSigner", "newResponse RECEIVED: id=$id resultPresent=${!result.isNullOrBlank()} resultLen=${result?.length ?: 0} eventPresent=${!event.isNullOrBlank()} eventLen=${event?.length ?: 0} pkg=$pkg")

            // Normalize result if it's a pubkey (might be in npub format from signer)
            // Check if result looks like a pubkey (64 hex chars or npub format)
            if (!result.isNullOrBlank()) {
                val trimmedResult = result.trim()
                if (trimmedResult.startsWith("npub", ignoreCase = true)) {
                    val normalized = npubToHex(trimmedResult)
                    if (normalized != null) {
                        android.util.Log.d("ExternalSigner", "newResponse NORMALIZE: id=$id result_was_npub '${trimmedResult.take(12)}...' → '${normalized.take(12)}...'")
                        result = normalized
                    }
                } else if (trimmedResult.matches(Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE))) {
                    android.util.Log.d("ExternalSigner", "newResponse PUBKEY: id=$id result_is_hex_pubkey '${trimmedResult.take(12)}...'")
                }
            }

            if (!id.isNullOrBlank() && (!event.isNullOrBlank() || !result.isNullOrBlank())) {
                android.util.Log.d("ExternalSigner", "newResponse COMPLETE: id=$id with result/event")
                val res = IntentResultLocal(id = id, result = result, event = event, packageName = pkg)
                val pending_deferred = pending.remove(id)
                if (pending_deferred != null) {
                    android.util.Log.d("ExternalSigner", "newResponse COMPLETING_DEFERRED: id=$id")
                    pending_deferred.complete(res)
                } else {
                    android.util.Log.w("ExternalSigner", "newResponse NO_PENDING_DEFERRED: id=$id (might be timeout or duplicate response)")
                }
            } else {
                // If signer returned something but without id, try to complete any pending deferred with raw result
                if (!result.isNullOrBlank() || !event.isNullOrBlank()) {
                    val count = responseLogCounter.incrementAndGet()
                    if (count % 500L == 0L) {
                        android.util.Log.d("ExternalSigner", "Signer returned no id; attempting best-effort completion. presentEvent=${!event.isNullOrBlank()} presentResult=${!result.isNullOrBlank()}")
                    }
                    // best-effort: complete a single pending deferred with what we have
                    val entry = pending.values.firstOrNull()
                    if (entry != null) {
                        entry.complete(IntentResultLocal(id = null, result = result, event = event, packageName = pkg))
                    } else {
                        android.util.Log.w("ExternalSigner", "Ignored empty response from signer: id=$id eventPresent=${!event.isNullOrBlank()} resultPresent=${!result.isNullOrBlank()}")
                    }
                } else {
                    android.util.Log.w("ExternalSigner", "Ignored empty response from signer: id=$id eventPresent=${!event.isNullOrBlank()} resultPresent=${!result.isNullOrBlank()}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ExternalSigner", "Failed parsing signer response", e)
        }
    }

    suspend fun ensureConfigured(pubkeyHex: String, packageName: String, contentResolver: ContentResolver?) {
        // Normalize pubkey in case it's in npub format
        val normalizedPubkey = normalizePubkey(pubkeyHex) ?: pubkeyHex
        externalPubKey = normalizedPubkey
        externalPackage = packageName
        
        if (normalizedPubkey != pubkeyHex && pubkeyHex.startsWith("npub", ignoreCase = true)) {
            android.util.Log.d("ExternalSigner", "Normalized signer pubkey from npub to hex: ${pubkeyHex.take(12)}...→${normalizedPubkey.take(12)}...")
        }
    }

    fun getConfiguredPubkey(): String? = externalPubKey
    fun getConfiguredPackage(): String? = externalPackage

    suspend fun signEvent(eventJsonRaw: String, eventId: String, timeoutMs: Long = 60_000): IntentResultLocal {
        val pkg = externalPackage ?: throw IllegalStateException("External signer package not configured")
        val pub = externalPubKey ?: throw IllegalStateException("External signer pubkey not configured")
        val l = launcher ?: throw IllegalStateException("No ActivityResult launcher registered")

        val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val deferred = CompletableDeferred<IntentResultLocal>()
        pending[callId] = deferred

        // --- sanitize event JSON (remove empty sig) ---
        val eventJson = try {
            val obj = JSONObject(eventJsonRaw)
            if (obj.has("sig") && obj.optString("sig").isBlank()) {
                obj.remove("sig")
            }
            obj.toString()
        } catch (_: Exception) {
            eventJsonRaw
        }

        // Build a minimal unsigned event for the signer (avoid sending any sig)
        val unsignedEvent = try {
            val incoming = JSONObject(eventJsonRaw)
            val ev = JSONObject()
            if (incoming.has("pubkey")) {
                val p = incoming.optString("pubkey")
                val hex = if (p.startsWith("npub", true)) npubToHex(p) ?: p else p
                ev.put("pubkey", hex)
            }
            if (incoming.has("created_at")) ev.put("created_at", incoming.optLong("created_at"))
            if (incoming.has("kind")) ev.put("kind", incoming.optInt("kind"))
            if (incoming.has("tags")) ev.put("tags", incoming.getJSONArray("tags"))
            if (incoming.has("content")) ev.put("content", incoming.optString("content"))
            ev
        } catch (e: Exception) {
            // fallback to sending the original raw JSON as content if parsing fails
            JSONObject().apply { put("content", eventJsonRaw) }
        }

        // --- structured envelope ---
        val envelopeJson = JSONObject().apply {
            put("type", "sign_event")
            put("id", callId)
            put("request_id", callId)
            put("current_user", pub)
            put("event", unsignedEvent)
        }.toString()

        // -------------------------
        // IMPORTANT: DO NOT PUT JSON INTO THE URI DATA
        // Use a scheme-only nostrsigner: URI and pass JSON in extras/body.
        // This avoids percent-encoding and the "unexpected character %" error.
        // -------------------------

        // --- extras-only intent (scheme-only data) ---
        val extrasOnlyIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("nostrsigner:") // scheme-only URI, no JSON in the data
            `package` = pkg
            putExtra("type", "sign_event")
            putExtra("current_user", pub)
            putExtra("id", callId)
            putExtra("event_id", eventId)
            // include JSON in extras only
            putExtra("event", eventJson)
            putExtra("event_json", eventJson)
            putExtra("request_id", callId)
            putExtra("nostr_request", envelopeJson)
            putExtra(Intent.EXTRA_TEXT, envelopeJson)
        }

        android.util.Log.d("ExternalSigner", "Launching extras-only intent: pkg=$pkg callId=$callId eventId=$eventId")
        try { l(extrasOnlyIntent) } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "Extras-only launch failed: ${e.message}")
        }

        val extrasGraceMs = minOf(10_000L, timeoutMs)
        val start = System.currentTimeMillis()
        val firstResult = try { withTimeout(extrasGraceMs) { deferred.await() } } catch (_: Throwable) { null }
        if (firstResult != null) return firstResult

        // --- ACTION_SEND fallback (application/json body) ---
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            `package` = pkg
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, envelopeJson)
        }
        android.util.Log.d("ExternalSigner", "Fallback ACTION_SEND launch for pkg=$pkg callId=$callId")
        try { l(sendIntent) } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "ACTION_SEND launch failed: ${e.message}")
        }

        val secondResult = try { withTimeout(5_000L) { deferred.await() } } catch (_: Throwable) { null }
        if (secondResult != null) return secondResult

        // --- URI fallback: still use scheme-only URI, not putting raw JSON into the data field ---
        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            `package` = pkg
            putExtra("type", "sign_event")
            putExtra("current_user", pub)
            putExtra("id", callId)
            putExtra("event_id", eventId)
            putExtra("event", eventJson)
            putExtra("event_json", eventJson)
            putExtra("request_id", callId)
            putExtra("nostr_request", envelopeJson)
            putExtra(Intent.EXTRA_TEXT, envelopeJson)
        }
        android.util.Log.d("ExternalSigner", "Fallback URI launch (scheme-only) for pkg=$pkg callId=$callId")
        try { l(uriIntent) } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "URI launch failed: ${e.message}")
        }

        // --- wait remaining time for response ---
        return try {
            val elapsed = System.currentTimeMillis() - start
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) throw java.util.concurrent.TimeoutException("External signer timed out")
            withTimeout(remaining) { deferred.await() }
        } finally {
            pending.remove(callId)
        }
    }

    suspend fun nip44Decrypt(
        ciphertext: String,
        senderPubkey: String,
        timeoutMs: Long = 60_000
    ): String {
        val pkg = externalPackage ?: throw IllegalStateException("External signer package not configured")
        val pub = externalPubKey ?: throw IllegalStateException("External signer pubkey not configured")
        val l = launcher ?: throw IllegalStateException("No ActivityResult launcher registered")

        // Normalize incoming pubkey in case it's in npub format
        val normalizedSenderPubkey = normalizePubkey(senderPubkey) ?: senderPubkey
        
        android.util.Log.d("ExternalSigner", "nip44Decrypt START: callId=<pending> currentUser=${pub.take(12)} senderPubkey=${normalizedSenderPubkey.take(12)} ciphertextLen=${ciphertext.length} timeoutMs=$timeoutMs")

        val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val deferred = CompletableDeferred<IntentResultLocal>()
        pending[callId] = deferred

        val start = System.currentTimeMillis()

        // --- 1st attempt: extras-only intent (scheme-only data, safer encoding) ---
        val extrasOnlyIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("nostrsigner:")  // scheme-only URI, no content in data
            `package` = pkg
            putExtra("type", "nip44_decrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedSenderPubkey)
            // Include ciphertext in extras to avoid percent-encoding issues
            putExtra("ciphertext", ciphertext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip44Decrypt LAUNCH(extras-only): callId=$callId ciphertextLen=${ciphertext.length} senderPubkey=${normalizedSenderPubkey.take(12)}")
            l(extrasOnlyIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip44Decrypt extras-only launch failed: callId=$callId error=${e.message}")
        }

        // Wait briefly for response from extras-only attempt
        val extrasGraceMs = minOf(10_000L, timeoutMs)
        val firstResult = try { withTimeout(extrasGraceMs) { deferred.await() } } catch (t: Throwable) {
            android.util.Log.d("ExternalSigner", "nip44Decrypt extras-only timeout after ${System.currentTimeMillis() - start}ms: callId=$callId")
            null
        }
        if (firstResult != null && !firstResult.result.isNullOrBlank()) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip44Decrypt SUCCESS(extras-only): callId=$callId elapsed=${elapsed}ms resultLen=${firstResult.result!!.length}")
            return firstResult.result!!
        }
        if (firstResult != null && firstResult.result.isNullOrBlank()) {
            android.util.Log.w("ExternalSigner", "nip44Decrypt EMPTY_RESULT(extras-only): callId=$callId")
        }

        // --- 2nd attempt: URI-encoded ciphertext (original NIP-55 style) ---
        val encodedCiphertext = android.net.Uri.encode(ciphertext)
        val uriIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("nostrsigner:$encodedCiphertext")
            `package` = pkg
            putExtra("type", "nip44_decrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedSenderPubkey)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip44Decrypt LAUNCH(uri-encoded): callId=$callId ciphertextLen=${ciphertext.length} encodedLen=${encodedCiphertext.length} senderPubkey=${normalizedSenderPubkey.take(12)}")
            l(uriIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip44Decrypt uri-encoded launch failed: callId=$callId error=${e.message}")
        }

        // Wait for response
        try {
            val elapsed = System.currentTimeMillis() - start
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) {
                android.util.Log.e("ExternalSigner", "nip44Decrypt TIMEOUT: callId=$callId elapsed=${elapsed}ms totalTimeout=$timeoutMs")
                throw java.util.concurrent.TimeoutException("External signer timed out for nip44_decrypt")
            }
            val res = withTimeout(remaining) { deferred.await() }
            val totalElapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip44Decrypt SUCCESS(uri): callId=$callId elapsed=${totalElapsed}ms resultLen=${res.result?.length ?: 0}")
            if (res.result.isNullOrBlank()) {
                android.util.Log.e("ExternalSigner", "nip44Decrypt EMPTY_RESULT(uri): callId=$callId")
                throw IllegalStateException("External signer returned empty decrypt result")
            }
            return res.result!!
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.e("ExternalSigner", "nip44Decrypt FAILED: callId=$callId elapsed=${elapsed}ms error=${t.message} class=${t::class.simpleName}", t)
            throw t
        } finally {
            pending.remove(callId)
        }
    }

    suspend fun nip44Encrypt(
        plaintext: String,
        recipientPubkey: String,
        timeoutMs: Long = 60_000
    ): String {
        val pkg = externalPackage ?: throw IllegalStateException("External signer package not configured")
        val pub = externalPubKey ?: throw IllegalStateException("External signer pubkey not configured")
        val l = launcher ?: throw IllegalStateException("No ActivityResult launcher registered")

        // Normalize incoming pubkey in case it's in npub format
        val normalizedRecipientPubkey = normalizePubkey(recipientPubkey) ?: recipientPubkey
        
        android.util.Log.d("ExternalSigner", "nip44Encrypt START: callId=<pending> currentUser=${pub.take(12)} recipientPubkey=${normalizedRecipientPubkey.take(12)} plaintextLen=${plaintext.length} timeoutMs=$timeoutMs")

        val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val deferred = CompletableDeferred<IntentResultLocal>()
        pending[callId] = deferred

        val start = System.currentTimeMillis()

        // --- 1st attempt: extras-only intent (safer encoding) ---
        val extrasOnlyIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("nostrsigner:")
            `package` = pkg
            putExtra("type", "nip44_encrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedRecipientPubkey)
            // Include plaintext in extras to avoid percent-encoding issues
            putExtra("plaintext", plaintext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip44Encrypt LAUNCH(extras-only): callId=$callId plaintextLen=${plaintext.length} recipientPubkey=${normalizedRecipientPubkey.take(12)}")
            l(extrasOnlyIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip44Encrypt extras-only launch failed: callId=$callId error=${e.message}")
        }

        // Wait briefly for response from extras-only attempt
        val extrasGraceMs = minOf(10_000L, timeoutMs)
        val firstResult = try { withTimeout(extrasGraceMs) { deferred.await() } } catch (t: Throwable) {
            android.util.Log.d("ExternalSigner", "nip44Encrypt extras-only timeout after ${System.currentTimeMillis() - start}ms: callId=$callId")
            null
        }
        if (firstResult != null && !firstResult.result.isNullOrBlank()) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip44Encrypt SUCCESS(extras-only): callId=$callId elapsed=${elapsed}ms resultLen=${firstResult.result!!.length}")
            return firstResult.result!!
        }
        if (firstResult != null && firstResult.result.isNullOrBlank()) {
            android.util.Log.w("ExternalSigner", "nip44Encrypt EMPTY_RESULT(extras-only): callId=$callId")
        }

        // --- 2nd attempt: URI-encoded plaintext (traditional NIP-55 style) ---
        val encodedPlaintext = android.net.Uri.encode(plaintext)
        val uriIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("nostrsigner:$encodedPlaintext")
            `package` = pkg
            putExtra("type", "nip44_encrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedRecipientPubkey)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip44Encrypt LAUNCH(uri-encoded): callId=$callId plaintextLen=${plaintext.length} encodedLen=${encodedPlaintext.length} recipientPubkey=${normalizedRecipientPubkey.take(12)}")
            l(uriIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip44Encrypt uri-encoded launch failed: callId=$callId error=${e.message}")
        }

        try {
            val elapsed = System.currentTimeMillis() - start
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) {
                android.util.Log.e("ExternalSigner", "nip44Encrypt TIMEOUT: callId=$callId elapsed=${elapsed}ms totalTimeout=$timeoutMs")
                throw java.util.concurrent.TimeoutException("External signer timed out for nip44_encrypt")
            }
            val res = withTimeout(remaining) { deferred.await() }
            val totalElapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip44Encrypt SUCCESS(uri): callId=$callId elapsed=${totalElapsed}ms resultLen=${res.result?.length ?: 0}")
            if (res.result.isNullOrBlank()) {
                android.util.Log.e("ExternalSigner", "nip44Encrypt EMPTY_RESULT(uri): callId=$callId")
                throw IllegalStateException("External signer returned empty encrypt result")
            }
            return res.result!!
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.e("ExternalSigner", "nip44Encrypt FAILED: callId=$callId elapsed=${elapsed}ms error=${t.message} class=${t::class.simpleName}", t)
            throw t
        } finally {
            pending.remove(callId)
        }
    }
}
