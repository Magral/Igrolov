package ru.igrolov.app

import android.content.Context

object MonitorBackgroundStore {
    fun preferences(context:Context)=context.getSharedPreferences("background_monitor_status",Context.MODE_PRIVATE)
    fun read(context:Context)=runCatching {MonitorBackgroundReport.decode(preferences(context).getString("state",null))}
        .getOrDefault(BackgroundMonitorReport())
    @Synchronized fun begin(context:Context,token:String,now:Long) {
        save(context,MonitorBackgroundReport.begin(read(context),token,now))
    }
    @Synchronized fun finish(context:Context,token:String,now:Long,failed:Boolean,interrupted:Boolean=false) {
        val before=read(context)
        val after=MonitorBackgroundReport.finish(before,token,now,failed,interrupted)
        if(after!=before)save(context,after)
    }
    private fun save(context:Context,report:BackgroundMonitorReport) {
        // A status-write failure must never abort price verification.
        runCatching {preferences(context).edit().putString("state",MonitorBackgroundReport.encode(report)).commit()}
    }
}
