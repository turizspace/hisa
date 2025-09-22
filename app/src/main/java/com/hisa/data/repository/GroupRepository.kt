package com.hisa.data.repository

import com.hisa.data.model.Group
import org.json.JSONObject

object GroupRepository {
    fun parseGroup(eventJson: String): Group? {
        val obj = JSONObject(eventJson)
        if (obj.getInt("kind") != 40) return null
        val tags = obj.getJSONArray("tags")
        var title = ""
        var description = ""
        var tag = ""
        for (i in 0 until tags.length()) {
            val t = tags.getJSONArray(i)
            when (t.getString(0)) {
                "title" -> title = t.getString(1)
                "description" -> description = t.getString(1)
                "t" -> tag = t.getString(1)
            }
        }
    return Group(id = obj.optString("id", ""), title = title, description = description, tag = tag)
    }
}
