package ru.igrolov.app

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Periodic WorkManager IDs survive across cycles; retries need a token for this cycle only. */
object MonitorRunToken {
    @Synchronized fun get(context:Context,id:String,attempt:Int):String {
        val prefs=context.getSharedPreferences("monitor_run_tokens",Context.MODE_PRIVATE)
        val old=runCatching {JSONObject(prefs.getString("tokens","{}") ?: "{}")}.getOrDefault(JSONObject())
        val token=if(attempt==0 || !old.has(id)) "$id:${UUID.randomUUID()}" else old.getString(id)
        val retained=JSONObject().put(id,token)
        old.keys().asSequence().filter {it!=id}.take(7).forEach {retained.put(it,old.getString(it))}
        prefs.edit().putString("tokens",retained.toString()).commit()
        return token
    }
}
