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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ExternalSignerManager (revised)
 *
 * - Sends NIP-55 payloads through the nostrsigner: URI and mirrors them in extras.
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
    private var resolver: ContentResolver? = null
    private var launcher: ((Intent) -> Unit)? = null
    private val launcherRegistered = AtomicBoolean(false)

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
        val rejected: Boolean = false,
    )

    private data class ResolverResult(
        val result: String?,
        val event: String?,
        val rejected: Boolean = false,
    )

    fun registerForegroundLauncher(l: (Intent) -> Unit) {
        launcher = l
        launcherRegistered.set(true)
        android.util.Log.d("ExternalSigner", "Launcher registered and ready for decryption")
    }

    fun unregisterForegroundLauncher(l: (Intent) -> Unit) {
        if (launcher == l) {
            launcher = null
            launcherRegistered.set(false)
            android.util.Log.d("ExternalSigner", "Launcher unregistered")
        }
    }

    fun isLauncherRegistered(): Boolean = launcher != null

    fun hasBackgroundResolver(): Boolean = resolver != null

    /**
     * Check if launcher is registered without blocking
     */
    fun isLauncherReady(): Boolean = launcherRegistered.get()

    /**
     * Wait for the launcher to be registered (with timeout)
     * Returns true if launcher became ready, false if timeout
     */
    suspend fun waitForLauncherReady(timeoutMs: Long = 10_000): Boolean {
        val startTime = System.currentTimeMillis()
        while (!launcherRegistered.get()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                android.util.Log.w("ExternalSigner", "Timeout waiting for launcher registration after ${timeoutMs}ms")
                return false
            }
            kotlinx.coroutines.delay(50)
        }
        android.util.Log.d("ExternalSigner", "Launcher ready after ${System.currentTimeMillis() - startTime}ms")
        return true
    }

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
                        val result = obj.optString("result").ifBlank { obj.optString("signature") }
                        val event = obj.optString("event")
                        val pkg = obj.optString("package")
                        val rejected = obj.optBoolean("rejected", false)
                        if (!id.isNullOrBlank() && (rejected || !event.isNullOrBlank() || !result.isNullOrBlank())) {
                            val res = IntentResultLocal(id = id, result = result, event = event, packageName = pkg, rejected = rejected)
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
            var result = intent.getStringExtra("result") ?: intent.getStringExtra("signature")
            // Some signers use "event", some "event_json" or EXTRA_TEXT
            val event = intent.getStringExtra("event") ?: intent.getStringExtra("event_json") ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            val pkg = intent.getStringExtra("package")
            val rejected = intent.getBooleanExtra("rejected", false)

            android.util.Log.d("ExternalSigner", "newResponse RECEIVED: id=$id rejected=$rejected resultPresent=${!result.isNullOrBlank()} resultLen=${result?.length ?: 0} eventPresent=${!event.isNullOrBlank()} eventLen=${event?.length ?: 0} pkg=$pkg")

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

            if (!id.isNullOrBlank() && (rejected || !event.isNullOrBlank() || !result.isNullOrBlank())) {
                android.util.Log.d("ExternalSigner", "newResponse COMPLETE: id=$id with result/event")
                val res = IntentResultLocal(id = id, result = result, event = event, packageName = pkg, rejected = rejected)
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
        if (contentResolver != null) {
            resolver = contentResolver
        }
        
        if (normalizedPubkey != pubkeyHex && pubkeyHex.startsWith("npub", ignoreCase = true)) {
            android.util.Log.d("ExternalSigner", "Normalized signer pubkey from npub to hex: ${pubkeyHex.take(12)}...→${normalizedPubkey.take(12)}...")
        }
    }

    fun getConfiguredPubkey(): String? = externalPubKey
    fun getConfiguredPackage(): String? = externalPackage

    fun clearConfiguration() {
        externalPubKey = null
        externalPackage = null
        resolver = null
    }

    private fun maybeQueryResolver(
        authoritySuffix: String,
        args: List<String>,
        needsEvent: Boolean = false
    ): ResolverResult? {
        val pkg = externalPackage ?: return null
        val contentResolver = resolver ?: return null
        return try {
            val cursor = contentResolver.query(
                Uri.parse("content://$pkg.$authoritySuffix"),
                args.toTypedArray(),
                null,
                null,
                null
            ) ?: return null
            cursor.use {
                if (it.getColumnIndex("rejected") > -1) {
                    return ResolverResult(result = null, event = null, rejected = true)
                }
                if (!it.moveToFirst()) return null
                val resultIndex = it.getColumnIndex("result").takeIf { idx -> idx >= 0 }
                    ?: it.getColumnIndex("signature").takeIf { idx -> idx >= 0 }
                val eventIndex = it.getColumnIndex("event").takeIf { idx -> idx >= 0 }
                val result = resultIndex?.let { idx -> it.getString(idx) }
                val event = eventIndex?.let { idx -> it.getString(idx) }
                if (needsEvent && event.isNullOrBlank() && result.isNullOrBlank()) return null
                if (!needsEvent && result.isNullOrBlank()) return null
                ResolverResult(result = result, event = event)
            }
        } catch (e: Exception) {
            android.util.Log.d("ExternalSigner", "Content resolver $authoritySuffix unavailable: ${e.message}")
            null
        }
    }

    private fun buildSignedEventFromSignature(eventJson: String, signature: String?, fallbackId: String? = null): String? {
        if (signature.isNullOrBlank()) return null
        return try {
            val parsed = JSONObject(eventJson)
            if (!fallbackId.isNullOrBlank() && !parsed.has("id")) {
                parsed.put("id", fallbackId)
            }
            parsed.put("sig", signature)
            parsed.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun nostrSignerUri(payload: String = ""): Uri {
        return if (payload.isEmpty()) {
            Uri.parse("nostrsigner:")
        } else {
            Uri.parse("nostrsigner:${Uri.encode(payload)}")
        }
    }

    private fun rawNostrSignerUri(payload: String = ""): Uri {
        return if (payload.isEmpty()) Uri.parse("nostrsigner:") else Uri.parse("nostrsigner:$payload")
    }

    private fun IntentResultLocal.requireAccepted(type: String): IntentResultLocal {
        if (rejected) throw IllegalStateException("External signer rejected $type")
        return this
    }

    private fun IntentResultLocal.requireSignEventPayload(eventJson: String): IntentResultLocal {
        val accepted = requireAccepted("sign_event")
        if (!accepted.event.isNullOrBlank()) return accepted
        val signedEvent = buildSignedEventFromSignature(eventJson, accepted.result, accepted.id)
            ?: throw IllegalStateException("External signer returned empty signed event")
        return accepted.copy(event = signedEvent)
    }

    suspend fun signEvent(eventJsonRaw: String, eventId: String, timeoutMs: Long = 60_000): IntentResultLocal {
        val pkg = externalPackage ?: throw IllegalStateException("External signer package not configured")
        val pub = externalPubKey ?: throw IllegalStateException("External signer pubkey not configured")

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
                ev.put("pubkey", hex.lowercase())
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

        val resolverResult = maybeQueryResolver(
            authoritySuffix = "SIGN_EVENT",
            args = listOf(eventJson, "", pub),
            needsEvent = true
        )
        if (resolverResult?.rejected == true) {
            throw IllegalStateException("External signer rejected sign_event")
        }
        if (resolverResult != null) {
            val signedEvent = resolverResult.event
                ?: buildSignedEventFromSignature(eventJson, resolverResult.result)
                ?: throw IllegalStateException("External signer returned empty signed event")
            return IntentResultLocal(
                id = eventId,
                result = resolverResult.result,
                event = signedEvent,
                packageName = pkg
            )
        }

        val l = launcher ?: throw IllegalStateException("No ActivityResult launcher registered")

        val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val deferred = CompletableDeferred<IntentResultLocal>()
        pending[callId] = deferred

        // --- structured envelope for signers that also inspect extras ---
        val envelopeJson = JSONObject().apply {
            put("type", "sign_event")
            put("id", callId)
            put("request_id", callId)
            put("current_user", pub)
            put("event", unsignedEvent)
        }.toString()

        // NIP-55 expects the payload in the nostrsigner: URI data. Keep the
        // raw event extras as compatibility hints for signers that read extras.
        val payloadIntent = Intent(Intent.ACTION_VIEW, nostrSignerUri(eventJson)).apply {
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "sign_event")
            putExtra("current_user", pub)
            putExtra("id", callId)
            putExtra("event_id", eventId)
            putExtra("event", eventJson)
            putExtra("event_json", eventJson)
            putExtra("payload", eventJson)
            putExtra("request_id", callId)
            putExtra("nostr_request", envelopeJson)
            putExtra(Intent.EXTRA_TEXT, eventJson)
        }

        android.util.Log.d("ExternalSigner", "Launching NIP-55 sign_event intent: pkg=$pkg callId=$callId eventId=$eventId payloadLen=${eventJson.length}")
        try { l(payloadIntent) } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "NIP-55 sign_event launch failed: ${e.message}")
        }

        // Wait briefly for NIP-55 URI response. If it doesn't respond quickly, fall through
        // to raw payload URI which is more reliable at actually delivering the sign request.
        val start = System.currentTimeMillis()
        val firstResult = try { withTimeout(1_500L) { deferred.await() } } catch (_: Throwable) { null }
        if (firstResult != null) {
            android.util.Log.d("ExternalSigner", "Sign_event response via NIP-55 after ${System.currentTimeMillis() - start}ms")
            return firstResult.requireSignEventPayload(eventJson)
        }
        android.util.Log.d("ExternalSigner", "NIP-55 URI did not respond quickly; trying raw payload URI")

        // Fallback: try raw payload URI (this is the one that reliably delivers the sign request)
        val rawPayloadIntent = Intent(Intent.ACTION_VIEW, rawNostrSignerUri(eventJson)).apply {
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "sign_event")
            putExtra("current_user", pub)
            putExtra("id", callId)
            putExtra("event_id", eventId)
            putExtra("event", eventJson)
            putExtra("event_json", eventJson)
            putExtra("payload", eventJson)
            putExtra("request_id", callId)
            putExtra("nostr_request", envelopeJson)
            putExtra(Intent.EXTRA_TEXT, eventJson)
        }
        android.util.Log.d("ExternalSigner", "Launching raw-payload sign_event intent for pkg=$pkg callId=$callId")
        try { l(rawPayloadIntent) } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "Raw-payload sign_event launch failed: ${e.message}")
        }

        val secondResult = try { withTimeout(30_000L) { deferred.await() } } catch (_: Throwable) { null }
        if (secondResult != null) {
            android.util.Log.d("ExternalSigner", "Sign_event response via raw payload after ${System.currentTimeMillis() - start}ms")
            return secondResult.requireSignEventPayload(eventJson)
        }

        // --- ACTION_SEND fallback (application/json body) ---
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            `package` = pkg
            type = "application/json"
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "sign_event")
            putExtra("current_user", pub)
            putExtra("id", callId)
            putExtra("event_id", eventId)
            putExtra("event", eventJson)
            putExtra("event_json", eventJson)
            putExtra("payload", eventJson)
            putExtra("request_id", callId)
            putExtra("nostr_request", envelopeJson)
            putExtra(Intent.EXTRA_TEXT, eventJson)
        }
        android.util.Log.d("ExternalSigner", "Launching fallback ACTION_SEND intent for pkg=$pkg callId=$callId")
        try { l(sendIntent) } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "ACTION_SEND launch failed: ${e.message}")
        }

        val thirdResult = try { withTimeout(10_000L) { deferred.await() } } catch (_: Throwable) { null }
        if (thirdResult != null) {
            android.util.Log.d("ExternalSigner", "Sign_event response via ACTION_SEND after ${System.currentTimeMillis() - start}ms")
            return thirdResult.requireSignEventPayload(eventJson)
        }

        // --- extras-only fallback for older/non-standard signer builds ---
        val extrasOnlyIntent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "sign_event")
            putExtra("current_user", pub)
            putExtra("id", callId)
            putExtra("event_id", eventId)
            putExtra("event", eventJson)
            putExtra("event_json", eventJson)
            putExtra("payload", eventJson)
            putExtra("request_id", callId)
            putExtra("nostr_request", envelopeJson)
            putExtra(Intent.EXTRA_TEXT, eventJson)
        }
        android.util.Log.d("ExternalSigner", "Fallback extras-only sign_event launch for pkg=$pkg callId=$callId")
        try { l(extrasOnlyIntent) } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "Extras-only sign_event launch failed: ${e.message}")
        }

        // --- wait remaining time for response ---
        return try {
            val elapsed = System.currentTimeMillis() - start
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) throw java.util.concurrent.TimeoutException("External signer timed out")
            withTimeout(remaining) { deferred.await() }.requireSignEventPayload(eventJson)
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

        // Normalize incoming pubkey in case it's in npub format
        val normalizedSenderPubkey = normalizePubkey(senderPubkey) ?: senderPubkey
        
        android.util.Log.d("ExternalSigner", "nip44Decrypt START: callId=<pending> currentUser=${pub.take(12)} senderPubkey=${normalizedSenderPubkey.take(12)} ciphertextLen=${ciphertext.length} timeoutMs=$timeoutMs")

        val resolverResult = maybeQueryResolver(
            authoritySuffix = "NIP44_DECRYPT",
            args = listOf(ciphertext, normalizedSenderPubkey, pub)
        )
        if (resolverResult?.rejected == true) {
            throw IllegalStateException("External signer rejected nip44_decrypt")
        }
        if (!resolverResult?.result.isNullOrBlank()) {
            android.util.Log.d("ExternalSigner", "nip44Decrypt SUCCESS(content-resolver): resultLen=${resolverResult!!.result!!.length}")
            return resolverResult.result
        }

        val l = launcher ?: throw IllegalStateException("No ActivityResult launcher registered")

        val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val deferred = CompletableDeferred<IntentResultLocal>()
        pending[callId] = deferred

        val start = System.currentTimeMillis()

        // NIP-55 puts the encrypted text in the nostrsigner: URI and sends the
        // peer pubkey/current user as extras. Keep the ciphertext extra only as
        // a compatibility hint for signers that also read extras.
        val uriIntent = Intent(Intent.ACTION_VIEW, nostrSignerUri(ciphertext)).apply {
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "nip44_decrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedSenderPubkey)
            putExtra("ciphertext", ciphertext)
            putExtra("payload", ciphertext)
            putExtra(Intent.EXTRA_TEXT, ciphertext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip44Decrypt LAUNCH(nip55-uri): callId=$callId ciphertextLen=${ciphertext.length} senderPubkey=${normalizedSenderPubkey.take(12)}")
            l(uriIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip44Decrypt nip55-uri launch failed: callId=$callId error=${e.message}")
        }

        // Wait briefly before falling back to raw URI/extras shapes.
        val extrasGraceMs = minOf(2_500L, timeoutMs)
        val firstResult = try { withTimeout(extrasGraceMs) { deferred.await() } } catch (t: Throwable) {
            android.util.Log.d("ExternalSigner", "nip44Decrypt nip55-uri timeout after ${System.currentTimeMillis() - start}ms: callId=$callId")
            null
        }
        val acceptedFirstResult = firstResult?.requireAccepted("nip44_decrypt")
        if (acceptedFirstResult != null && !acceptedFirstResult.result.isNullOrBlank()) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip44Decrypt SUCCESS(nip55-uri): callId=$callId elapsed=${elapsed}ms resultLen=${acceptedFirstResult.result!!.length}")
            return acceptedFirstResult.result!!
        }
        if (acceptedFirstResult != null && acceptedFirstResult.result.isNullOrBlank()) {
            android.util.Log.w("ExternalSigner", "nip44Decrypt EMPTY_RESULT(nip55-uri): callId=$callId")
        }

        val rawUriIntent = Intent(Intent.ACTION_VIEW, rawNostrSignerUri(ciphertext)).apply {
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "nip44_decrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedSenderPubkey)
            putExtra("ciphertext", ciphertext)
            putExtra("payload", ciphertext)
            putExtra(Intent.EXTRA_TEXT, ciphertext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip44Decrypt LAUNCH(raw-uri-fallback): callId=$callId ciphertextLen=${ciphertext.length} senderPubkey=${normalizedSenderPubkey.take(12)}")
            l(rawUriIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip44Decrypt raw URI fallback launch failed: callId=$callId error=${e.message}")
        }

        val secondResult = try { withTimeout(2_500L) { deferred.await() } } catch (t: Throwable) {
            android.util.Log.d("ExternalSigner", "nip44Decrypt raw-uri timeout after ${System.currentTimeMillis() - start}ms: callId=$callId")
            null
        }
        val acceptedSecondResult = secondResult?.requireAccepted("nip44_decrypt")
        if (acceptedSecondResult != null && !acceptedSecondResult.result.isNullOrBlank()) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip44Decrypt SUCCESS(raw-uri): callId=$callId elapsed=${elapsed}ms resultLen=${acceptedSecondResult.result!!.length}")
            return acceptedSecondResult.result!!
        }
        if (acceptedSecondResult != null && acceptedSecondResult.result.isNullOrBlank()) {
            android.util.Log.w("ExternalSigner", "nip44Decrypt EMPTY_RESULT(raw-uri): callId=$callId")
        }

        val extrasOnlyIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("nostrsigner:")
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "nip44_decrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedSenderPubkey)
            putExtra("ciphertext", ciphertext)
            putExtra("payload", ciphertext)
            putExtra(Intent.EXTRA_TEXT, ciphertext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip44Decrypt LAUNCH(extras-only-fallback): callId=$callId ciphertextLen=${ciphertext.length} senderPubkey=${normalizedSenderPubkey.take(12)}")
            l(extrasOnlyIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip44Decrypt extras-only fallback launch failed: callId=$callId error=${e.message}")
        }

        // Wait for response
        try {
            val elapsed = System.currentTimeMillis() - start
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) {
                android.util.Log.e("ExternalSigner", "nip44Decrypt TIMEOUT: callId=$callId elapsed=${elapsed}ms totalTimeout=$timeoutMs")
                throw java.util.concurrent.TimeoutException("External signer timed out for nip44_decrypt")
            }
            val res = withTimeout(remaining) { deferred.await() }.requireAccepted("nip44_decrypt")
            val totalElapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip44Decrypt SUCCESS(fallback): callId=$callId elapsed=${totalElapsed}ms resultLen=${res.result?.length ?: 0}")
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

    suspend fun nip04Decrypt(
        ciphertext: String,
        senderPubkey: String,
        timeoutMs: Long = 60_000
    ): String {
        val pkg = externalPackage ?: throw IllegalStateException("External signer package not configured")
        val pub = externalPubKey ?: throw IllegalStateException("External signer pubkey not configured")

        val normalizedSenderPubkey = normalizePubkey(senderPubkey) ?: senderPubkey
        android.util.Log.d("ExternalSigner", "nip04Decrypt START: callId=<pending> currentUser=${pub.take(12)} senderPubkey=${normalizedSenderPubkey.take(12)} ciphertextLen=${ciphertext.length} timeoutMs=$timeoutMs")

        val resolverResult = maybeQueryResolver(
            authoritySuffix = "NIP04_DECRYPT",
            args = listOf(ciphertext, normalizedSenderPubkey, pub)
        )
        if (resolverResult?.rejected == true) {
            throw IllegalStateException("External signer rejected nip04_decrypt")
        }
        if (!resolverResult?.result.isNullOrBlank()) {
            android.util.Log.d("ExternalSigner", "nip04Decrypt SUCCESS(content-resolver): resultLen=${resolverResult!!.result!!.length}")
            return resolverResult.result
        }

        val l = launcher ?: throw IllegalStateException("No ActivityResult launcher registered")
        val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val deferred = CompletableDeferred<IntentResultLocal>()
        pending[callId] = deferred

        val start = System.currentTimeMillis()
        val uriIntent = Intent(Intent.ACTION_VIEW, nostrSignerUri(ciphertext)).apply {
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "nip04_decrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedSenderPubkey)
            putExtra("ciphertext", ciphertext)
            putExtra("payload", ciphertext)
            putExtra(Intent.EXTRA_TEXT, ciphertext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip04Decrypt LAUNCH(nip55-uri): callId=$callId ciphertextLen=${ciphertext.length} senderPubkey=${normalizedSenderPubkey.take(12)}")
            l(uriIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip04Decrypt nip55-uri launch failed: callId=$callId error=${e.message}")
        }

        val extrasGraceMs = minOf(2_500L, timeoutMs)
        val firstResult = try { withTimeout(extrasGraceMs) { deferred.await() } } catch (t: Throwable) {
            android.util.Log.d("ExternalSigner", "nip04Decrypt nip55-uri timeout after ${System.currentTimeMillis() - start}ms: callId=$callId")
            null
        }
        val acceptedFirstResult = firstResult?.requireAccepted("nip04_decrypt")
        if (acceptedFirstResult != null && !acceptedFirstResult.result.isNullOrBlank()) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip04Decrypt SUCCESS(nip55-uri): callId=$callId elapsed=${elapsed}ms resultLen=${acceptedFirstResult.result!!.length}")
            return acceptedFirstResult.result!!
        }
        if (acceptedFirstResult != null && acceptedFirstResult.result.isNullOrBlank()) {
            android.util.Log.w("ExternalSigner", "nip04Decrypt EMPTY_RESULT(nip55-uri): callId=$callId")
        }

        val rawUriIntent = Intent(Intent.ACTION_VIEW, rawNostrSignerUri(ciphertext)).apply {
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "nip04_decrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedSenderPubkey)
            putExtra("ciphertext", ciphertext)
            putExtra("payload", ciphertext)
            putExtra(Intent.EXTRA_TEXT, ciphertext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip04Decrypt LAUNCH(raw-uri-fallback): callId=$callId ciphertextLen=${ciphertext.length} senderPubkey=${normalizedSenderPubkey.take(12)}")
            l(rawUriIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip04Decrypt raw URI fallback launch failed: callId=$callId error=${e.message}")
        }

        val secondResult = try { withTimeout(2_500L) { deferred.await() } } catch (t: Throwable) {
            android.util.Log.d("ExternalSigner", "nip04Decrypt raw-uri timeout after ${System.currentTimeMillis() - start}ms: callId=$callId")
            null
        }
        val acceptedSecondResult = secondResult?.requireAccepted("nip04_decrypt")
        if (acceptedSecondResult != null && !acceptedSecondResult.result.isNullOrBlank()) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip04Decrypt SUCCESS(raw-uri): callId=$callId elapsed=${elapsed}ms resultLen=${acceptedSecondResult.result!!.length}")
            return acceptedSecondResult.result!!
        }
        if (acceptedSecondResult != null && acceptedSecondResult.result.isNullOrBlank()) {
            android.util.Log.w("ExternalSigner", "nip04Decrypt EMPTY_RESULT(raw-uri): callId=$callId")
        }

        val extrasOnlyIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("nostrsigner:")
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "nip04_decrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedSenderPubkey)
            putExtra("ciphertext", ciphertext)
            putExtra("payload", ciphertext)
            putExtra(Intent.EXTRA_TEXT, ciphertext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip04Decrypt LAUNCH(extras-only-fallback): callId=$callId ciphertextLen=${ciphertext.length} senderPubkey=${normalizedSenderPubkey.take(12)}")
            l(extrasOnlyIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip04Decrypt extras-only fallback launch failed: callId=$callId error=${e.message}")
        }

        try {
            val elapsed = System.currentTimeMillis() - start
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) {
                android.util.Log.e("ExternalSigner", "nip04Decrypt TIMEOUT: callId=$callId elapsed=${elapsed}ms totalTimeout=$timeoutMs")
                throw java.util.concurrent.TimeoutException("External signer timed out for nip04_decrypt")
            }
            val res = withTimeout(remaining) { deferred.await() }.requireAccepted("nip04_decrypt")
            val totalElapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip04Decrypt SUCCESS(fallback): callId=$callId elapsed=${totalElapsed}ms resultLen=${res.result?.length ?: 0}")
            if (res.result.isNullOrBlank()) {
                android.util.Log.e("ExternalSigner", "nip04Decrypt EMPTY_RESULT(uri): callId=$callId")
                throw IllegalStateException("External signer returned empty decrypt result")
            }
            return res.result!!
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.e("ExternalSigner", "nip04Decrypt FAILED: callId=$callId elapsed=${elapsed}ms error=${t.message} class=${t::class.simpleName}", t)
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

        // Normalize incoming pubkey in case it's in npub format
        val normalizedRecipientPubkey = normalizePubkey(recipientPubkey) ?: recipientPubkey
        
        android.util.Log.d("ExternalSigner", "nip44Encrypt START: callId=<pending> currentUser=${pub.take(12)} recipientPubkey=${normalizedRecipientPubkey.take(12)} plaintextLen=${plaintext.length} timeoutMs=$timeoutMs")

        val resolverResult = maybeQueryResolver(
            authoritySuffix = "NIP44_ENCRYPT",
            args = listOf(plaintext, normalizedRecipientPubkey, pub)
        )
        if (resolverResult?.rejected == true) {
            throw IllegalStateException("External signer rejected nip44_encrypt")
        }
        if (!resolverResult?.result.isNullOrBlank()) {
            android.util.Log.d("ExternalSigner", "nip44Encrypt SUCCESS(content-resolver): resultLen=${resolverResult!!.result!!.length}")
            return resolverResult.result
        }

        val l = launcher ?: throw IllegalStateException("No ActivityResult launcher registered")

        val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val deferred = CompletableDeferred<IntentResultLocal>()
        pending[callId] = deferred

        val start = System.currentTimeMillis()

        // NIP-55 puts the plaintext in the nostrsigner: URI and sends the peer
        // pubkey/current user as extras. Keep the plaintext extra only as a
        // compatibility hint for signers that also read extras.
        val uriIntent = Intent(Intent.ACTION_VIEW, nostrSignerUri(plaintext)).apply {
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "nip44_encrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedRecipientPubkey)
            putExtra("plaintext", plaintext)
            putExtra("payload", plaintext)
            putExtra(Intent.EXTRA_TEXT, plaintext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip44Encrypt LAUNCH(nip55-uri): callId=$callId plaintextLen=${plaintext.length} recipientPubkey=${normalizedRecipientPubkey.take(12)}")
            l(uriIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip44Encrypt nip55-uri launch failed: callId=$callId error=${e.message}")
        }

        // Wait briefly before falling back to raw URI/extras shapes.
        val extrasGraceMs = minOf(2_500L, timeoutMs)
        val firstResult = try { withTimeout(extrasGraceMs) { deferred.await() } } catch (t: Throwable) {
            android.util.Log.d("ExternalSigner", "nip44Encrypt nip55-uri timeout after ${System.currentTimeMillis() - start}ms: callId=$callId")
            null
        }
        val acceptedFirstResult = firstResult?.requireAccepted("nip44_encrypt")
        if (acceptedFirstResult != null && !acceptedFirstResult.result.isNullOrBlank()) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip44Encrypt SUCCESS(nip55-uri): callId=$callId elapsed=${elapsed}ms resultLen=${acceptedFirstResult.result!!.length}")
            return acceptedFirstResult.result!!
        }
        if (acceptedFirstResult != null && acceptedFirstResult.result.isNullOrBlank()) {
            android.util.Log.w("ExternalSigner", "nip44Encrypt EMPTY_RESULT(nip55-uri): callId=$callId")
        }

        val rawUriIntent = Intent(Intent.ACTION_VIEW, rawNostrSignerUri(plaintext)).apply {
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "nip44_encrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedRecipientPubkey)
            putExtra("plaintext", plaintext)
            putExtra("payload", plaintext)
            putExtra(Intent.EXTRA_TEXT, plaintext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip44Encrypt LAUNCH(raw-uri-fallback): callId=$callId plaintextLen=${plaintext.length} recipientPubkey=${normalizedRecipientPubkey.take(12)}")
            l(rawUriIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip44Encrypt raw URI fallback launch failed: callId=$callId error=${e.message}")
        }

        val secondResult = try { withTimeout(2_500L) { deferred.await() } } catch (t: Throwable) {
            android.util.Log.d("ExternalSigner", "nip44Encrypt raw-uri timeout after ${System.currentTimeMillis() - start}ms: callId=$callId")
            null
        }
        val acceptedSecondResult = secondResult?.requireAccepted("nip44_encrypt")
        if (acceptedSecondResult != null && !acceptedSecondResult.result.isNullOrBlank()) {
            val elapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip44Encrypt SUCCESS(raw-uri): callId=$callId elapsed=${elapsed}ms resultLen=${acceptedSecondResult.result!!.length}")
            return acceptedSecondResult.result!!
        }
        if (acceptedSecondResult != null && acceptedSecondResult.result.isNullOrBlank()) {
            android.util.Log.w("ExternalSigner", "nip44Encrypt EMPTY_RESULT(raw-uri): callId=$callId")
        }

        val extrasOnlyIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("nostrsigner:")
            `package` = pkg
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "nip44_encrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", normalizedRecipientPubkey)
            putExtra("plaintext", plaintext)
            putExtra("payload", plaintext)
            putExtra(Intent.EXTRA_TEXT, plaintext)
        }

        try {
            android.util.Log.d("ExternalSigner", "nip44Encrypt LAUNCH(extras-only-fallback): callId=$callId plaintextLen=${plaintext.length} recipientPubkey=${normalizedRecipientPubkey.take(12)}")
            l(extrasOnlyIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "nip44Encrypt extras-only fallback launch failed: callId=$callId error=${e.message}")
        }

        try {
            val elapsed = System.currentTimeMillis() - start
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) {
                android.util.Log.e("ExternalSigner", "nip44Encrypt TIMEOUT: callId=$callId elapsed=${elapsed}ms totalTimeout=$timeoutMs")
                throw java.util.concurrent.TimeoutException("External signer timed out for nip44_encrypt")
            }
            val res = withTimeout(remaining) { deferred.await() }.requireAccepted("nip44_encrypt")
            val totalElapsed = System.currentTimeMillis() - start
            android.util.Log.d("ExternalSigner", "nip44Encrypt SUCCESS(fallback): callId=$callId elapsed=${totalElapsed}ms resultLen=${res.result?.length ?: 0}")
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
