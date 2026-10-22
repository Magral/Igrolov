package ru.igrolov.app

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo

/** Scroll through Compose semantics without stale accessibility node IDs. */
object UiTestScroll {
    fun reveal(compose: ComposeTestRule, text: String, contains: Boolean = false) {
        compose.onNodeWithText(text, substring = contains).performScrollTo()
        compose.waitForIdle()
    }
}
