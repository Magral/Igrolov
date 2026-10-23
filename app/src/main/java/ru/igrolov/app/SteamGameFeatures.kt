package ru.igrolov.app

import org.json.JSONObject

enum class SteamRussianLocalization { SUPPORTED, NOT_SUPPORTED, UNKNOWN }
enum class SteamControllerSupport { FULL, PARTIAL, NONE, UNKNOWN }

data class SteamGameFeatures(
    val russianLocalization: SteamRussianLocalization = SteamRussianLocalization.UNKNOWN,
    val controllerSupport: SteamControllerSupport = SteamControllerSupport.UNKNOWN
) {
    fun withFallback(fallback: SteamGameFeatures) = SteamGameFeatures(
        if (russianLocalization == SteamRussianLocalization.UNKNOWN) fallback.russianLocalization else russianLocalization,
        if (controllerSupport == SteamControllerSupport.UNKNOWN) fallback.controllerSupport else controllerSupport
    )

    companion object {
        fun fromDetails(data: JSONObject): SteamGameFeatures {
            val languages = data.optString("supported_languages", "")
            val localization = if (languages.isBlank()) SteamRussianLocalization.UNKNOWN else {
                val russianListed = languages.replace(Regex("<[^>]*>"), " ")
                    .split(',', ';')
                    .any { it.trim().startsWith("Russian", ignoreCase = true) }
                if (russianListed) SteamRussianLocalization.SUPPORTED else SteamRussianLocalization.NOT_SUPPORTED
            }
            val controller = when (data.optString("controller_support", "").trim().lowercase()) {
                "full" -> SteamControllerSupport.FULL
                "partial" -> SteamControllerSupport.PARTIAL
                "" -> if (data.optString("name").isNotBlank()) SteamControllerSupport.NONE
                    else SteamControllerSupport.UNKNOWN
                else -> SteamControllerSupport.UNKNOWN
            }
            return SteamGameFeatures(localization, controller)
        }
    }
}
