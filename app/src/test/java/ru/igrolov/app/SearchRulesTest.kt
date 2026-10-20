package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchRulesTest {
    private fun offer(title: String, edition: String="Игра") = Offer("123456",title,100.0,"Key","РФ указана в названии",edition)
    @Test fun steamReferenceSeparatesDlcFromFullEditions() {
        val game=SteamGameReference.Game("Cyberpunk 2077",listOf("Cyberpunk 2077: Phantom Liberty"),"test")
        assertFalse(SteamGameReference.isMainGame(offer("Cyberpunk 2077 Phantom Liberty Steam Key"),game))
        assertTrue(SteamGameReference.isMainGame(offer("Cyberpunk 2077 Ultimate Edition Steam Key"),game))
        assertFalse(SteamGameReference.isMainGame(offer("Cyberpunk 2077 Upgrade Steam Key","DLC"),game))
        val blocked=JSONObject("""{"blocked":["ultimate"],"allowed":["ultimate"]}""")
        assertFalse(SteamGameReference.isMainGame(offer("Cyberpunk 2077 Ultimate Edition Steam Key"),game,blocked))
    }
    @Test fun explicitRussiaExclusionOverridesLanguageAndUnknownIsManualOnly() {
        assertEquals("РФ исключена",OfferRules.rfStatus("Cyberpunk 2077 Steam Key кроме РФ RU language"))
        assertEquals("Регион РФ не подтвержден",OfferRules.rfStatus("Cyberpunk 2077 Steam Key Russian language"))
        val unknown=offer("Cyberpunk 2077 Steam Key").copy(rfStatus="Регион РФ не подтвержден")
        assertTrue(OfferRules.eligible(unknown,"Все","Все",null,allowUnconfirmed=true))
        assertFalse(OfferRules.eligible(unknown,"Все","Все",null,allowUnconfirmed=false))
        assertFalse(OfferRules.eligible(unknown.copy(rfStatus="РФ исключена"),"Все","Все",null,allowUnconfirmed=true))
    }
    @Test fun punctuationNumbersAndBonusGiftCannotMisclassifyDelivery() {
        assertEquals("Key",ManualOfferClassifier.titleType("Tom Clancy’s Rainbow Six Siege · STEAM KEY", "Tom Clancy's Rainbow Six Siege"))
        assertNull(ManualOfferClassifier.titleType("Cyberpunk 2077 Steam Key","Cyberpunk 207"))
        assertEquals("Не указан",ManualOfferClassifier.titleType("Cyberpunk 2077 Steam + ПОДАРОК","Cyberpunk 2077"))
        assertNull(ManualOfferClassifier.titleType("Cyberpunk 2077 Xbox Steam Key","Cyberpunk 2077"))
    }
}
