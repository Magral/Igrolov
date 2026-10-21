package ru.igrolov.app

/** Finds a conservative marketplace query and possible seller-side names. Suggestions always require user confirmation. */
object CatalogAliasResolver {
    private val deliveryMarker = Regex("(?i)\\b(steam|ключи?|key|gift|гифт|подарок|cd[ -]?key)\\b")
    private val unsafeTitle = Regex("(?i)\\b(аккаунт\\w*|акки|account\\w*|аренд\\w*|rent\\w*|offline|оффлайн|профил\\w*|dlc|дополнени\\w*|soundtrack|саундтрек|ост|season pass|сезонный пропуск|upgrade|улучшени\\w*)\\b")

    fun fallbackQuery(steamTitle: String): String? {
        val title = steamTitle.trim().replace(Regex("[™®©]"), "")
        val colon = title.indexOf(':')
        val core = if (colon > 0) title.substring(0, colon).trim() else {
            val words = title.split(Regex("\\s+")).filter(String::isNotBlank)
            if (words.size < 3) return null
            words.take(3).joinToString(" ")
        }
        val words = GameSearchResolver.normalized(core).split(' ').filter { it.length > 1 }
        return core.takeIf { words.size >= 2 && it.length >= 5 }
    }

    fun suggestions(core: String, listingTitles: Iterable<String>): List<String> {
        val normalizedCore = GameSearchResolver.normalized(core)
        if (normalizedCore.isBlank()) return emptyList()
        return listingTitles.mapNotNull { title ->
            val marker = deliveryMarker.find(title) ?: return@mapNotNull null
            val alias = title.substring(0, marker.range.first).trim().trim('-', '–', ':', '|', ' ') 
            val normalizedAlias = GameSearchResolver.normalized(alias)
            if (alias.length !in 4..120 || unsafeTitle.containsMatchIn(alias) || normalizedCore !in normalizedAlias) return@mapNotNull null
            if (ManualOfferClassifier.titleType(title, core) == null) return@mapNotNull null
            alias
        }.distinctBy { GameSearchResolver.normalized(it) }.take(6)
    }
}
