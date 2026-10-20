package ru.igrolov.app

import org.json.JSONObject

/** Older versions cached catalog/default teaser prices without validating purchase options. */
object TrackedPriceCache {
    private const val SCHEMA=106
    fun decode(id:String,raw:String?):Tracked {
        val cached=runCatching {JSONObject(raw ?: "{}")}.getOrDefault(JSONObject())
        val price=if(cached.optInt("proofSchema")==SCHEMA)cached.optDouble("price",Double.NaN).takeIf {it.isFinite() && it>0} else null
        return Tracked(id,cached.optString("name","Товар $id"),price,"Обновляем цену…","https://plati.market/itm/$id",cached.optString("canonical"),cached.optInt("steamAppId"))
    }
    fun fromOffer(offer:Offer)=Tracked(offer.id,offer.name,offer.price.takeIf {offer.priceVerified && offer.available==true && !offer.variablePrice},
        "Ожидает обновления","https://plati.market/itm/${offer.id}",offer.canonical,offer.steamAppId)
    fun merge(previous:Tracked,fresh:Tracked):Tracked =
        if(fresh.status.startsWith("Ошибка загрузки") && previous.price!=null)
            previous.copy(status="Нет связи · показана последняя проверенная цена") else fresh
    fun encode(item:Tracked):String? {
        if(item.status.startsWith("Ошибка загрузки"))return null
        return JSONObject().put("proofSchema",SCHEMA).put("name",item.name).put("canonical",item.canonical).put("steamAppId",item.steamAppId)
            .put("price",item.price?.takeIf {it.isFinite() && it>0} ?: JSONObject.NULL).toString()
    }
}
