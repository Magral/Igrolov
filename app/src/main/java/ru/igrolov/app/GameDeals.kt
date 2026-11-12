package ru.igrolov.app

enum class DealSort(val label:String) { DEFAULT("По умолчанию"), PRICE("По найденной цене"), SAVING("По выгоде") }
data class GameSaving(val rubles:Double,val percent:Int,val marketplace:String="")

object GameDeals {
    fun price(source:SourcePriceCheck?,legacy:Double?=null):Double? =
        (if(source!=null && source.checkedAt>0)source.price else source?.price ?: legacy)?.takeIf {it.isFinite() && it>0}
    fun minimum(prices:CachedGamePrices?,plati:Double?=null,ggsel:Double?=null)=
        listOfNotNull(price(prices?.plati,plati),price(prices?.ggsel,ggsel)).minOrNull()
    private fun fresh(checked:Long,failed:Long,now:Long)=checked>0 && failed<=checked && now-checked in 0..86_400_000L
    fun saving(quote:SteamRuQuote?,prices:CachedGamePrices?,now:Long):GameSaving? {
        if(quote==null || quote.available!=true || quote.scope.isBlank() || quote.price==null || quote.price<=0 ||
            !fresh(quote.checkedAt,quote.failedAt,now))return null
        val best=listOf("Plati" to prices?.plati,"GGSEL" to prices?.ggsel).filter {(_,source)->
            source!=null && source.scope==quote.scope && fresh(source.checkedAt,source.failedAt,now) && price(source)!=null
        }.minByOrNull {it.second!!.price!!} ?: return null
        val difference=quote.price-best.second!!.price!!
        if(difference<0.01)return null
        return GameSaving(difference,((difference/quote.price)*100).toInt().coerceIn(0,100),best.first)
    }
    fun <T> sort(items:List<T>,order:DealSort,price:(T)->Double?,saving:(T)->GameSaving?):List<T> {
        if(order==DealSort.DEFAULT)return items
        val metrics=items.mapIndexed {index,item->Triple(item,index,if(order==DealSort.PRICE)price(item) else saving(item)?.rubles)}
        return metrics.sortedWith(compareBy<Triple<T,Int,Double?>> {it.third==null}
            .thenComparator {a,b->if(a.third==null || b.third==null)0 else if(order==DealSort.PRICE)a.third!!.compareTo(b.third!!) else b.third!!.compareTo(a.third!!)}
            .thenBy {it.second}).map {it.first}
    }
}
