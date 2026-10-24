package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject

/** Only verified Russian main-game Gift/Key offers can trigger automatic notifications. */
object MonitorPolicy {
    fun eligible(offer:Offer,kind:String,rules:JSONObject=JSONObject(),ignored:Set<String> = emptySet()):Boolean =
        offer.price.isFinite() && offer.price>0 && offer.type in setOf("Gift","Key") &&
            ManualSearchFilter.rejection(offer,ManualSearchFilters(type=kind,confirmedRf=true),rules,ignored)==null

    data class Alerts(val offers:List<Offer>,val prices:JSONObject)
    /** Return each marketplace's own lowest confirmed Russian offer. */
    fun minimumsByMarketplace(offers:List<Offer>):JSONObject {
        val minima=JSONObject()
        offers.asSequence()
            .filter { it.marketplace in setOf("Plati", "GGSEL") && eligible(it, "Все") }
            .groupBy { it.marketplace }
            .forEach { (marketplace, rows) -> minima.put(marketplace, rows.minOf { it.price }) }
        return minima
    }

    fun alerts(offers:List<Offer>,limit:Double,previous:JSONObject,legacy:JSONArray):Alerts {
        val prices=JSONObject(previous.toString())
        val legacyIds=(0 until legacy.length()).map {legacy.optString(it)}.toSet()
        val found=mutableListOf<Offer>()
        for(offer in offers) {
            if(!offer.price.isFinite() || offer.price<=0) continue
            val old=prices.optDouble(offer.id,Double.NaN)
            if(!old.isFinite() && offer.id in legacyIds) {
                // Migration: retain old notification suppression; establish a price for future drops.
                prices.put(offer.id,offer.price)
            } else if(limit.isFinite() && limit>0 && offer.price<=limit && (!old.isFinite() || offer.price<old-0.005)) {
                found.add(offer);prices.put(offer.id,offer.price)
            }
        }
        // Keep recently observed offers first and bound preferences growth.
        val retained=JSONObject()
        (offers.map {it.id}+prices.keys().asSequence().toList()).distinct().take(500).forEach {id->if(prices.has(id))retained.put(id,prices.get(id))}
        return Alerts(found,retained)
    }
}

object MonitorSchedule {
    const val CHECK_INTERVAL_MILLIS = 60L * 60L * 1000L

    /** WorkManager runs approximately hourly; this is an estimate from the latest attempt. */
    fun expectedNextCheck(checkedAt: Long, failedAt: Long): Long? {
        // WorkManager retries failures with backoff, so an hourly timestamp would be misleading.
        if (failedAt > checkedAt) return null
        return checkedAt.takeIf { it > 0L }?.plus(CHECK_INTERVAL_MILLIS)
    }
}

object MonitorDiagnostics {
    fun noOfferReason(totalCount: Int, eligibleCount: Int, rejectedCount: Int, hasError: Boolean): String? {
        if (hasError || eligibleCount > 0) return null
        return when {
            totalCount <= 0 -> "Кандидаты основной игры Steam не найдены в каталогах Plati и GGSEL."
            rejectedCount > 0 -> "Найдено кандидатов: $totalCount, но ни один не прошёл проверки основной игры, Gift/Key, РФ, наличия и цены."
            else -> "Подходящих предложений пока нет."
        }
    }
}

object WatchHistory {
    data class Point(val at:Long,val price:Double?)
    data class PriceChange(val previous:Double,val current:Double) {
        val difference:Double get() = current - previous
        val percent:Double get() = (difference / previous) * 100.0
    }
    fun points(history:JSONArray):List<Point> = (0 until history.length()).mapNotNull {i ->
        val j=history.optJSONObject(i) ?: return@mapNotNull null
        val at=j.optLong("at");if(at<=0)return@mapNotNull null
        Point(at,j.optDouble("price",Double.NaN).takeIf {it.isFinite() && it>0})
    }.sortedBy {it.at}
    /** Compare the two latest confirmed prices; no-offer checks remain gaps, not zero-price drops. */
    fun priceChange(history:JSONArray):PriceChange? {
        val prices=points(history).mapNotNull {it.price}.takeLast(2)
        if(prices.size<2) return null
        return PriceChange(prices[0],prices[1])
    }
    fun append(history:JSONArray,at:Long,price:Double?,count:Int):JSONArray {
        val result=JSONArray()
        val cutoff=at-90L*24*60*60*1000
        (0 until history.length()).mapNotNull {history.optJSONObject(it)}
            .filter {it.optLong("at")>=cutoff && it.optLong("at")>0 && it.optLong("at")<=at}
            .sortedBy {it.optLong("at")}.takeLast(399).forEach {old->
                val row=JSONObject(old.toString())
                val value=row.optDouble("price",Double.NaN)
                if(!value.isFinite() || value<=0)row.remove("price")
                result.put(row)
            }
        val row=JSONObject().put("at",at).put("count",count)
        if(price!=null && price.isFinite() && price>0)row.put("price",price)
        return result.put(row)
    }
}
