package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class CatalogFilterSettingsTest {
    @Test fun restoresEveryFilterWithoutReopeningPanel() {
        val input=SteamLeaderFilterInput(expanded=true,titleQuery="Kingdom",maxPriceText="2000,50",
            minimumDiscountText="30",minimumReviewText="90",minimumReviewCountText="1000",
            russianLocalization=SteamRussianLocalization.SUPPORTED,controllerSupport=SteamControllerSupport.FULL,
            selectedGenres=setOf("RPG","Стратегии"))
        val restored=CatalogFilterSettings.decode(CatalogFilterSettings.encode(input,"Недоступны в Steam RU"))
        assertEquals(input.copy(expanded=false),restored.input)
        assertEquals("Недоступны в Steam RU",restored.availability)
    }
    @Test fun invalidAndUnknownStoredValuesRemainUsable() {
        assertEquals(CatalogFilterSettings.Snapshot(),CatalogFilterSettings.decode("invalid"))
        assertEquals(CatalogFilterSettings.Snapshot(),CatalogFilterSettings.decode(null))
        val restored=CatalogFilterSettings.decode("""{"russian":"NEW_VALUE","controller":"BAD","availability":"BAD","genres":["RPG","RPG",""]}""")
        assertNull(restored.input.russianLocalization);assertNull(restored.input.controllerSupport)
        assertEquals(setOf("RPG"),restored.input.selectedGenres);assertEquals("Все игры",restored.availability)
    }
    @Test fun compactSummaryMakesHiddenPriceAndRatingFiltersVisible() {
        assertEquals("",CatalogFilterSettings.summary(SteamLeaderFilterInput()))
        assertEquals("До 1500 ₽ · Скидка от 25% · Рейтинг от 80%",CatalogFilterSettings.summary(
            SteamLeaderFilterInput(maxPriceText="1500",minimumDiscountText="25",minimumReviewText="80")))
    }
}
