package ru.igrolov.app

import java.net.URI
import org.json.JSONObject

/** A displayed price can open only the product that actually supplied that price. */
object OfferProductLink {
    fun forProduct(marketplace:String,id:String,url:String):String {
        val supplied=validated(marketplace,url)
        if(supplied.isNotBlank())return supplied
        return if(marketplace=="Plati" && id.matches(Regex("[0-9]{4,12}"))) "https://plati.market/itm/$id" else ""
    }
    fun forOffer(offer:Offer)=forProduct(offer.marketplace,offer.id,offer.productUrl)
    fun validated(marketplace:String,url:String):String = runCatching {
        val uri=URI(url.trim())
        if(uri.scheme!="https" || uri.userInfo!=null || (uri.port!=-1 && uri.port!=443))return ""
        val host=uri.host?.lowercase() ?: return ""
        val path=uri.path.orEmpty()
        val product=when(marketplace) {
            "Plati" -> host in setOf("plati.market","www.plati.market","plati.ru","www.plati.ru") && path.startsWith("/itm/") && path.length>5
            "GGSEL" -> host in setOf("ggsel.net","www.ggsel.net") && Regex("^/(?:[a-z]{2}/)?catalog/product/[^/]+/?$").matches(path)
            else -> false
        }
        if(product)uri.toASCIIString() else ""
    }.getOrDefault("")

    fun monitorMinimum(watch:JSONObject):String {
        val price=watch.optDouble("last",Double.NaN)
        if(!price.isFinite() || price<=0)return ""
        val rows=watch.optJSONArray("topOffers") ?: return ""
        val bestId=watch.optString("bestId")
        val offer=(0 until rows.length()).mapNotNull {rows.optJSONObject(it)}.firstOrNull {
            it.optDouble("price",Double.NaN)==price && (bestId.isBlank() || it.optString("id")==bestId)
        } ?: return ""
        return forProduct(offer.optString("marketplace","Plati"),offer.optString("id"),offer.optString("url"))
    }
}
