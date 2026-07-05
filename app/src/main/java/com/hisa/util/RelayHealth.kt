package com.hisa.util

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

object RelayHealth {
    private const val DEFAULT_SOCKET_TIMEOUT_MS = 1200

    fun normalizeRelayUrls(relays: List<String>): List<String> {
        return relays.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map(::normalizeRelayUrl)
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    fun normalizeRelayUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""

        val withScheme = when {
            trimmed.startsWith("ws://", ignoreCase = true) || trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) ->
                trimmed.replaceFirst("http://", "wss://", ignoreCase = true)
                    .replaceFirst("https://", "wss://", ignoreCase = true)
            else -> "wss://$trimmed"
        }

        return try {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase().orEmpty()
            val normalizedScheme = if (scheme == "ws") "wss" else scheme
            val host = uri.host?.trim().orEmpty()
            if (scheme !in setOf("ws", "wss") || host.isBlank()) {
                ""
            } else {
                val port = when {
                    uri.port != -1 -> uri.port
                    scheme == "wss" -> 443
                    else -> 80
                }
                val portSuffix = if (port == 80 || port == 443) "" else ":$port"
                val path = uri.rawPath?.takeIf { !it.isNullOrBlank() && it != "/" } ?: ""
                val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
                "$normalizedScheme://$host$portSuffix$path$query"
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun selectRelayUrls(
        candidates: List<String>,
        fallback: List<String> = emptyList(),
        probeReachability: Boolean = false,
        timeoutMs: Int = DEFAULT_SOCKET_TIMEOUT_MS
    ): List<String> {
        val normalizedCandidates = normalizeRelayUrls(candidates)
        if (normalizedCandidates.isEmpty()) {
            return normalizeRelayUrls(fallback)
        }

        if (!probeReachability) {
            return normalizedCandidates
        }

        val reachable = normalizedCandidates.filter { probeRelayReachability(it, timeoutMs) }
        return reachable.ifEmpty {
            normalizeRelayUrls(fallback).ifEmpty { normalizedCandidates }
        }
    }

    fun probeRelayReachability(url: String, timeoutMs: Int = DEFAULT_SOCKET_TIMEOUT_MS): Boolean {
        val normalized = normalizeRelayUrl(url)
        if (normalized.isBlank()) return false

        return try {
            val uri = URI(normalized)
            val host = uri.host ?: return false
            val port = uri.port.takeIf { it != -1 } ?: if (uri.scheme.equals("wss", ignoreCase = true)) 443 else 80
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
