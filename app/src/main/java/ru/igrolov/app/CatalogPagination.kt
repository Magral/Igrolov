package ru.igrolov.app

import org.json.JSONObject

/** Explicit pagination wins; without it, an empty/short page ends the catalogue. */
object CatalogPagination {
    fun hasNext(root:JSONObject,page:Int,pageSize:Int=100):Boolean {
        val result=root.optJSONObject("result")
        val rows=root.optJSONObject("items")?.optJSONArray("item")
            ?: root.optJSONArray("items") ?: result?.optJSONArray("items")
        if(rows==null || rows.length()==0)return false
        val totalPages=result?.optInt("total_pages",0)?.takeIf {it>0}
        return if(totalPages!=null)page<totalPages else rows.length()>=pageSize
    }
}
