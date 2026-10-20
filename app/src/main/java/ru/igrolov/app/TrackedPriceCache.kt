package ru.igrolov.app

import org.json.JSONObject

/** Older versions cached catalog/default teaser prices without validating purchase options. */
object TrackedPriceCache {
    private const val SCHEMA=106
    fun decode(id:String,raw:String?):Tracked {
        val cached=runCatching {JSONObject(raw ?: "{}")}.getOrDefault(JSONObject())
        val price=if(cached.optInt("proofSchema")==SCHEMA)cached.optDouble("price",Double.NaN).takeIf {it.isFinite() && it>0} else null
        return Tracked(id,cached.optString("name","Товар $id"),price,"Обновляем цену…","https://plati.market/itm/$id")
    }
    fun encode(item:Tracked):String? {
        if(item.status.startsWith("Ошибка загрузки"))return null
        return JSONObject().put("proofSchema",SCHEMA).put("name",item.name)
            .put("price",item.price?.takeIf {it.isFinite() && it>0} ?: JSONObject.NULL).toString()
    }
}
