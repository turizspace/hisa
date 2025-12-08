package com.hisa.data.nostr

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

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
                        if (!id.isNullOrBlank() && !event.isNullOrBlank()) {
                            val res = IntentResultLocal(id = id, result = result, event = event, packageName = pkg)
                            pending.remove(id)?.complete(res)
                        } else {
                            android.util.Log.w("ExternalSigner", "Ignoring array entry with empty id/event: id=$id eventExists=${!event.isNullOrBlank()}")
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

            if (!id.isNullOrBlank() && !event.isNullOrBlank()) {
                val res = IntentResultLocal(id = id, result = result, event = event, packageName = pkg)
                pending.remove(id)?.complete(res)
            } else {
                android.util.Log.w("ExternalSigner", "Ignored empty response from signer: id=$id eventPresent=${!event.isNullOrBlank()}")
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
}
