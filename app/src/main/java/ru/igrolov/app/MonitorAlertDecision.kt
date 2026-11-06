package ru.igrolov.app

/** Suppression follows the game's delivered minimum, even when the cheapest seller changes. */
object MonitorAlertDecision {
    fun evaluate(price:Double?,target:Double,dropPercent:Int,anchor:Double,lastDelivered:Double):String? {
        if(price==null || !price.isFinite() || price<=0)return null
        if(lastDelivered.isFinite() && lastDelivered>0 && price>=lastDelivered-0.005)return null
        if(target.isFinite() && target>0 && price<=target)return "target"
        if(dropPercent in setOf(5,10,20) && anchor.isFinite() && anchor>0 && price<anchor-0.005 &&
            price<=anchor*(1.0-dropPercent/100.0)+0.000001)return "drop"
        return null
    }
}
