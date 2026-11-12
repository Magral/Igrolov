package ru.igrolov.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

object SteamRuQuoteStore {
    private val pending=ConcurrentHashMap<String,CompletableFuture<SteamRuQuote>>()
    private val slots=Semaphore(2)
    private fun prefs(context:Context)=context.getSharedPreferences("steam_ru_quotes",Context.MODE_PRIVATE)
    fun read(context:Context,key:String)=runCatching {SteamRuQuotes.decode(prefs(context).getString(key,null))}.getOrNull()
    fun readAll(context:Context)=prefs(context).all.mapNotNull {(key,value)->
        SteamRuQuotes.decode(value as? String)?.let {key to it}
    }.toMap()
    fun lookup(context:Context,appId:Int,canonical:String,packageId:Int=0,edition:String?=null):SteamRuQuote {
        if(packageId<=0 && !edition.isNullOrBlank() && GameSearchResolver.normalized(edition)!=GameSearchResolver.normalized(canonical))
            return SteamRuQuote(edition=edition,failedAt=System.currentTimeMillis())
        val key=SteamRuQuotes.key(appId,packageId)
        val old=read(context,key)
        val now=System.currentTimeMillis()
        old?.takeIf {it.checkedAt>0 && it.failedAt<=it.checkedAt && now-it.checkedAt in 0..300_000L &&
            (it.scope.isNotBlank() || it.edition==canonical)}?.let {return it}
        val future=CompletableFuture<SteamRuQuote>()
        pending.putIfAbsent(key,future)?.let {return it.get()}
        try {
            val acquired=slots.tryAcquire(2,java.util.concurrent.TimeUnit.SECONDS)
            val quote=if(!acquired)(old ?: SteamRuQuote()).copy(failedAt=System.currentTimeMillis()) else try {
                runCatching {SteamRuQuotes.fetch(appId,canonical,packageId,edition,System.currentTimeMillis(),::get)}
                    .getOrElse {(old ?: SteamRuQuote()).copy(failedAt=System.currentTimeMillis())}
            } finally {slots.release()}
            runCatching {save(context,key,quote)}
            future.complete(quote)
            return quote
        } catch(e:Exception) {future.completeExceptionally(e);throw e}
        finally {pending.remove(key,future)}
    }
    @Synchronized private fun save(context:Context,key:String,quote:SteamRuQuote) {
        val keep=(readAll(context)+(key to quote)).entries.sortedByDescending {maxOf(it.value.checkedAt,it.value.failedAt)}.take(200).map {it.key}.toSet()
        val editor=prefs(context).edit().putString(key,SteamRuQuotes.encode(quote))
        prefs(context).all.keys.filterNot {it in keep}.forEach {editor.remove(it)}
        editor.apply()
    }
    private fun get(url:String):JSONObject {
        val c=URL(url).openConnection() as HttpURLConnection
        c.connectTimeout=4000;c.readTimeout=4000
        c.setRequestProperty("User-Agent","Igrolov Android")
        try {
            if(c.responseCode !in 200..299)error("Steam RU request failed")
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use {it.readText()})
        } finally {c.disconnect()}
    }
}
