package ru.igrolov.app

import org.json.JSONObject

enum class SteamRussianLocalization { SUPPORTED, NOT_SUPPORTED, UNKNOWN }
enum class SteamControllerSupport { FULL, PARTIAL, NONE, UNKNOWN }

data class SteamGameFeatures(
    val russianLocalization: SteamRussianLocalization = SteamRussianLocalization.UNKNOWN,
    val controllerSupport: SteamControllerSupport = SteamControllerSupport.UNKNOWN
) {
    val needsRussianLocalizationFallback: Boolean
        get() = russianLocalization == SteamRussianLocalization.UNKNOWN

    fun withFallback(fallback: SteamGameFeatures) = SteamGameFeatures(
        if (russianLocalization == SteamRussianLocalization.UNKNOWN) fallback.russianLocalization else russianLocalization,
        if (controllerSupport == SteamControllerSupport.UNKNOWN) fallback.controllerSupport else controllerSupport
    )

    companion object {
        fun fromDetails(data: JSONObject): SteamGameFeatures {
            val languages = data.optString("supported_languages", "")
            val localization = if (languages.isBlank()) SteamRussianLocalization.UNKNOWN else {
                // Steam localizes language names according to `l=`; the RU request may
                // return "Русский" instead of "Russian". Strip markup and entities
                // before matching so both response forms are recognized.
                val plainLanguages = decodeEntities(languages.replace(Regex("<[^>]*>"), " "))
                val russianListed = plainLanguages.split(Regex("[,;\\n\\r•]+"))
                    .any { segment ->
                        Regex("^[^\\p{L}]*(?:russian|русск(?:ий|ая|ое|ие|ому|им|их)?)(?:$|[^\\p{L}])", RegexOption.IGNORE_CASE)
                            .containsMatchIn(segment.trim())
                    }
                if (russianListed) SteamRussianLocalization.SUPPORTED else SteamRussianLocalization.NOT_SUPPORTED
            }
            val controller = when (data.optString("controller_support", "").trim().lowercase()) {
                "full" -> SteamControllerSupport.FULL
                "partial" -> SteamControllerSupport.PARTIAL
                "none", "no", "unsupported" -> SteamControllerSupport.NONE
                "" -> SteamControllerSupport.UNKNOWN
                else -> SteamControllerSupport.UNKNOWN
            }
            return SteamGameFeatures(localization, controller)
        }

        private fun decodeEntities(value: String): String = value
            .replace(Regex("&nbsp;?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
            .replace(Regex("&#(\\d+);")) { match ->
                match.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: match.value
            }
            .replace(Regex("&#x([0-9a-fA-F]+);", RegexOption.IGNORE_CASE)) { match ->
                match.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: match.value
            }
    }
}
