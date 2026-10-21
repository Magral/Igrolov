package ru.igrolov.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Separate preferences: never writes CatalogPageSettings or monitoring watches. */
data class ManualSearchFilters(val type:String="Все",val edition:String="Игра",val maxPrice:String="",val confirmedRf:Boolean=false) {
    val amount:Double? get()=maxPrice.trim().replace(',','.').toDoubleOrNull()?.takeIf {it.isFinite() && it>0 && it<=100000000}
    val valid:Boolean get()=maxPrice.isBlank() || amount!=null
}
object ManualSearchSettings {
    private const val PREFS="manual_search_105"
    fun decode(raw:String?):ManualSearchFilters = runCatching {
        val j=JSONObject(raw ?: "{}")
        ManualSearchFilters(j.optString("type").takeIf {it in setOf("Все","Gift","Key")} ?: "Все",
            j.optString("edition").takeIf {it in setOf("Игра","Все","DLC")} ?: "Игра",
            j.optString("maxPrice").takeIf {it.isBlank() || ManualSearchFilters(maxPrice=it).valid}.orEmpty(),j.optBoolean("confirmedRf",false))
    }.getOrDefault(ManualSearchFilters())
    fun encode(filters:ManualSearchFilters)=JSONObject().put("type",filters.type).put("edition",filters.edition)
        .put("maxPrice",filters.maxPrice.takeIf {filters.valid}.orEmpty()).put("confirmedRf",filters.confirmedRf).toString()
    fun read(context:Context)=decode(context.getSharedPreferences(PREFS,0).getString("filters",null))
    fun save(context:Context,filters:ManualSearchFilters) {context.getSharedPreferences(PREFS,0).edit().putString("filters",encode(filters)).apply()}
    fun decodeGames(raw:String?):List<GameSearchResolver.Candidate> = runCatching {
        val a=JSONArray(raw ?: "[]")
        (0 until minOf(a.length(),20)).mapNotNull {i-> val j=a.optJSONObject(i) ?: return@mapNotNull null
            val id=j.optInt("id");val name=j.optString("name");val aliases=j.optJSONArray("aliases")?.let {a -> (0 until minOf(a.length(),12)).map {a.optString(it)}.filter {it.isNotBlank()} }.orEmpty()
            if(id>0 && name.isNotBlank())GameSearchResolver.Candidate(id,name,aliases) else null}
    }.getOrDefault(emptyList())
    fun encodeGames(games:List<GameSearchResolver.Candidate>):String {
        val a=JSONArray();games.take(20).forEach {a.put(JSONObject().put("id",it.appId).put("name",it.name).put("aliases",JSONArray(it.aliases.distinct().take(12))))}
        return a.toString()
    }
    fun games(context:Context):List<GameSearchResolver.Candidate> = decodeGames(context.getSharedPreferences(PREFS,0).getString("games","[]"))
    fun rememberGame(context:Context,game:GameSearchResolver.Candidate) {
        val updated=(listOf(game)+games(context).filterNot {it.appId==game.appId}).take(20)
        context.getSharedPreferences(PREFS,0).edit().putString("games",encodeGames(updated)).apply()
    }
    fun rememberAlias(context:Context,game:GameSearchResolver.Candidate,alias:String) {
        val clean=alias.trim().replace(Regex("\\s+")," ")
        if(clean.isBlank() || clean.length>120 || GameSearchResolver.normalized(clean)==GameSearchResolver.normalized(game.name)) return
        rememberGame(context,game.copy(aliases=(game.aliases+clean).distinct().take(12)))
    }
}
object ManualSearchFilter {
    fun rejection(offer:Offer,filters:ManualSearchFilters,rules:JSONObject=JSONObject(),ignored:Set<String> = emptySet()):String? = when {
        offer.id in ignored -> "Игнорируемые"
        !offer.detailsChecked || offer.available!=true -> "Наличие не подтверждено"
        !offer.priceVerified || offer.variablePrice -> "Цена варианта не подтверждена"
        offer.rfStatus=="РФ исключена" -> "РФ исключена"
        !ManualContentRules.visible(offer,"Все","Все",rules) -> "Пользовательские исключения"
        filters.edition!="Все" && offer.edition!=filters.edition -> "Другой или неизвестный состав"
        filters.type!="Все" && offer.type!=filters.type -> "Другой способ передачи"
        filters.confirmedRf && offer.rfStatus !in setOf("РФ указана в названии","РФ выбрана в параметрах") -> "Регион РФ не подтверждён"
        !filters.valid -> "Некорректная предельная цена"
        filters.amount!=null && offer.price>filters.amount!! -> "Дороже заданной цены"
        else -> null
    }
}
