package ru.igrolov.app

import org.json.JSONObject

/** Older versions cached catalog/default teaser prices without validating purchase options. */
object TrackedPriceCache {
    private const val SCHEMA=106

    fun storageKey(marketplace:String,id:String):String = "${if(marketplace=="GGSEL") "GGSEL" else "Plati"}:$id"

    private fun parts(key:String):Pair<String,String> {
        val separator=key.indexOf(':')
        if(separator>0) {
            val marketplace=key.substring(0,separator)
            val id=key.substring(separator+1)
            if(marketplace.equals("GGSEL",true) && id.isNotBlank())return "GGSEL" to id
            if(marketplace.equals("Plati",true) && id.isNotBlank())return "Plati" to id
        }
        return "Plati" to key
    }

    fun decode(key:String,raw:String?):Tracked {
        val (marketplace,id)=parts(key)
        val cached=runCatching {JSONObject(raw ?: "{}")}.getOrDefault(JSONObject())
        val price=if(cached.optInt("proofSchema")==SCHEMA)cached.optDouble("price",Double.NaN).takeIf {it.isFinite() && it>0} else null
        val url=cached.optString("url").ifBlank {
            if(marketplace=="GGSEL") "https://ggsel.net/" else "https://plati.market/itm/$id"
        }
        return Tracked(id,cached.optString("name","Товар $id"),price,"Обновляем цену…",url,
            cached.optString("canonical"),cached.optInt("steamAppId"),marketplace)
    }

    fun fromOffer(offer:Offer):Tracked {
        val url=offer.productUrl.ifBlank {
            if(offer.marketplace=="GGSEL") "https://ggsel.net/" else "https://plati.market/itm/${offer.id}"
        }
        return Tracked(offer.id,offer.name,offer.price.takeIf {offer.priceVerified && offer.available==true && !offer.variablePrice},
            "Ожидает обновления",url,offer.canonical,offer.steamAppId,offer.marketplace)
    }

    fun merge(previous:Tracked,fresh:Tracked):Tracked =
        if(fresh.status.startsWith("Ошибка загрузки") && previous.price!=null)
            previous.copy(status="Нет связи · показана последняя проверенная цена") else fresh

    fun encode(item:Tracked):String? {
        if(item.status.startsWith("Ошибка загрузки"))return null
        return JSONObject().put("proofSchema",SCHEMA).put("name",item.name).put("canonical",item.canonical)
            .put("steamAppId",item.steamAppId).put("marketplace",item.marketplace).put("url",item.url)
            .put("price",item.price?.takeIf {it.isFinite() && it>0} ?: JSONObject.NULL).toString()
    }
}
