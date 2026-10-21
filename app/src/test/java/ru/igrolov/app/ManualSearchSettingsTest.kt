package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class ManualSearchSettingsTest {
    private fun offer()=Offer("123456","Stellaris Steam Key РФ",1500.0,"Key","РФ указана в названии","Игра",detailsChecked=true,available=true,priceVerified=true)
    @Test fun settingsRoundTripAndBrokenPreferencesHaveSafeDefaults() {
        val filters=ManualSearchFilters("Key","DLC","2500,50",true)
        assertEquals(filters,ManualSearchSettings.decode(ManualSearchSettings.encode(filters)))
        assertEquals(ManualSearchFilters(),ManualSearchSettings.decode("broken"))
        assertEquals(ManualSearchFilters(),ManualSearchSettings.decode("""{"type":"Account","edition":"anything","maxPrice":"NaN"}"""))
        for(value in listOf("NaN","Infinity","-1","0","1e12","oops")) assertFalse(value,ManualSearchFilters(maxPrice=value).valid)
    }
    @Test fun marketplaceAliasesPersistWithTheSteamAppId() {
        val game=GameSearchResolver.Candidate(2863680,"ZERO PARADES: Director's Cut",listOf("ZERO PARADES: For Dead Spies"))
        assertEquals(game,ManualSearchSettings.decodeGames(ManualSearchSettings.encodeGames(listOf(game))).single())
        assertTrue(ManualSearchSettings.decodeGames("broken").isEmpty())
    }
    @Test fun savedFiltersNeverBypassStockPriceOrRegionExclusions() {
        val filters=ManualSearchFilters(edition="Все")
        for(bad in listOf(offer().copy(available=false),offer().copy(available=null),offer().copy(priceVerified=false),offer().copy(variablePrice=true),offer().copy(rfStatus="РФ исключена"),offer().copy(detailsChecked=false)))
            assertNotNull(ManualSearchFilter.rejection(bad,filters))
        assertNull(ManualSearchFilter.rejection(offer(),filters))
    }
    @Test fun priceDeliveryCompositionAndConfirmedRegionExplainRejection() {
        assertEquals("Дороже заданной цены",ManualSearchFilter.rejection(offer(),ManualSearchFilters(maxPrice="1499")))
        assertNull(ManualSearchFilter.rejection(offer(),ManualSearchFilters(maxPrice="1500")))
        assertEquals("Другой способ передачи",ManualSearchFilter.rejection(offer(),ManualSearchFilters(type="Gift")))
        assertEquals("Другой или неизвестный состав",ManualSearchFilter.rejection(offer().copy(edition="DLC"),ManualSearchFilters()))
        assertEquals("Регион РФ не подтверждён",ManualSearchFilter.rejection(offer().copy(rfStatus="Регион не указан"),ManualSearchFilters(confirmedRf=true)))
        assertNull(ManualSearchFilter.rejection(offer().copy(rfStatus="РФ выбрана в параметрах"),ManualSearchFilters(confirmedRf=true)))
        assertEquals("Игнорируемые",ManualSearchFilter.rejection(offer(),ManualSearchFilters(),ignored=setOf("123456")))
    }
}
