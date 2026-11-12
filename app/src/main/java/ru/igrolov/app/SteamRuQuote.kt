package ru.igrolov.app

import org.json.JSONObject
import java.util.Locale

data class SteamRuQuote(val price:Double?=null,val available:Boolean?=null,val scope:String="",
    val edition:String="",val checkedAt:Long=0,val failedAt:Long=0) {
    val label:String get()=price?.let {if(it==0.0) "Бесплатно" else String.format(Locale("ru","RU"),"%.2f ₽",it)}.orEmpty()
}

object SteamRuQuotes {
    fun key(appId:Int,packageId:Int=0)="$appId:$packageId"
    fun fetch(appId:Int,canonical:String,packageId:Int=0,edition:String?=null,now:Long,
        get:(String)->JSONObject):SteamRuQuote {
        require(appId>0)
        val root=get("https://store.steampowered.com/api/appdetails?appids=$appId&cc=ru&l=russian")
        val entry=root.optJSONObject(appId.toString()) ?: error("Steam RU response missing")
        if(!entry.has("success"))error("Steam RU response incomplete")
        if(!entry.optBoolean("success"))return SteamRuQuote(available=false,checkedAt=now)
        val data=entry.optJSONObject("data") ?: error("Steam RU data missing")
        if(data.optString("type")!="game")error("Steam RU game not confirmed")
        val groups=data.optJSONArray("package_groups")
        val subs=buildList<JSONObject> {
            if(groups!=null)for(i in 0 until groups.length()) {
                val rows=groups.optJSONObject(i)?.optJSONArray("subs") ?: continue
                for(j in 0 until rows.length())rows.optJSONObject(j)?.let {add(it)}
            }
        }
        fun packageQuote(id:Int,expected:String?,baseRequired:Boolean):SteamRuQuote? {
            val detail=get("https://store.steampowered.com/api/packagedetails?packageids=$id&cc=ru&l=russian")
                .optJSONObject(id.toString())?.takeIf {it.optBoolean("success")}?.optJSONObject("data") ?: return null
            val apps=detail.optJSONArray("apps") ?: return null
            if((0 until apps.length()).none {apps.optJSONObject(it)?.optInt("id")==appId})return null
            val name=detail.optString("name")
            if(baseRequired && GameSearchResolver.normalized(name)!=GameSearchResolver.normalized(canonical))return null
            val price=detail.optJSONObject("price") ?: return null
            if(price.optString("currency")!="RUB")return null
            val cents=price.optLong("final",-1)
            if(cents<0)return null
            return SteamRuQuote(cents/100.0,true,if(!baseRequired && expected.isNullOrBlank()) "" else OfferComparison.scope(appId,canonical,expected),expected ?: canonical,now)
        }
        val overview=data.optJSONObject("price_overview")
        if(packageId>0) {
            val sub=subs.firstOrNull {it.optInt("packageid")==packageId} ?: return SteamRuQuote(available=false,edition=edition.orEmpty(),checkedAt=now)
            val cents=sub.optLong("price_in_cents_with_discount",-1)
            if(cents>=0 && overview?.optString("currency")=="RUB")
                return SteamRuQuote(cents/100.0,true,if(edition.isNullOrBlank()) "" else OfferComparison.scope(appId,canonical,edition),edition.orEmpty(),now)
            return packageQuote(packageId,edition,false) ?: error("Steam RU edition price not confirmed")
        }
        if(data.optBoolean("is_free"))return SteamRuQuote(0.0,true,OfferComparison.scope(appId,canonical),canonical,now)
        val candidate=subs.firstOrNull()?.optInt("packageid")?.takeIf {it>0}
        if(candidate!=null)runCatching {packageQuote(candidate,null,true)}.getOrNull()?.let {return it}
        if(overview!=null && overview.optString("currency")!="RUB")error("Steam RU currency not confirmed")
        val cents=overview?.optLong("final",-1) ?: -1
        // The app's price can be displayed, but is not proof of a particular edition.
        return if(cents>=0)SteamRuQuote(cents/100.0,true,edition=canonical,checkedAt=now)
            else SteamRuQuote(available=false,checkedAt=now)
    }
    fun encode(q:SteamRuQuote)=JSONObject().put("price",q.price ?: JSONObject.NULL).put("available",q.available ?: JSONObject.NULL)
        .put("scope",q.scope).put("edition",q.edition).put("checked",q.checkedAt).put("failed",q.failedAt).toString()
    fun decode(raw:String?):SteamRuQuote?=runCatching {
        val j=JSONObject(raw ?: return null)
        SteamRuQuote(j.optDouble("price",Double.NaN).takeIf {it.isFinite() && it>=0},
            if(j.isNull("available"))null else j.optBoolean("available"),j.optString("scope").take(180),
            j.optString("edition").take(180),j.optLong("checked").coerceAtLeast(0),j.optLong("failed").coerceAtLeast(0))
    }.getOrNull()
}
