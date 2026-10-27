package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorErrorPresentationTest {
    @Test fun explainsOpenSslRecordErrorsWithoutDumpingCipherDiagnostics() {
        assertEquals(
            "Временный сбой защищённого соединения с площадкой (SSL). Показаны предыдущие цены.",
            MonitorErrorPresentation.summary("Read error: Failure in SSL library BAD_DECRYPT DECRYPTION_FAILED_OR_BAD_RECORD_MAC")
        )
    }

    @Test fun explainsMissingSellerCategoryConcisely() {
        assertEquals(
            "Площадка не нашла категорию товара (HTTP 404). Показаны предыдущие цены.",
            MonitorErrorPresentation.summary("GGSEL category page HTTP 404")
        )
    }

    @Test fun explainsTimeoutsAndUnknownFailures() {
        assertEquals(
            "Площадка не ответила вовремя. Показаны предыдущие цены.",
            MonitorErrorPresentation.summary("Socket timed out")
        )
        assertEquals(
            "Не удалось обновить цены на площадке. Показаны предыдущие цены.",
            MonitorErrorPresentation.summary("Unexpected response")
        )
    }
}
