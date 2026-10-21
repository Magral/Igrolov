package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgselBuyerApiTest {
    @Test fun requestsOnlyTheExactGameCategoryAndParsesItsActiveOffers() {
        val game=ManualGameReference.Game("Stellaris",281990,aliases=listOf("Stellaris: Galaxy Edition"))
        val paths=mutableListOf<String>()
        val offers=GgselBuyerApi.find(game,post={path,body->
            paths+=path
            when(path) {
                "elastic/goods/query-categories" -> {
                    assertEquals("Stellaris",body.getString("search_term"))
                    JSONObject("""{"data":{"categories":[
                        {"digi_catalog":7,"name":"Stellaris DLC","url":"stellaris-dlc"},
                        {"digi_catalog":8,"name":"Stellaris","url":"stellaris"}]}}""")
                }
                "elastic/goods/categories" -> {
                    assertEquals(8,body.getInt("digi_catalog"))
                    assertEquals("stellaris",body.getString("url"))
                    assertEquals("wmr",body.getString("currency"))
                    assertEquals("Steam",body.getJSONArray("platforms").getString(0))
                    JSONObject("""{"items":[
                        {"id_goods":123456,"url":"stellaris-key-ru","name":"Stellaris Steam Key РФ","forbidden_type":0,"price_for_one_wmr":"499.90","is_preorder":false,"options_check":false,"options":[]},
                        {"id_goods":123457,"name":"Stellaris Steam Key РФ","forbidden_type":0,"price_for_one_wmr":"1","is_preorder":true,"options_check":false,"options":[]}] }""")
                }
                else -> error("Unexpected GGSEL endpoint: $path")
            }
        })
        assertEquals(listOf("elastic/goods/query-categories","elastic/goods/categories"),paths)
        assertEquals(1,offers.size)
        assertEquals("ggsel:123456",offers.single().id)
        assertEquals("GGSEL",offers.single().marketplace)
        assertEquals("РФ указана в названии",offers.single().rfStatus)
        assertTrue(offers.single().priceVerified && offers.single().available==true)
    }

    @Test fun missingExactCategoryIsAConfirmedEmptyResultWithoutBroadSearch() {
        var calls=0
        val offers=GgselBuyerApi.find(ManualGameReference.Game("Zero Parades: Director's Cut",123),post={_,_->
            calls++
            JSONObject("""{"items":[{"digi_catalog":5,"name":"Zero Parades","url":"zero-parades"}]}""")
        })
        assertTrue(offers.isEmpty())
        assertEquals(1,calls)
    }
}
