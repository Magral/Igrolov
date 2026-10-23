package ru.igrolov.app

import org.json.JSONObject
import java.net.UnknownHostException
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
            """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"category":{"name":"Stellaris","url":"stellaris","digi_catalog":281990}}}}</script>"""
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


    @Test fun fallsBackToPrimaryApiHostOnlyWhenApi4CannotResolve() {
        val game=ManualGameReference.Game("Stellaris",281990)
        val requested=mutableListOf<String>()
        val offers=GgselBuyerApi.find(game,post={url,body->
            requested+=url
            when(url) {
                GgselBuyerApi.CATEGORY_QUERY_URL -> JSONObject("""{"data":[{"name":"Stellaris","url":"stellaris"}]}""")
                GgselBuyerApi.CATEGORY_GOODS_URL -> throw UnknownHostException("api4 host unavailable")
                GgselBuyerApi.CATEGORY_GOODS_FALLBACK_URL -> {
                    assertEquals(37770,body.getInt("digi_catalog"))
                    JSONObject("""{"data":{"items":[]}}""")
                }
                GgselBuyerApi.CATEGORY_SEARCH_URL -> {
                    assertEquals("Stellaris",body.getString("search_term"))
                    assertEquals(37770,body.getInt("digi_catalog"))
                    assertEquals("stellaris",body.getString("url"))
                    assertEquals(100,body.getInt("limit"))
                    assertEquals("sortByPriceUp",body.getString("sort"))
                    assertEquals(2,body.getJSONArray("content_type_ids").length())
                    assertEquals("Steam",body.getJSONArray("platforms").getString(0))
                    JSONObject("""{"data":{"items":[]}}""")
                }
                else -> error("Unexpected GGSEL endpoint: " + url)
            }
        },getCategoryPage={"""<script>{"id":12943,"digi_catalog":37770,"name":"Stellaris","url":"stellaris"}</script>"""})
        assertTrue(offers.isEmpty())
        assertEquals(listOf(GgselBuyerApi.CATEGORY_QUERY_URL,GgselBuyerApi.CATEGORY_GOODS_URL,GgselBuyerApi.CATEGORY_GOODS_FALLBACK_URL,GgselBuyerApi.CATEGORY_SEARCH_URL),requested)
    }

    @Test fun searchesExactCategoryWhenCatalogEndpointReturnsNoAcceptedOffers() {
        val game=ManualGameReference.Game("Stellaris",281990)
        val requested=mutableListOf<String>()
        val offers=GgselBuyerApi.find(game,post={url,body->
            requested+=url
            when(url) {
                GgselBuyerApi.CATEGORY_QUERY_URL -> JSONObject("""{"data":[{"name":"Stellaris","url":"stellaris"}]}""")
                GgselBuyerApi.CATEGORY_GOODS_URL -> JSONObject("""{"data":{"items":[]}}""")
                GgselBuyerApi.CATEGORY_SEARCH_URL -> {
                    assertEquals("Stellaris",body.getString("search_term"))
                    assertEquals(37770,body.getInt("digi_catalog"))
                    assertEquals("stellaris",body.getString("url"))
                    JSONObject("""{"data":{"items":[
                        {"id_goods":223344,"url":"stellaris-key","name":"Stellaris Steam Key РФ","forbidden_type":0,"price_for_one_wmr":"499.90","is_preorder":false,"options_check":false,"options":[]},
                        {"id_goods":223345,"name":"Stellaris Steam Key РФ + soundtrack","forbidden_type":0,"price_for_one_wmr":"99","is_preorder":false,"options_check":false,"options":[]},
                        {"id_goods":223346,"name":"Stellaris Steam account","forbidden_type":0,"price_for_one_wmr":"1","is_preorder":false,"options_check":false,"options":[]}
                    ]}}""")
                }
                else -> error("Unexpected GGSEL endpoint: " + url)
            }
        },getCategoryPage={"""<script>{"id":12943,"digi_catalog":37770,"name":"Stellaris","url":"stellaris"}</script>"""})
        assertEquals(1,offers.size)
        assertEquals("ggsel:223344",offers.single().id)
        assertEquals(499.9,offers.single().price,0.001)
        assertEquals(listOf(GgselBuyerApi.CATEGORY_QUERY_URL,GgselBuyerApi.CATEGORY_GOODS_URL,GgselBuyerApi.CATEGORY_SEARCH_URL),requested)
    }

    @Test fun editionSearchMergesCatalogAndEnglishAndLocalizedVariantQueries() {
        val game=ManualGameReference.Game("Example Game",123456,aliases=listOf(
            "Example Game Royal Edition", "Example Game Королевское издание"))
        val searchTerms=mutableListOf<String>()
        val offers=GgselBuyerApi.find(game,post={url,body->
            when(url) {
                GgselBuyerApi.CATEGORY_QUERY_URL -> JSONObject("""{"data":[{"digi_catalog":37770,"name":"Example Game","url":"example-game"}]}""")
                GgselBuyerApi.CATEGORY_GOODS_URL -> JSONObject("""{"data":{"items":[
                    {"id_goods":223300,"name":"Example Game Royal Edition Steam Key РФ","forbidden_type":0,"price_for_one_wmr":"699","is_preorder":false,"options_check":false,"options":[]}
                ]}}""")
                GgselBuyerApi.CATEGORY_SEARCH_URL -> {
                    searchTerms += body.getString("search_term")
                    val id=if(body.getString("search_term").contains("Королевское")) 223302 else 223301
                    val title=if(id==223302) "Example Game Королевское издание Steam Key РФ" else "Example Game Royal Edition Steam Key РФ"
                    JSONObject("""{"data":{"items":[{"id_goods":$id,"name":"$title","forbidden_type":0,"price_for_one_wmr":"599","is_preorder":false,"options_check":false,"options":[]}]}}""")
                }
                else -> error("Unexpected GGSEL endpoint: " + url)
            }
        },editionSearchTerms=listOf("Example Game Royal Edition","Example Game Королевское издание"))

        assertEquals(listOf("Example Game Royal Edition","Example Game Королевское издание"),searchTerms)
        assertEquals(setOf("ggsel:223300","ggsel:223301","ggsel:223302"),offers.map { it.id }.toSet())
    }

    @Test fun fallsBackToQueryWhenApi4Returns404() {
        val game=ManualGameReference.Game("Stellaris",281990)
        val requested=mutableListOf<String>()
        val offers=GgselBuyerApi.find(game,post={url,_->
            requested+=url
            when(url) {
                GgselBuyerApi.CATEGORY_QUERY_URL -> JSONObject("""{"data":[{"name":"Stellaris","url":"stellaris"}]}""")
                GgselBuyerApi.CATEGORY_GOODS_URL -> throw java.io.IOException("GGSEL HTTP 404")
                GgselBuyerApi.CATEGORY_GOODS_FALLBACK_URL -> JSONObject("""{"data":{"items":[]}}""")
                GgselBuyerApi.CATEGORY_SEARCH_URL -> JSONObject("""{"data":{"items":[
                    {"id_goods":445566,"url":"stellaris-steam-gift","name":"Stellaris Steam Gift РФ","forbidden_type":0,"price_for_one_wmr":"599","is_preorder":false,"options_check":false,"options":[]}
                ]}}""")
                else -> error("Unexpected GGSEL endpoint: " + url)
            }
        },getCategoryPage={"""<script>{"id":12943,"digi_catalog":37770,"name":"Stellaris","url":"stellaris"}</script>"""})
        assertEquals(1,offers.size)
        assertEquals("ggsel:445566",offers.single().id)
        assertEquals(listOf(GgselBuyerApi.CATEGORY_QUERY_URL,GgselBuyerApi.CATEGORY_GOODS_URL,GgselBuyerApi.CATEGORY_GOODS_FALLBACK_URL,GgselBuyerApi.CATEGORY_SEARCH_URL),requested)
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
