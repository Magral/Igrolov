package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject

/** Separate persistent snapshots for Leaders and Wishlist; panel expansion stays temporary. */
object CatalogFilterSettings {
    data class Snapshot(val input:SteamLeaderFilterInput=SteamLeaderFilterInput(),val availability:String="Все игры")
    fun encode(input:SteamLeaderFilterInput,availability:String="Все игры"):String = JSONObject()
        .put("title",input.titleQuery).put("price",input.maxPriceText)
        .put("discount",input.minimumDiscountText).put("rating",input.minimumReviewText)
        .put("reviews",input.minimumReviewCountText)
        .put("russian",input.russianLocalization?.name.orEmpty())
        .put("controller",input.controllerSupport?.name.orEmpty())
        .put("genres",JSONArray(input.selectedGenres.sorted())).put("availability",availability).toString()

    fun decode(saved:String?):Snapshot = runCatching {
        val root=JSONObject(saved ?: "{}")
        val genres=root.optJSONArray("genres") ?: JSONArray()
        Snapshot(SteamLeaderFilterInput(
            titleQuery=root.optString("title"),maxPriceText=root.optString("price"),
            minimumDiscountText=root.optString("discount"),minimumReviewText=root.optString("rating"),
            minimumReviewCountText=root.optString("reviews"),
            russianLocalization=SteamRussianLocalization.values().firstOrNull {it.name==root.optString("russian")},
            controllerSupport=SteamControllerSupport.values().firstOrNull {it.name==root.optString("controller")},
            selectedGenres=(0 until genres.length()).map {genres.optString(it)}.filter {it.isNotBlank()}.toSet()
        ),root.optString("availability").takeIf {it in setOf("Все игры","Доступны в Steam RU","Недоступны в Steam RU")} ?: "Все игры")
    }.getOrDefault(Snapshot())

    fun summary(input:SteamLeaderFilterInput):String = buildList {
        if(input.titleQuery.isNotBlank())add("Название: ${input.titleQuery.trim()}")
        if(input.maxPriceText.isNotBlank())add("До ${input.maxPriceText} ₽")
        if(input.minimumDiscountText.isNotBlank())add("Скидка от ${input.minimumDiscountText}%")
        if(input.minimumReviewText.isNotBlank())add("Рейтинг от ${input.minimumReviewText}%")
        if(input.minimumReviewCountText.isNotBlank())add("Отзывов от ${input.minimumReviewCountText}")
    }.joinToString(" · ")
}
