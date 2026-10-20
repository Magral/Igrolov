package ru.igrolov.app

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Enrich every candidate before sorting: the catalog's teaser order is not a final price order. */
object VerifiedMonitorCheck {
    data class Result(val verified:List<Offer>,val checked:Int,val rejected:Int)
    fun run(catalog:CatalogCheck.Snapshot,reference:ManualGameReference.Game,kind:String,rules:JSONObject,
            ignored:Set<String>,active:()->Boolean,request:(String)->JSONObject? = {CatalogApi.get(it,true)}):Result {
        require(reference.appId>0) {"Основная игра не подтверждена Steam"}
        val failures=AtomicInteger()
        val enriched=ManualCatalogEnricher {path->
            try {request(path)} catch(e:Exception) {
                // Category lookup is optional; a card or calculation failure makes the minimum incomplete.
                if(!path.startsWith("/api/categories"))failures.incrementAndGet()
                null
            }
        }.enrich(catalog.offers.filterNot {it.id in ignored},catalog.sellers,{active() && failures.get()==0},reference)
        if(!active())throw java.util.concurrent.CancellationException()
        if(failures.get()>0)throw IOException("Не удалось проверить ${failures.get()} карточек/расчётов; предыдущий результат сохранён")
        val valid=enriched.filter {MonitorPolicy.eligible(it,kind,rules,ignored)}.sortedBy {it.price}
        return Result(valid,catalog.offers.size,catalog.offers.size-valid.size)
    }
}
