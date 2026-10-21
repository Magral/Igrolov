package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgselBuyerApiTest {
    @Test fun requestsOnlyTheExactGameCategoryAndParsesItsActiveOffers() {
        val game=ManualGameReference.Game("Stellaris",281990,aliases=listOf("Stellaris: Galaxy Edition"))
        val paths=mutableListOf<String>()
        val pageUrls=mutableListOf<String>()
        val offers=GgselBuyerApi.find(game,post={path,body->
            paths+=path
            when(path) {
                GgselBuyerApi.CATEGORY_QUERY_URL -> {
                    assertEquals("Stellaris",body.getString("search_term"))
                    assertEquals("en",body.getString("lang"))
                    JSONObject("""{"data":{"categories":[
                        {"name":"Stellaris DLC","url":"stellaris-dlc"},
                        {"name":"Stellaris","url":"stellaris"}]}}""")
                }
                GgselBuyerApi.CATEGORY_GOODS_URL -> {
                    assertEquals(281990,body.getInt("digi_catalog"))
                    assertEquals("en",body.getString("lang"))
                    assertEquals("stellaris",body.getString("url"))
                    assertEquals("wmr",body.getString("currency"))
                    assertEquals("Steam",body.getJSONArray("platforms").getString(0))
                    JSONObject("""{"items":[
                        {"id_goods":123456,"url":"stellaris-key-ru","name":"Stellaris Steam Key РФ","forbidden_type":0,"price_for_one_wmr":"499.90","is_preorder":false,"options_check":false,"options":[]},
                        {"id_goods":123457,"name":"Stellaris Steam Key РФ","forbidden_type":0,"price_for_one_wmr":"1","is_preorder":true,"options_check":false,"options":[]}] }""")
                }
                else -> error("Unexpected GGSEL endpoint: $path")
            }
        },getCategoryPage={url->
            pageUrls+=url
            """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"category":{"name":"Stellaris","digi_catalog":281990}}}}</script>"""
        })
        assertEquals(listOf(GgselBuyerApi.CATEGORY_PAGE_BASE_URL+"stellaris"),pageUrls)
        assertEquals(listOf(GgselBuyerApi.CATEGORY_QUERY_URL,GgselBuyerApi.CATEGORY_GOODS_URL),paths)
        assertEquals("https://api.ggsel.com/elastic/goods/query-categories",paths[0])
        assertEquals("https://api4.ggsel.com/elastic/goods/categories",paths[1])
        assertTrue(paths.none { "/v2/" in it })
        assertEquals(1,offers.size)
        assertEquals("ggsel:123456",offers.single().id)
        assertEquals("GGSEL",offers.single().marketplace)
        assertEquals("РФ указана в названии",offers.single().rfStatus)
        assertTrue(offers.single().priceVerified && offers.single().available==true)
    }

    @Test fun doesNotRequestProductsWhenCategoryPageHasNoCatalogId() {
        val game=ManualGameReference.Game("Stellaris",281990)
        var goodsRequests=0
        val error=runCatching {
            GgselBuyerApi.find(game,post={path,_->
                if(path==GgselBuyerApi.CATEGORY_QUERY_URL)
                    JSONObject("""{"data":[{"name":"Stellaris","url":"stellaris"}]}""")
                else {
                    goodsRequests++
                    JSONObject()
                }
            },getCategoryPage={"<html><body>blocked</body></html>"})
        }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertEquals(0,goodsRequests)
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
