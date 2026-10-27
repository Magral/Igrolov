package ru.igrolov.app

/** A single concise status for price-check failures; technical diagnostics are not exposed in the UI. */
object MonitorErrorPresentation {
    fun summary(@Suppress("UNUSED_PARAMETER") error: String): String =
        "Не удалось обновить цены. Показаны предыдущие данные."
}
