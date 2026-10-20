package ru.igrolov.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Read-only catalog calls. Strict mode distinguishes a failed check from an absent product. */
object CatalogApi {
    fun get(path:String,strict:Boolean=false):JSONObject? {
        var failure:Exception?=null
        repeat(2) { attempt ->
            if(Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            var connection:HttpURLConnection?=null
            try {
                connection=URL("https://api.digiseller.com$path").openConnection() as HttpURLConnection
                connection.connectTimeout=3000;connection.readTimeout=5000
                connection.setRequestProperty("Accept","application/json")
                val status=connection.responseCode
                if(status in 200..299) return JSONObject(connection.inputStream.bufferedReader().use {it.readText()})
                if(status==404 || status==410) return null
                failure=IOException("HTTP $status")
                if(status!=429 && status !in 500..599) {
                    if(strict) throw failure!! else return null
                }
            } catch(e:Exception) {failure=e} finally {connection?.disconnect()}
            if(attempt==0 && !Thread.currentThread().isInterrupted) Thread.sleep(200)
        }
        if(strict) throw failure ?: IOException("Нет ответа каталога")
        return null
    }
}
