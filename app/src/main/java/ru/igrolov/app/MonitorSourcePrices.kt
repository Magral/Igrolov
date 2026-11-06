package ru.igrolov.app

import org.json.JSONObject

object MonitorSourcePrices {
    fun read(watch:JSONObject):CachedGamePrices = OfferPriceCache.decode(watch.optString("sourceChecks")) ?: run {
        val minima=watch.optJSONObject("sourceMinimums")
        fun source(name:String)=SourcePriceCheck(
            price=minima?.optDouble(name,Double.NaN)?.takeIf {it.isFinite() && it>0},
            checkedAt=watch.optLong("checked").coerceAtLeast(0))
        CachedGamePrices(source("Plati"),source("GGSEL"))
    }
}
