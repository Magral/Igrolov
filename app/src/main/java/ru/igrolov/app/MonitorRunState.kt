package ru.igrolov.app

/** Waiting for WorkManager/network and finishing a saved snapshot are not active price requests. */
object MonitorRunState {
    data class Job(val state: String, val done: Int = 0, val total: Int = 0,
                   val complete: Boolean = false, val game: String = "") {
        val finishedSnapshot: Boolean get() = complete || (total > 0 && done >= total)
        val updating: Boolean get() = state == "RUNNING" && !finishedSnapshot
    }
    data class View(val active: Job?, val waiting: Boolean, val finished: Boolean)
    fun reduce(manual: List<Job>, periodic: List<Job>): View {
        val active = (manual + periodic).firstOrNull { it.updating }
        val waiting = active == null && manual.any { it.state in setOf("ENQUEUED", "BLOCKED") && !it.finishedSnapshot }
        val finished = active == null && !waiting && (manual + periodic).any { it.finishedSnapshot || it.state == "SUCCEEDED" }
        return View(active, waiting, finished)
    }
}
