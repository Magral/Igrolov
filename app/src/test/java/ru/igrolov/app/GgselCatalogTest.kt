package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgselCatalogTest {
    @Test fun selectsOnlyAnExactGameCategoryOrSavedAlias() {
        val root = JSONObject("""
            {"data":{"categories":[
              {"id":10,"digi_catalog":100,"name":"Stellaris DLC","url":"stellaris-dlc"},
              {"id":11,"digi_catalog":101,"name":"Stellaris","url":"stellaris"}
            ]}}
        """.trimIndent())
        val categories = GgselCatalog.categories(root)
        assertEquals("stellaris", GgselCatalog.chooseCategory(categories, listOf("Stellaris"))?.url)
        assertNull(GgselCatalog.chooseCategory(categories, listOf("Stellaris: Galaxy Edition")))
    }

    @Test fun parsesActiveFixedPriceSteamGameOffersAndRejectsDlcAccountsAndUnknownStock() {
        val root = JSONObject("""
            {"data":{"pages":[{"items":[
              {"id_goods":123456,"url":"stellaris-steam-key-ru-123456","name":"Stellaris Steam Key RU","forbidden_type":0,"price_for_one_wmr":"499.90","is_preorder":false,"options_check":false,"options":[]},
              {"id_goods":123457,"name":"Stellaris Steam Gift RU","forbidden_type":1,"price_for_one_wmr":"1","options_check":false},
              {"id_goods":123458,"name":"Stellaris: Utopia Steam Key RU","forbidden_type":0,"price_for_one_wmr":"5","options_check":false},
              {"id_goods":123459,"name":"Stellaris Steam Account RU","forbidden_type":0,"price_for_one_wmr":"2","options_check":false},
              {"id_goods":123460,"name":"Stellaris Steam Key RU","price_for_one_wmr":"3","options_check":false},
              {"id_goods":123461,"name":"Stellaris Steam Key RU","forbidden_type":0,"price_for_one_wmr":"4","options_check":true},
              {"id_goods":123462,"name":"Stellaris Steam Key RU","forbidden_type":0,"price_for_one_wmr":"0","options_check":false},
              {"id_goods":123463,"name":"Stellaris Steam Key TR","forbidden_type":0,"price_for_one_wmr":"100","options_check":false}
            ]}]}}
        """.trimIndent())
        val offers = GgselCatalog.offers(root, ManualGameReference.Game("Stellaris", 281990))
        assertEquals(1, offers.size)
        val offer = offers.single()
        assertEquals("ggsel:123456", offer.id)
        assertEquals("GGSEL", offer.marketplace)
        assertEquals("Key", offer.type)
        assertEquals(499.90, offer.price, 0.001)
        assertEquals("https://ggsel.net/en/catalog/product/stellaris-steam-key-ru-123456", offer.productUrl)
        assertTrue(offer.available == true && offer.priceVerified && offer.detailsChecked)
    }

    @Test fun leavesUnconfirmedRussianRegionMarkedRatherThanClaimingCompatibility() {
        val root = JSONObject("""
            {"items":[{"id_goods":987654,"name":"Stellaris Steam Key","forbidden_type":0,
              "price_wmr":"450","options_check":false}]}
        """.trimIndent())
        val offer = GgselCatalog.offers(root, ManualGameReference.Game("Stellaris", 281990)).single()
        assertEquals("Регион РФ не подтвержден", offer.rfStatus)
        assertFalse(OfferRules.eligible(offer, "Игра", "Key", null))
    }
}
