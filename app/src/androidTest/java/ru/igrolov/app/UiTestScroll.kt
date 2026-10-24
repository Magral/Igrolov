package ru.igrolov.app

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo

object UiTestScroll {
    fun reveal(compose: ComposeTestRule, text: String, contains: Boolean = false) {
        compose.onAllNodesWithText(text, substring = contains).onFirst().performScrollTo()
        compose.waitForIdle()
    }

    /** Pump Compose frames while waiting for external accessibility observations. */
    fun await(compose: ComposeTestRule, timeout: Long, condition: () -> Boolean): Boolean =
        try {
            compose.waitUntil(timeoutMillis = timeout, condition = condition)
            true
        } catch (_: ComposeTimeoutException) {
            false
        }
}
