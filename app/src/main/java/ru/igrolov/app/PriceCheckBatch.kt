package ru.igrolov.app

data class PriceCheckTarget(val appId: Int, val title: String, val profileId: String? = null)
data class PriceCheckBatch(
    val pending: List<PriceCheckTarget> = emptyList(), val total: Int = 0,
    val completed: Int = 0, val cancelled: Boolean = false,
    val filters: ManualSearchFilters = ManualSearchFilters(), val pages: Int = 1,
    val active:List<PriceCheckTarget> = emptyList(),
    val runId:String = java.util.UUID.randomUUID().toString()
) {
    val current: PriceCheckTarget? get() = active.firstOrNull() ?: pending.firstOrNull()
    val running: Boolean get() = pending.isNotEmpty() && !cancelled
    val ready:List<PriceCheckTarget> get() = if(!running)emptyList() else
        pending.filterNot {it in active}.take((2-active.size).coerceAtLeast(0))
    fun claim():PriceCheckBatch = copy(active=active+ready)
    fun advance(expected: PriceCheckTarget): PriceCheckBatch =
        if (!running || expected !in active) this else copy(pending = pending-expected,active=active-expected, completed = completed + 1)
    fun cancel(): PriceCheckBatch = copy(pending = emptyList(),active=emptyList(), cancelled = true)
    companion object {
        fun start(targets: List<PriceCheckTarget>, filters: ManualSearchFilters = ManualSearchFilters(), pages: Int = 1): PriceCheckBatch {
            val selected = targets.filter { it.appId > 0 && it.title.isNotBlank() }.distinctBy { it.appId to it.profileId }.take(10)
            return PriceCheckBatch(selected, selected.size, filters = filters, pages = pages)
        }
    }
}

