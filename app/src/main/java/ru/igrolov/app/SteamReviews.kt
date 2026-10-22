package ru.igrolov.app

import java.net.URLEncoder
import org.json.JSONObject

enum class SteamReviewTone { POSITIVE, NEGATIVE, NEUTRAL }

data class SteamReviewSummary(val positive: Long, val total: Long) {
    val percent: Int? get() = if (total > 0) ((positive * 100L) / total).toInt().coerceIn(0, 100) else null
    val tone: SteamReviewTone get() = when {
        (percent ?: 60) > 60 -> SteamReviewTone.POSITIVE
        (percent ?: 60) < 60 -> SteamReviewTone.NEGATIVE
        else -> SteamReviewTone.NEUTRAL
    }
}

object SteamReviews {
    fun requestUrl(appId: Int): String {
        require(appId > 0)
        val input = JSONObject().put("appid", appId).put("languages", org.json.JSONArray().put("all"))
            .put("filter", 1).put("review_type", 0).put("purchase_type", 0).put("num_per_page", 1)
        return "https://api.steampowered.com/IUserReviewsService/GetAppReviews/v1/?input_json=" +
            URLEncoder.encode(input.toString(), "UTF-8")
    }

    fun legacyRequestUrl(appId: Int): String =
        "https://store.steampowered.com/appreviews/$appId?json=1&filter=recent&language=all&review_type=all&purchase_type=steam&num_per_page=1"

    /** Score totals on the first page; never calculate a rating from the sampled reviews. */
    fun parse(json: String): SteamReviewSummary? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.has("success") && root.optInt("success") != 1) return null
        val body = root.optJSONObject("response") ?: root
        val summary = body.optJSONObject("query_summary") ?: return null
        val positive = summary.optLong("total_positive", -1)
        val total = summary.optLong("total_reviews", -1)
        if (positive < 0 || total < 0 || positive > total) return null
        return SteamReviewSummary(positive, total)
    }
}
