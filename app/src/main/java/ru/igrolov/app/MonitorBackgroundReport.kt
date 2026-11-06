package ru.igrolov.app

import org.json.JSONObject

data class BackgroundMonitorReport(
    val token:String="",val startedAt:Long=0,val finishedAt:Long=0,
    val lastSuccessAt:Long=0,val status:String="never"
)

/** Records actual periodic executions, never manual checks or registration of a schedule. */
object MonitorBackgroundReport {
    fun begin(previous:BackgroundMonitorReport,token:String,now:Long)=
        previous.copy(token=token,startedAt=now,finishedAt=0,status="running")

    fun finish(previous:BackgroundMonitorReport,token:String,now:Long,failed:Boolean,interrupted:Boolean=false):BackgroundMonitorReport {
        if(previous.token!=token || previous.status!="running")return previous
        return previous.copy(finishedAt=now,lastSuccessAt=if(!failed && !interrupted)now else previous.lastSuccessAt,
            status=if(interrupted) "interrupted" else if(failed) "partial" else "success")
    }
    fun caption(report:BackgroundMonitorReport,now:Long):String = when {
        report.startedAt<=0 -> "Фоновая проверка ещё не выполнялась"
        report.status=="running" && now-report.startedAt<=10*60_000L -> "Выполняется фоновая проверка"
        report.status in setOf("running","interrupted") -> "Фоновая проверка не завершилась · ${PriceFreshness.age(report.startedAt,now)}"
        report.status=="partial" -> "Фон: часть цен не обновлена · ${PriceFreshness.age(report.finishedAt,now)}"
        report.status=="success" -> "Фоновая проверка: ${PriceFreshness.age(report.finishedAt,now)}"
        else -> "Фоновая проверка ещё не выполнялась"
    }
    fun encode(report:BackgroundMonitorReport)=JSONObject().put("token",report.token).put("started",report.startedAt)
        .put("finished",report.finishedAt).put("success",report.lastSuccessAt).put("status",report.status).toString()
    fun decode(raw:String?):BackgroundMonitorReport=runCatching {
        val json=JSONObject(raw ?: "{}")
        val status=json.optString("status").takeIf {it in setOf("running","interrupted","partial","success")} ?: return@runCatching BackgroundMonitorReport()
        if(json.optString("token").isBlank() || json.optLong("started")<=0 ||
            (status!="running" && json.optLong("finished")<=0))return@runCatching BackgroundMonitorReport()
        BackgroundMonitorReport(json.optString("token"),json.optLong("started").coerceAtLeast(0),
            json.optLong("finished").coerceAtLeast(0),json.optLong("success").coerceAtLeast(0),status)
    }.getOrDefault(BackgroundMonitorReport())
}
