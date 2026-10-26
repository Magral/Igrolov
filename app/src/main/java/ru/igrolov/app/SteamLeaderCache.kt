package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject

/** Short-lived cache for Steam data used by the Leaders tabs. */
object SteamLeaderCache {
    const val TTL_MILLIS: Long = 5 * 60 * 1000L

    fun encode(game: SteamTopSeller, checkedAt: Long): String = JSONObject()
        .put("checkedAt", checkedAt)
        .put("appId", game.appId)
        .put("title", game.title)
        .put("steamPrice", game.steamPrice)
        .put("steamInitialPrice", game.steamInitialPrice)
        .put("discountPercent", game.discountPercent)
        .put("availability", game.availability.name)
        .put("isGame", game.isGame)
        .put("isFree", game.isFree)
        .put("headerImageUrl", game.headerImageUrl)
        .put("description", game.description)
        .put("russianLocalization", game.features.russianLocalization.name)
        .put("controllerSupport", game.features.controllerSupport.name)
        .put("genres", JSONArray(game.genres))
        .toString()

    fun decode(raw: String, now: Long, ttlMillis: Long = TTL_MILLIS): SteamTopSeller? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val checkedAt = json.optLong("checkedAt", 0L)
        val age = now - checkedAt
        if (checkedAt <= 0L || age !in 0..ttlMillis) return null
        val appId = json.optInt("appId", 0).takeIf { it > 0 } ?: return null
        val availability = runCatching {
            SteamRuAvailability.valueOf(json.optString("availability"))
        }.getOrNull() ?: return null
        if (availability == SteamRuAvailability.UNKNOWN) return null
        val localization = runCatching {
            SteamRussianLocalization.valueOf(json.optString("russianLocalization"))
        }.getOrDefault(SteamRussianLocalization.UNKNOWN)
        val controller = runCatching {
            SteamControllerSupport.valueOf(json.optString("controllerSupport"))
        }.getOrDefault(SteamControllerSupport.UNKNOWN)
        val genresJson = json.optJSONArray("genres")
        val genres = if (genresJson == null) emptyList() else
            (0 until genresJson.length()).mapNotNull { genresJson.optString(it).takeIf(String::isNotBlank) }
        return SteamTopSeller(
            appId = appId,
            title = json.optString("title"),
            steamPrice = json.optString("steamPrice"),
            steamInitialPrice = json.optString("steamInitialPrice"),
            discountPercent = json.optInt("discountPercent", 0).coerceIn(0, 100),
            availability = availability,
            isGame = json.optBoolean("isGame", true),
            isFree = json.optBoolean("isFree", false),
            headerImageUrl = json.optString("headerImageUrl"),
            description = json.optString("description"),
            features = SteamGameFeatures(localization, controller),
            genres = genres
        )
    }
}
