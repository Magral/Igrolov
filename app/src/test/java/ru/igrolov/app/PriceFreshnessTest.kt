package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class PriceFreshnessTest {
    private val now=1_800_000_000_000L
    @Test fun ageChangesAtMinuteHourAndDayBoundaries() {
        assertEquals("только что",PriceFreshness.age(now-59_999,now))
        assertEquals("1 мин назад",PriceFreshness.age(now-60_000,now))
        assertEquals("59 мин назад",PriceFreshness.age(now-3_599_999,now))
        assertEquals("1 ч назад",PriceFreshness.age(now-3_600_000,now))
        assertEquals("вчера",PriceFreshness.age(now-86_400_000,now))
        assertEquals("3 дн назад",PriceFreshness.age(now-3*86_400_000L,now))
        assertEquals("только что",PriceFreshness.age(now+60_000,now))
    }
    @Test fun unknownAndFailedChecksCannotLookLikeFreshConfirmedPrices() {
        assertEquals("Сохранено · время неизвестно",PriceFreshness.status(0,0,now,true))
        assertEquals("Не обновлено",PriceFreshness.status(0,now,now,false))
        assertEquals("Не обновлено · время неизвестно",PriceFreshness.status(0,now,now,true))
        assertEquals("Не обновлено · время неизвестно",PriceFreshness.source(SourcePriceCheck(failedAt=now),100.0,now))
        assertEquals("Не обновлено · 2 ч назад",PriceFreshness.status(now-7_200_000,now-1,now,true))
        assertEquals("Проверено только что",PriceFreshness.status(now,now-10,now,true))
    }
    @Test fun savedDayOldPriceRemainsReadableAndOlderChecksShowDate() {
        assertEquals("Сохранено · вчера",PriceFreshness.status(now-86_400_000,0,now,true))
        assertTrue(PriceFreshness.status(now-8*86_400_000L,0,now,true).matches(Regex("Сохранено · [0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")))
        assertEquals("Проверено вчера",PriceFreshness.status(now-86_400_000,0,now,false))
    }
    @Test fun aDifferentLegacyPriceCannotBorrowAnotherPricesTimestamp() {
        val check=SourcePriceCheck(100.0,now)
        assertEquals("Проверено только что",PriceFreshness.source(check,100.0,now))
        assertEquals("Сохранено · время неизвестно",PriceFreshness.source(check,90.0,now))
    }
}
