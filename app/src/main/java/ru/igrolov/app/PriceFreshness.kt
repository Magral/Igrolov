package ru.igrolov.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Check age describes evidence, never a promise that a seller's price is still unchanged. */
object PriceFreshness {
    private const val MINUTE=60_000L
    private const val DAY=24*60*MINUTE
    fun age(checkedAt:Long,now:Long):String {
        val elapsed=(now-checkedAt).coerceAtLeast(0L)
        return when {
            elapsed<MINUTE -> "только что"
            elapsed<60*MINUTE -> "${elapsed/MINUTE} мин назад"
            elapsed<DAY -> "${elapsed/(60*MINUTE)} ч назад"
            elapsed<2*DAY -> "вчера"
            elapsed<7*DAY -> "${elapsed/DAY} дн назад"
            else -> SimpleDateFormat("dd.MM.yyyy",Locale("ru","RU")).format(Date(checkedAt))
        }
    }
    fun status(checkedAt:Long,failedAt:Long,now:Long,hasPrice:Boolean):String {
        if(checkedAt<=0L)return when {
            failedAt>0L && hasPrice -> "Не обновлено · время неизвестно"
            failedAt>0L -> "Не обновлено"
            hasPrice -> "Сохранено · время неизвестно"
            else -> ""
        }
        val age=age(checkedAt,now)
        return when {
            failedAt>checkedAt -> "Не обновлено · $age"
            hasPrice && now-checkedAt>=DAY -> "Сохранено · $age"
            else -> "Проверено $age"
        }
    }
    fun source(check:SourcePriceCheck?,displayedPrice:Double?,now:Long):String {
        val matching=check?.takeIf {it.price==displayedPrice}
        val failedAt=check?.takeIf {it.failedAt>it.checkedAt}?.failedAt ?: 0L
        return status(matching?.checkedAt ?: 0,failedAt,now,displayedPrice!=null)
    }
}
