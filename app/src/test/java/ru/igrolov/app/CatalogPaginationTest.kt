package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogPaginationTest {
    private fun page(size:Int)=JSONObject().put("items",JSONObject().put("item",JSONArray().apply {
        repeat(size) {put(JSONObject().put("id",it))}
    }))
    @Test fun emptyAndShortPagesStopWithoutPaginationButFullPagesContinue() {
        assertFalse(CatalogPagination.hasNext(page(0),1))
        assertFalse(CatalogPagination.hasNext(page(30),1))
        assertTrue(CatalogPagination.hasNext(page(100),1))
    }
    @Test fun explicitPaginationKeepsShortIntermediatePagesButStopsAtEnd() {
        val root=page(30).put("result",JSONObject().put("total_pages",3))
        assertTrue(CatalogPagination.hasNext(root,1))
        assertFalse(CatalogPagination.hasNext(root,3))
        assertFalse(CatalogPagination.hasNext(page(0).put("result",JSONObject().put("total_pages",3)),1))
    }
    @Test fun alternativeResponseShapesAndInvalidPageCountsAreSupported() {
        val rows=JSONArray().apply {repeat(100) {put(it)}}
        assertTrue(CatalogPagination.hasNext(JSONObject().put("items",rows),1))
        assertTrue(CatalogPagination.hasNext(JSONObject().put("result",JSONObject().put("items",rows).put("total_pages",0)),1))
        assertFalse(CatalogPagination.hasNext(JSONObject().put("result",JSONObject().put("items",JSONArray())),1))
    }
}
