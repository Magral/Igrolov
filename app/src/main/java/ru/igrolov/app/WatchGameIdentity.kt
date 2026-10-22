package ru.igrolov.app

import org.json.JSONObject

object WatchGameIdentity {
    fun matches(entry: JSONObject, appId: Int, name: String): Boolean {
        val storedId = entry.optInt("steamAppId", 0)
        return if (appId > 0 && storedId > 0) storedId == appId
        else entry.optString("name").trim().equals(name.trim(), ignoreCase = true)
    }
}
