package ru.igrolov.app

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.assertIsDisplayed

object UiTestScroll {
    fun reveal(compose: ComposeTestRule, text: String, contains: Boolean = false) {
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        if (lists.fetchSemanticsNodes().isNotEmpty()) {
            runCatching { lists.onFirst().performScrollToNode(hasText(text, substring = contains)) }
        }
        repeat(8) {
            val nodes = compose.onAllNodesWithText(text, substring = contains)
            if (nodes.fetchSemanticsNodes().isNotEmpty()) {
                val first = nodes.onFirst()
                runCatching { first.performScrollTo() }
                compose.waitForIdle()
                if (runCatching { first.assertIsDisplayed(); true }.getOrDefault(false)) return
            }
            // Lazy lists expose only composed items. A real swipe advances nested and outer
            // vertical scrollers until the requested card/action enters the viewport.
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.onAllNodesWithText(text, substring = contains).onFirst().assertIsDisplayed()
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

