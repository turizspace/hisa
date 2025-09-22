package com.hisa.data.nostr

import android.content.ContentResolver
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight Intent bridge to external signer apps (Amber) using the nostrsigner ACTION_VIEW contract.
 *
 * - `registerForegroundLauncher` must be called with a function that can launch an Intent (ActivityResult launcher).
 * - `ensureConfigured` stores the external signer's package and pubkey used when building requests.
 * - `signEvent` assembles a sign intent and launches it, awaiting the result.
 */
object ExternalSignerManager {
    private var externalPubKey: String? = null
    private var externalPackage: String? = null
    private var launcher: ((Intent) -> Unit)? = null

    // Pending requests keyed by event id
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
            // Some signers may return a batch of results in a "results" JSON array extra.
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
                        val res = IntentResultLocal(id = id, result = result, event = event, packageName = pkg)
                        if (!id.isNullOrBlank()) {
                            pending.remove(id)?.complete(res)
                        }
                    }
                } catch (e: Exception) {
                    // fall through to single-result parse
                }
            }
            val id = intent.getStringExtra("id")
            val result = intent.getStringExtra("result")
            val event = intent.getStringExtra("event")
            val pkg = intent.getStringExtra("package")
            val res = IntentResultLocal(id = id, result = result, event = event, packageName = pkg)
            if (!id.isNullOrBlank()) {
                pending.remove(id)?.complete(res)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    suspend fun ensureConfigured(pubkeyHex: String, packageName: String, contentResolver: ContentResolver?) {
        externalPubKey = pubkeyHex
        externalPackage = packageName
        // contentResolver not needed for Intent-only bridge
    }

    // Expose configured values for other parts of the app to use as a fallback
    fun getConfiguredPubkey(): String? = externalPubKey
    fun getConfiguredPackage(): String? = externalPackage

    suspend fun signEvent(eventJson: String, eventId: String, timeoutMs: Long = 60_000): IntentResultLocal {
        val pkg = externalPackage ?: throw IllegalStateException("External signer package not configured")
        val pub = externalPubKey ?: throw IllegalStateException("External signer pubkey not configured")
        val l = launcher ?: throw IllegalStateException("No ActivityResult launcher registered to start external signer")

        val callId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val deferred = CompletableDeferred<IntentResultLocal>()
        pending[callId] = deferred

    // Build ACTION_VIEW nostrsigner:<payload> intent. Some signers parse the URI payload; others read the "event" extra.
    // Percent-encode the event JSON in the URI to avoid parsing issues.
    val uri = android.net.Uri.parse("nostrsigner:" + android.net.Uri.encode(eventJson))
    val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.`package` = pkg
        intent.putExtra("type", "sign_event")
        intent.putExtra("current_user", pub)

    // Include our generated request id so the signer can echo it back.
    // Also include the eventId so callers that want to correlate can do so.
    intent.putExtra("id", callId)
    intent.putExtra("event_id", eventId)
    // Also include raw event JSON as an extra so signers that don't read the URI can still get it
    intent.putExtra("event", eventJson)
    // Some signers accept a 'request' or 'request_id' extra; include both for compatibility
    intent.putExtra("request", callId)
    intent.putExtra("request_id", callId)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // Debug log: show key fields we send so we can diagnose signer behavior
        try {
            timber.log.Timber.d("Launching external signer pkg=%s callId=%s eventId=%s uriLen=%d eventLen=%d", pkg, callId, eventId, uri.toString().length, eventJson.length)
        } catch (e: Exception) {
            // ignore logging failures
        }
        l(intent)

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(callId)
        }
    }
}

