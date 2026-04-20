package com.hisa.util

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Utility for formatting JSON strings for human readability.
 * Handles pretty-printing with indentation and error recovery.
 */
object JsonFormatter {
    /**
     * Pretty-print a JSON string with indentation.
     * Handles both objects and arrays, with graceful fallback.
     *
     * @param jsonString The raw JSON string to format
     * @param indentSpaces Number of spaces for indentation (default: 2)
     * @return Formatted JSON string, or original string if parsing fails
     */
    fun prettyPrint(jsonString: String?, indentSpaces: Int = 2): String {
        if (jsonString.isNullOrBlank()) return ""
        
        return try {
            val obj = JSONTokener(jsonString).nextValue()
            when (obj) {
                is JSONObject -> obj.toString(indentSpaces)
                is JSONArray -> obj.toString(indentSpaces)
                else -> jsonString
            }
        } catch (e: JSONException) {
            // If it's already formatted or malformed, return as-is
            jsonString
        }
    }

    /**
     * Compact a JSON string (remove all whitespace except within strings).
     * Useful for logging or storage.
     *
     * @param jsonString The JSON string to compact
     * @return Compacted JSON string
     */
    fun compact(jsonString: String?): String {
        if (jsonString.isNullOrBlank()) return ""
        
        return try {
            val obj = JSONTokener(jsonString).nextValue()
            when (obj) {
                is JSONObject -> obj.toString()
                is JSONArray -> obj.toString()
                else -> jsonString
            }
        } catch (e: JSONException) {
            jsonString
        }
    }

    /**
     * Extract a readable summary from a JSON object.
     * Shows key fields only, useful for previews.
     *
     * @param jsonString The JSON string to summarize
     * @param maxLength Maximum preview length (default: 100)
     * @return Summary string
     */
    fun summarize(jsonString: String?, maxLength: Int = 100): String {
        if (jsonString.isNullOrBlank()) return "No data"
        
        return try {
            val obj = JSONTokener(jsonString).nextValue()
            if (obj is JSONObject) {
                val id = obj.optString("id", "")?.take(12) ?: ""
                val kind = obj.optInt("kind", -1)
                val pubkey = obj.optString("pubkey", "")?.take(8) ?: ""
                val content = obj.optString("content", "")?.take(50)?.replace("\n", " ") ?: ""
                
                buildString {
                    append("Event(")
                    if (id.isNotBlank()) append("id=$id, ")
                    if (kind >= 0) append("kind=$kind, ")
                    if (pubkey.isNotBlank()) append("by=$pubkey, ")
                    if (content.isNotBlank()) append("content=\"$content\"")
                    append(")")
                }.take(maxLength)
            } else {
                jsonString.take(maxLength)
            }
        } catch (e: Exception) {
            jsonString.take(maxLength)
        }
    }
}
