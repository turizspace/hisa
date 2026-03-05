package com.hisa.data.nostr

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
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
 */
object ExternalSignerManager {
    private val responseLogCounter = AtomicLong(0)
    private var externalPubKey: String? = null
    private var externalPackage: String? = null
    private var launcher: ((Intent) -> Unit)? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<IntentResultLocal>>()

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
            val result = intent.getStringExtra("result")
            // Some signers use "event", some "event_json" or EXTRA_TEXT
            val event = intent.getStringExtra("event") ?: intent.getStringExtra("event_json") ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            val pkg = intent.getStringExtra("package")

            if (!id.isNullOrBlank() && (!event.isNullOrBlank() || !result.isNullOrBlank())) {
                val count = responseLogCounter.incrementAndGet()
                if (count % 100L == 0L) {
                    android.util.Log.d("ExternalSigner", "Signer returned (sampled): id=$id pkg=$pkg eventPresent=${!event.isNullOrBlank()} resultPresent=${!result.isNullOrBlank()}")
                }
                val res = IntentResultLocal(id = id, result = result, event = event, packageName = pkg)
                pending.remove(id)?.complete(res)
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
        externalPubKey = pubkeyHex
        externalPackage = packageName
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

        // Helper: convert npub -> hex if caller sent npub in pubkey
        fun npubToHex(npub: String): String? {
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
        android.util.Log.d("ExternalSigner", "Envelope JSON: $envelopeJson")
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

        val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val deferred = CompletableDeferred<IntentResultLocal>()
        pending[callId] = deferred

        // Build a minimal envelope similar to signEvent to pass in extras
        val envelopeJson = JSONObject().apply {
            put("type", "nip44_decrypt")
            put("id", callId)
            put("request_id", callId)
            put("current_user", pub)
            put("ciphertext", ciphertext)
            put("pubkey", senderPubkey)
        }.toString()

        // Extras-only intent: avoid embedding ciphertext in the URI
        val extrasOnlyIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("nostrsigner:")
            `package` = pkg
            putExtra("type", "nip44_decrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", senderPubkey)
            putExtra("ciphertext", ciphertext)
            putExtra("nostr_request", envelopeJson)
            putExtra(Intent.EXTRA_TEXT, envelopeJson)
        }

        // Log launch info (sampled) to aid debugging of signer interactions without spamming logs
        try {
            val cnt = responseLogCounter.incrementAndGet()
            if (cnt <= 5L || cnt % 50L == 0L) {
                android.util.Log.d("ExternalSigner", "nip44Decrypt launch: pkg=$pkg callId=$callId senderPubkey=${senderPubkey.take(12)} ciphertextLen=${ciphertext.length}")
                android.util.Log.d("ExternalSigner", "nip44Decrypt envelope: ${envelopeJson.take(400)}")
            }
            l(extrasOnlyIntent)
        } catch (e: Exception) {
            android.util.Log.w("ExternalSigner", "Extras-only launch failed: ${e.message}")
        }

        // Grace period waiting for extras-only response
        val extrasGraceMs = minOf(10_000L, timeoutMs)
        val start = System.currentTimeMillis()
        val firstResult = try { withTimeout(extrasGraceMs) { deferred.await() } } catch (_: Throwable) { null }
        if (firstResult != null) {
            pending.remove(callId)
            val cnt = responseLogCounter.incrementAndGet()
            if (cnt <= 5L || cnt % 50L == 0L) android.util.Log.d("ExternalSigner", "nip44Decrypt received firstResult: id=$callId pkg=$pkg presentResult=${!firstResult.result.isNullOrBlank()} presentEvent=${!firstResult.event.isNullOrBlank()}")
            return when {
                !firstResult.result.isNullOrBlank() -> firstResult.result
                !firstResult.event.isNullOrBlank() -> firstResult.event
                else -> throw IllegalStateException("External signer returned no decrypt result")
            }
        }

        // ACTION_SEND fallback (application/json body)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            `package` = pkg
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, envelopeJson)
        }
        try { l(sendIntent) } catch (_: Exception) {}
        val secondResult = try { withTimeout(5_000L) { deferred.await() } } catch (_: Throwable) { null }
        if (secondResult != null) {
            pending.remove(callId)
            val cnt = responseLogCounter.incrementAndGet()
            if (cnt <= 5L || cnt % 50L == 0L) android.util.Log.d("ExternalSigner", "nip44Decrypt received secondResult: id=$callId pkg=$pkg presentResult=${!secondResult.result.isNullOrBlank()} presentEvent=${!secondResult.event.isNullOrBlank()}")
            return when {
                !secondResult.result.isNullOrBlank() -> secondResult.result
                !secondResult.event.isNullOrBlank() -> secondResult.event
                else -> throw IllegalStateException("External signer returned no decrypt result")
            }
        }

        // URI fallback: scheme-only with extras
        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            `package` = pkg
            putExtra("type", "nip44_decrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", senderPubkey)
            putExtra("ciphertext", ciphertext)
            putExtra("nostr_request", envelopeJson)
            putExtra(Intent.EXTRA_TEXT, envelopeJson)
        }
        try { l(uriIntent) } catch (_: Exception) {}

        // Wait remaining time for response
        try {
            val elapsed = System.currentTimeMillis() - start
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) throw java.util.concurrent.TimeoutException("External signer timed out")
            val res = withTimeout(remaining) { deferred.await() }
            val cnt = responseLogCounter.incrementAndGet()
            if (cnt <= 5L || cnt % 50L == 0L) android.util.Log.d("ExternalSigner", "nip44Decrypt received finalResult: id=$callId pkg=$pkg presentResult=${!res.result.isNullOrBlank()} presentEvent=${!res.event.isNullOrBlank()}")
            return when {
                !res.result.isNullOrBlank() -> res.result
                !res.event.isNullOrBlank() -> res.event
                else -> throw IllegalStateException("External signer returned no decrypt result")
            }
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

        val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val deferred = CompletableDeferred<IntentResultLocal>()
        pending[callId] = deferred

        val envelopeJson = JSONObject().apply {
            put("type", "nip44_encrypt")
            put("id", callId)
            put("request_id", callId)
            put("current_user", pub)
            put("plaintext", plaintext)
            put("pubkey", recipientPubkey)
        }.toString()

        val extrasOnlyIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("nostrsigner:")
            `package` = pkg
            putExtra("type", "nip44_encrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", recipientPubkey)
            putExtra("plaintext", plaintext)
            putExtra("nostr_request", envelopeJson)
            putExtra(Intent.EXTRA_TEXT, envelopeJson)
        }

        try { l(extrasOnlyIntent) } catch (_: Exception) {}

        val extrasGraceMs = minOf(10_000L, timeoutMs)
        val start = System.currentTimeMillis()
        val firstResult = try { withTimeout(extrasGraceMs) { deferred.await() } } catch (_: Throwable) { null }
        if (firstResult != null) {
            pending.remove(callId)
            return when {
                !firstResult.result.isNullOrBlank() -> firstResult.result
                !firstResult.event.isNullOrBlank() -> firstResult.event
                else -> throw IllegalStateException("External signer returned no encrypt result")
            }
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            `package` = pkg
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, envelopeJson)
        }
        try { l(sendIntent) } catch (_: Exception) {}
        val secondResult = try { withTimeout(5_000L) { deferred.await() } } catch (_: Throwable) { null }
        if (secondResult != null) {
            pending.remove(callId)
            return when {
                !secondResult.result.isNullOrBlank() -> secondResult.result
                !secondResult.event.isNullOrBlank() -> secondResult.event
                else -> throw IllegalStateException("External signer returned no encrypt result")
            }
        }

        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            `package` = pkg
            putExtra("type", "nip44_encrypt")
            putExtra("id", callId)
            putExtra("current_user", pub)
            putExtra("pubkey", recipientPubkey)
            putExtra("plaintext", plaintext)
            putExtra("nostr_request", envelopeJson)
            putExtra(Intent.EXTRA_TEXT, envelopeJson)
        }
        try { l(uriIntent) } catch (_: Exception) {}

        try {
            val elapsed = System.currentTimeMillis() - start
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) throw java.util.concurrent.TimeoutException("External signer timed out")
            val res = withTimeout(remaining) { deferred.await() }
            return when {
                !res.result.isNullOrBlank() -> res.result
                !res.event.isNullOrBlank() -> res.event
                else -> throw IllegalStateException("External signer returned no encrypt result")
            }
        } finally {
            pending.remove(callId)
        }
    }
}
