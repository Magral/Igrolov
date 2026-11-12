package ru.igrolov.app

data class OfferComparisonProof(val scope:String="",val deliveryType:String="")

object OfferComparison {
    fun scope(appId:Int,canonical:String,edition:String?=null):String =
        if(edition.isNullOrBlank() || GameSearchResolver.normalized(edition)==GameSearchResolver.normalized(canonical)) "base:$appId"
        else "edition:$appId:${GameSearchResolver.normalized(edition)}"

    /** Unknown title suffixes cannot silently become proof of a standard edition. */
    fun baseTitle(title:String,canonical:String):Boolean {
        fun words(s:String)=GameSearchResolver.normalized(s).split(Regex("[^\\p{L}\\p{N}]+" )).filter {it.isNotBlank()}
        val titleWords=words(title)
        val game=words(canonical)
        if(game.isEmpty())return false
        val at=titleWords.windowed(game.size).indexOfFirst {it==game}
        if(at<0)return false
        val allowed=setOf("steam","key","keys","gift","gifts","ключ","ключи","гифт","подарок","pc","пк","ru","rus","рф","россия","россии","для","снг","cis","global","worldwide","region","free","активация","активации","код","cd","cdkey","standard","стандартное","стандарт","edition","издание")
        return (titleWords.take(at)+titleWords.drop(at+game.size)).all {it in allowed}
    }
    fun proof(offer:Offer,appId:Int,canonical:String,edition:String?=null):OfferComparisonProof {
        if(appId<=0 || !offer.priceVerified || offer.available!=true || offer.type !in setOf("Gift","Key"))return OfferComparisonProof()
        val identity=if(!edition.isNullOrBlank() && ManualContentRules.matchesSelectedSteamEdition(offer.name,canonical,edition))scope(appId,canonical,edition)
            else if(edition.isNullOrBlank() && baseTitle(offer.name,canonical))scope(appId,canonical) else ""
        return OfferComparisonProof(identity,offer.type)
    }
}
