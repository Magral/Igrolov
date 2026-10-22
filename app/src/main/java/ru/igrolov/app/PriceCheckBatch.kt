package ru.igrolov.app

data class PriceCheckTarget(val appId: Int, val title: String, val profileId: String? = null)
data class PriceCheckBatch(
    val pending: List<PriceCheckTarget> = emptyList(), val total: Int = 0,
    val completed: Int = 0, val cancelled: Boolean = false,
    val filters: ManualSearchFilters = ManualSearchFilters(), val pages: Int = 1
) {
    val current: PriceCheckTarget? get() = pending.firstOrNull()
    val running: Boolean get() = current != null && !cancelled
    fun advance(expected: PriceCheckTarget): PriceCheckBatch =
        if (!running || current != expected) this else copy(pending = pending.drop(1), completed = completed + 1)
    fun cancel(): PriceCheckBatch = copy(pending = emptyList(), cancelled = true)
    companion object {
        fun start(targets: List<PriceCheckTarget>, filters: ManualSearchFilters = ManualSearchFilters(), pages: Int = 1): PriceCheckBatch {
            val selected = targets.filter { it.appId > 0 && it.title.isNotBlank() }.distinctBy { it.appId }.take(10)
            return PriceCheckBatch(selected, selected.size, filters = filters, pages = pages)
        }
    }
}
