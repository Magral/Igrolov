package ru.igrolov.app

/** Keeps portrait Steam cover fallbacks separate from landscape header artwork. */
object SteamArtworkPresentation {
    fun isPortrait(url: String): Boolean = url.contains("/library_600x900", ignoreCase = true)

    fun cacheKey(appId: Int, portrait: Boolean): String =
        "${if (portrait) "portrait" else "landscape"}_$appId"
}
