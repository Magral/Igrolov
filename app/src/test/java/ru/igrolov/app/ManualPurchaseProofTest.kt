package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManualPurchaseProofTest {
    private fun card()=JSONObject("""{"id":"123456","is_available":1,"prices":{"default":{"RUB":1000}}}""")
    private fun offer()=Offer("123456","Stellaris Steam Key РФ",1.0,"Key","РФ указана в названии","Игра",detailsChecked=true,available=true,priceVerified=true)
    @Test fun unavailableAndUnknownStockAlwaysHidden() {
        for (status in listOf(0,2,-1)) {
            val proof=ManualPurchaseProof.inspect(card().put("is_available",status),"Stellaris") { fail("No price request for unavailable card"); null }
            assertFalse(proof.available); assertNull(proof.price)
            assertFalse(ManualContentRules.visible(offer().copy(available=proof.available,priceVerified=false),"Все","Все"))
        }
        assertFalse(ManualContentRules.visible(offer().copy(available=null),"Игра","Все"))
        assertFalse(ManualContentRules.visible(offer().copy(detailsChecked=false),"Игра","Все"))
    }
    @Test fun freshCardReplacesTeaserAndMissingRubDoesNotUseCatalogPrice() {
        assertEquals(1000.0,ManualPurchaseProof.inspect(card(),"Stellaris") {null}.price!!,0.001)
        assertNull(ManualPurchaseProof.inspect(card().put("prices",JSONObject()),"Stellaris") {null}.price)
        assertFalse(ManualContentRules.visible(offer().copy(priceVerified=false),"Все","Все"))
    }
    @Test fun countryIsChosenUsingLabelRatherThanSystemNameAndCalculatedOnServer() {
        val p=card().put("options",org.json.JSONArray("""[
            {"id":12,"name":"option_text_12","label":"Профиль Steam","type":"text","required":1},
            {"id":13,"name":"option_radio_13","label":"Страна аккаунта","type":"radio","variants":[
                {"value":21,"text":"Украина","default":1,"visible":1},
                {"value":22,"text":"Россия","default":0,"modify_type":"RUB","modify_value":500,"visible":1}]}]"""))
        var path=""
        val proof=ManualPurchaseProof.inspect(p,"Stellaris") {
            path=it; JSONObject("""{"retval":0,"data":{"price":1,"amount":1500,"commission":50,"count":1,"currency":"RUB"}}""")
        }
        assertTrue(path.contains("options%5B%5D=13%3A22"))
        assertEquals(1550.0,proof.price!!,0.001); assertTrue(proof.label.contains("Россия"))
    }
    @Test fun unsupportedChoicesNoRussiaHiddenVariantsAndUnknownVariantStockAreRejected() {
        for (option in listOf(
            """{"id":1,"label":"Страна","type":"radio","variants":[{"value":2,"text":"Украина","visible":1}]}""",
            """{"id":1,"label":"Страна","type":"radio","variants":[{"value":2,"text":"Россия","visible":0}]}""",
            """{"id":1,"label":"Страна","type":"radio","separate_content":1,"variants":[{"value":2,"text":"Россия","visible":1}]}""",
            """{"id":1,"label":"Выбор товара","type":"radio","variants":[{"value":2,"text":"Stellaris: Utopia","visible":1}]}"""
        )) {
            val p=card().put("options",org.json.JSONArray().put(JSONObject(option)))
            assertNull(ManualPurchaseProof.inspect(p,"Stellaris") { fail("Ambiguous choice must not be guessed");null }.price)
        }
    }
    @Test fun rejectedCalculationWrongCurrencyOrWrongCountCannotLeakTeaserPrice() {
        val p=card().put("options",JSONObject("""{"option":[{"id":1,"label":"Регион","type":"select","variants":{"variant":[{"value":2,"text":"Россия","visible":1}]}}]}"""))
        for(response in listOf(
            """{"retval":33}""",
            """{"retval":0,"data":{"amount":1,"count":1,"currency":"USD","commission":0}}""",
            """{"retval":0,"data":{"amount":1,"count":2,"currency":"RUB","commission":0}}"""
        )) assertNull(ManualPurchaseProof.inspect(p,"Stellaris") { JSONObject(response) }.price)
    }
    @Test fun namedDlcNeverAssumedToBeMainGameEvenIfSteamIsUnavailable() {
        val game=ManualGameReference.Game("Stellaris")
        for(name in listOf("Stellaris: Utopia Steam Key РФ","Stellaris MEGACORP Steam Key","Stellaris Overlord Steam Gift","Stellaris: The Machine Age Steam Key")) {
            val kind=ManualContentRules.classify(name,"",game)
            assertNotEquals(name,"Игра",kind)
            assertFalse(ManualContentRules.visible(offer().copy(name=name,edition=kind),"Игра","Все"))
        }
        assertEquals("Игра",ManualContentRules.classify("Stellaris Steam Key РФ+СНГ","",game))
        assertEquals("Игра",ManualContentRules.classify("Stellaris Galaxy Edition Steam Key РФ","",game))
        assertEquals("DLC",ManualContentRules.classify("Stellaris Steam Key","Requires the base game Stellaris",game))
        assertEquals("DLC",ManualContentRules.classify("Stellaris Steam Gift","https://store.steampowered.com/app/518910",game.copy(appId=281990,dlcIds=setOf(518910))))
    }
    @Test fun enrichmentSortsByCurrentVerifiedPriceNotTheSearchTeaser() {
        val offers=listOf(offer().copy(id="10000",price=1.0),offer().copy(id="10001",price=10.0))
        val result=ManualCatalogEnricher { path ->
            val id=path.substringAfter("/products/").substringBefore("/")
            card().put("id",id).put("prices",JSONObject().put("default",JSONObject().put("RUB",if(id=="10000")2000 else 1000)))
                .let { JSONObject().put("retval",0).put("product",it) }
        }.enrich(offers,emptyMap(),{true},reference=ManualGameReference.Game("Stellaris"))
        assertEquals(listOf("10001","10000"),result.map { it.id })
        assertEquals(listOf(1000.0,2000.0),result.map { it.price })
        assertTrue(result.all { ManualContentRules.visible(it,"Игра","Key") })
    }
}
