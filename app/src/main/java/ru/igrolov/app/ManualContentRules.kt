package ru.igrolov.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Lightweight manual reference: one main-game request, never hundreds of sequential DLC requests. */
object ManualGameReference {
    data class Game(val canonical: String, val appId: Int = 0, val dlcIds: Set<Int> = emptySet(), val aliases: List<String> = emptyList())
    fun catalogQuery(query: String, aliases: List<String>): String =
        aliases.firstOrNull { it.isNotBlank() }
            ?: query.replace(Regex("[™®©]"), "")
    fun lookup(query: String, selectedAppId: Int? = null, additionalAliases: List<String> = emptyList()): Game {
        return runCatching {
            val selected = selectedAppId?.takeIf {it>0}?.let {SteamGameReference.Suggestion(it,query)} ?: SteamGameReference.searchGames(query).firstOrNull {
                OfferRules.normalize(it.name) == OfferRules.normalize(query)
            } ?: return Game(query)
            val conn = URL("https://store.steampowered.com/api/appdetails?appids=${selected.id}&l=english&cc=us").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val root = try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) } finally { conn.disconnect() }
            val entry = root.optJSONObject(selected.id.toString())
            val data = entry?.takeIf { it.optBoolean("success") }?.optJSONObject("data")
            if (data?.optString("type") != "game") return Game(query)
            val dlc = data.optJSONArray("dlc")
            Game(data.optString("name", query), selected.id,
                if (dlc == null) emptySet() else (0 until dlc.length()).map { dlc.optInt(it) }.filter { it > 0 }.toSet(), (additionalAliases+GameSearchResolver.aliases(selected.id,data.optString("name",query))).distinct())
        }.getOrElse { Game(query,selectedAppId ?: 0,aliases=additionalAliases) }
    }
}

object ManualContentRules {
    private fun words(text: String) = OfferRules.normalize(text)
        .replace(Regex("(?<=\\p{L})'(?=\\p{L})"), "")
        .replace(Regex("<[^>]*>"), " ").split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.isNotBlank() }
    private fun phrase(value: String, term: String): Boolean {
        val wanted=words(term)
        return wanted.isNotEmpty() && words(value).windowed(wanted.size).any { it == wanted }
    }
    private val deliveryWords = setOf("steam", "key", "keys", "ключ", "ключи", "код", "code", "cd", "cdkey", "gift", "gifts", "гифт", "гифты",
        "рф", "ru", "rus", "снг", "cis", "global", "worldwide", "world", "russia", "россия", "россии", "kz", "кз", "казахстан", "ua", "украина", "tr", "турция", "turkey",
        "регион", "регионов", "регионов", "region", "free", "активация", "активации", "activation", "авто", "автодоставка", "autodelivery", "auto", "delivery",
        "подарок", "подарки", "bonus", "бонус", "бонусы", "акция", "карты", "картой", "карта", "карт", "cards", "card", "моментально", "мгновенно", "сразу", "лицензия", "лицензия", "лицензионный", "доставка",
        "пк", "pc", "для", "всех", "все", "регионы", "мир", "мира", "руб", "rub", "стандарт", "стандартное", "standard", "edition", "издание", "версия",
        "deluxe", "делюкс", "ultimate", "complete", "definitive", "galaxy", "галактическое", "gold", "premium", "goty", "digital", "collector", "collectors")
    fun classify(title: String, info: String, game: ManualGameReference.Game): String {
        // These describe add-on ownership, rather than a passing mention of extra content.
        if (listOf("для запуска требуется базовая игра", "требуется базовая версия игры", "требуется оригинальная игра",
                "requires the base game", "requires base game", "дополнение не содержит основную игру").any { phrase(info,it) }) return "DLC"
        if (OfferRules.editionType(title,game.canonical)=="DLC" || listOf("species pack", "story pack", "season pass", "expansion", "upgrade", "season").any { phrase(title,it) }) return "DLC"
        val links=Regex("(?i)store\\.steampowered\\.com/app/([0-9]+)").findAll(info).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        if (links.isNotEmpty() && game.appId !in links && links.all { it in game.dlcIds }) return "DLC"
        val titleWords=words(title)
        val identities=(listOf(game.canonical)+game.aliases).map(::words).filter {it.isNotEmpty()}.sortedByDescending {it.size}
        val matched=identities.mapNotNull {identity ->
            val start=titleWords.windowed(identity.size).indexOfFirst {it==identity}
            if(start<0)null else start to identity.size
        }.firstOrNull() ?: return "Не подтверждено"
        val suffix=titleWords.drop(matched.first+matched.second)
        val identity=titleWords.subList(matched.first,matched.first+matched.second).joinToString(" ")
        val identityNumbers=Regex("[0-9]+").findAll(GameSearchResolver.normalized(identity)).map {it.value}.toSet()
        // A numbered sequel is a different game, even if the marketplace matches the prefix.
        if(suffix.firstOrNull()?.all(Char::isDigit)==true && suffix.first() !in identityNumbers && suffix.first() !in setOf("0","24")) return "Не подтверждено"
        // Positive identification: an unrecognized named suffix is NOT assumed to be the base game.
        // This rejects "Stellaris: Utopia/MegaCorp/Overlord" even while Steam is unavailable.
        if (suffix.all { it in deliveryWords || it.all(Char::isDigit) }) return "Игра"
        return "Не подтверждено"
    }
    fun visible(offer: Offer, edition: String, type: String, rules: JSONObject = JSONObject()): Boolean {
        if (!offer.detailsChecked || offer.available != true || !offer.priceVerified || offer.variablePrice) return false
        if (offer.rfStatus=="РФ исключена" || (type!="Все" && offer.type!=type)) return false
        if (GameRuleStore.words(rules,"blocked").any { phrase(offer.name,it) }) return false
        return edition=="Все" || offer.edition==edition
    }
}
