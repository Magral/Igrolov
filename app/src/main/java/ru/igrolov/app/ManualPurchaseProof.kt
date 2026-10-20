package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** A catalog teaser price is never used as a verified, purchasable offer. Shared by search, tracked cards and automatic monitoring. */
object ManualPurchaseProof {
    data class Plan(val pairs: List<String>, val labels: List<String>, val valid: Boolean, val gameSelected: Boolean = false, val russiaSelected:Boolean=false)
    data class Proof(val price: Double?, val available: Boolean, val label: String, val gameSelected: Boolean = false, val russiaSelected:Boolean=false)
    private fun rows(value: Any?): List<JSONObject> = when(value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is JSONObject -> rows(value.opt("option") ?: value.opt("variant") ?: JSONArray().put(value))
        else -> emptyList()
    }
    fun options(product: JSONObject)=rows(product.opt("options"))
    private fun normalized(value: String)=OfferRules.normalize(value).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    fun plan(product: JSONObject, canonical: String): Plan {
        val pairs=mutableListOf<String>(); val labels=mutableListOf<String>(); var gameSelected=false; var russiaSelected=false
        for(option in options(product)) {
            val type=option.optString("type").lowercase()
            val variants=rows(option.opt("variants"))
            if(type in setOf("text","textarea") && variants.isEmpty()) continue // e.g. buyer profile URL, no price choice
            if(variants.isEmpty()) return Plan(pairs,labels,false)
            val question=option.optString("label").ifBlank { option.optString("name") }.lowercase()
            val visible=variants.filter { it.optInt("visible",1)==1 }
            val selected=when {
                listOf("стран","регион","country","region").any { it in question } -> visible.filter {
                    normalized(it.optString("text")) in setOf("россия","рф","russia","russian federation","ru","россия рф")
                }.singleOrNull()
                listOf("издани","верси","edition","игр","game","продукт","product").any { it in question } -> visible.filter {
                    normalized(it.optString("text")) in setOf(normalized(canonical), normalized("$canonical Standard Edition"),"standard","standard edition","стандартное издание","базовая игра")
                }.singleOrNull()
                else -> null
            } ?: return Plan(pairs,labels,false)
            // Hidden stock is not proof of availability for a separately delivered variant.
            if(option.optInt("separate_content")==1 && (!selected.has("num_in_stock") || selected.optInt("num_in_stock")<=0)) return Plan(pairs,labels,false)
            val id=option.optString("id").ifBlank { option.optString("name").substringAfterLast('_') }
            val value=selected.optString("value")
            if(!id.matches(Regex("[1-9][0-9]*")) || !value.matches(Regex("[1-9][0-9]*"))) return Plan(pairs,labels,false)
            pairs.add("$id:$value");labels.add(selected.optString("text"))
            if(listOf("стран","регион","country","region").any {it in question}) russiaSelected=true
            if (normalized(selected.optString("text")) in setOf(normalized(canonical),normalized("$canonical Standard Edition"),"standard","standard edition","стандартное издание","базовая игра")) gameSelected=true
        }
        return Plan(pairs,labels,true,gameSelected,russiaSelected)
    }
    fun inspect(product: JSONObject?, canonical: String, request: (String)->JSONObject?): Proof {
        if(product==null || product.optInt("is_available",-1)!=1) return Proof(null,false,"Наличие не подтверждено")
        if(product.optString("collection")=="unit" || product.optInt("pwyw")==1 || product.optInt("options_check")==1) return Proof(null,true,"Цена требует проверки продавцом")
        val plan=plan(product,canonical)
        if(!plan.valid) return Proof(null,true,"Вариант и цена не подтверждены")
        if(plan.pairs.isEmpty()) {
            // Some sellers advertise an edition selector but omit its data in the public card.
            val title=product.optString("name")
            if(OfferRules.variablePrice(title) || Regex("(?iu)\\bвыбор\\b").containsMatchIn(title)) return Proof(null,true,"Выбор варианта не представлен в API")
            val price=product.optJSONObject("prices")?.optJSONObject("default")?.optDouble("RUB",Double.NaN)
                ?.takeIf { it.isFinite() && it>0 }
            return Proof(price,true,if(price==null) "Нет актуальной цены RUB" else "Актуальная цена карточки · RUB")
        }
        val id=product.optString("id")
        if(!id.matches(Regex("[1-9][0-9]{3,11}"))) return Proof(null,true,"Некорректный ID карточки")
        val params=plan.pairs.joinToString("") { "&options%5B%5D=${URLEncoder.encode(it,"UTF-8")}" }
        val root=runCatching { request("/api/products/price/calc?product_id=$id&currency=RUB&count=1$params") }.getOrNull()
        if(root?.optInt("retval",-1)!=0) return Proof(null,false,"Вариант недоступен или цена не подтверждена")
        val data=root.optJSONObject("data") ?: return Proof(null,false,"Нет расчёта цены")
        val currency=data.optString("currency").uppercase()
        val amount=data.optDouble("amount",Double.NaN)
        val commission=data.optDouble("commission",Double.NaN)
        val count=data.optDouble("count",Double.NaN)
        val price=(amount+commission).takeIf { currency=="RUB" && count==1.0 && commission.isFinite() && commission>=0 && it.isFinite() && it>0 }
        return Proof(price,price!=null,"Вариант: ${plan.labels.joinToString(" · ")} · расчёт Digiseller RUB",plan.gameSelected,plan.russiaSelected)
    }
}
