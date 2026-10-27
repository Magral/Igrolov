package ru.igrolov.app

/** Short user-facing explanations for noisy network exceptions in price monitoring cards. */
object MonitorErrorPresentation {
    fun summary(error: String): String {
        val normalized = error.lowercase()
        val cause = when {
            "http 404" in normalized -> "Площадка не нашла категорию товара (HTTP 404)."
            "ssl" in normalized || "bad_decrypt" in normalized ||
                "decryption_failed" in normalized || "bad_record_mac" in normalized ->
                "Временный сбой защищённого соединения с площадкой (SSL)."
            "timed out" in normalized || "timeout" in normalized ->
                "Площадка не ответила вовремя."
            else -> "Не удалось обновить цены на площадке."
        }
        return "$cause Показаны предыдущие цены."
    }
}
