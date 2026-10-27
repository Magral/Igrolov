package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorErrorPresentationTest {
    @Test fun allTechnicalFailuresUseOnePlainLanguageMessage() {
        val expected = "Не удалось обновить цены. Показаны предыдущие данные."
        listOf(
            "Read error: SSL BAD_DECRYPT DECRYPTION_FAILED_OR_BAD_RECORD_MAC",
            "GGSEL category page HTTP 404",
            "Socket timed out",
            "Unexpected response"
        ).forEach { error -> assertEquals(expected, MonitorErrorPresentation.summary(error)) }
    }
}
