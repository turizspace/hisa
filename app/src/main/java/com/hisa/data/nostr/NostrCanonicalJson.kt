package com.hisa.data.nostr

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object NostrCanonicalJson {
    fun serializeEventForId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        return buildString {
            append("[0,")
            appendJsonString(pubkey)
            append(',')
            append(createdAt)
            append(',')
            append(kind)
            append(',')
            appendTags(tags)
            append(',')
            appendJsonString(content)
            append(']')
        }
    }

    fun computeEventHash(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): ByteArray {
        val serialized = serializeEventForId(pubkey, createdAt, kind, tags, content)
        return MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
    }

    fun computeEventId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        return computeEventHash(pubkey, createdAt, kind, tags, content)
            .joinToString("") { "%02x".format(it) }
    }

    fun computeEventId(event: JSONObject): String {
        return computeEventId(
            pubkey = event.optString("pubkey", ""),
            createdAt = event.optLong("created_at", 0L),
            kind = event.optInt("kind", 0),
            tags = tagsFromJsonArray(event.optJSONArray("tags") ?: JSONArray()),
            content = event.optString("content", "")
        )
    }

    fun tagsFromJsonArray(tags: JSONArray): List<List<String>> {
        return buildList {
            for (i in 0 until tags.length()) {
                val tag = tags.getJSONArray(i)
                add(buildList {
                    for (j in 0 until tag.length()) {
                        add(tag.getString(j))
                    }
                })
            }
        }
    }

    private fun StringBuilder.appendTags(tags: List<List<String>>) {
        append('[')
        tags.forEachIndexed { tagIndex, tag ->
            if (tagIndex > 0) append(',')
            append('[')
            tag.forEachIndexed { valueIndex, value ->
                if (valueIndex > 0) append(',')
                appendJsonString(value)
            }
            append(']')
        }
        append(']')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    when {
                        ch < ' ' -> appendUnicodeEscape(ch)
                        ch.isHighSurrogate() -> {
                            if (i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                                append(ch)
                                i += 1
                                append(value[i])
                            } else {
                                appendUnicodeEscape(ch)
                            }
                        }
                        ch.isLowSurrogate() -> appendUnicodeEscape(ch)
                        else -> append(ch)
                    }
                }
            }
            i += 1
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(ch: Char) {
        append("\\u")
        val code = ch.code
        append(HEX[(code ushr 12) and 0xF])
        append(HEX[(code ushr 8) and 0xF])
        append(HEX[(code ushr 4) and 0xF])
        append(HEX[code and 0xF])
    }

    private val HEX = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )
}
