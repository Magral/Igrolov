package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Methods run in separate instrumentation processes to prove disk persistence. */
@RunWith(AndroidJUnit4::class)
class ManualSearchPreferencesTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun open(): UiDevice {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        assertTrue(UiTestScroll.await(compose, 15000) {
            device.hasObject(By.text("Игролов 1.2.8"))
        })
        return device
    }

    @Test fun saveFiltersFromScreen() {
        ManualSearchSettings.save(context, ManualSearchFilters())
        open()

        compose.onNodeWithText("Key").performClick()
        compose.onNodeWithText("Только подтверждённая РФ").performClick()
        UiTestScroll.reveal(compose, "Цена до, ₽")
        compose.onAllNodes(hasSetTextAction()).get(1).performTextReplacement("2500")
        UiTestScroll.reveal(compose, "DLC")
        compose.onNodeWithText("DLC").performClick()
        compose.waitForIdle()

        assertEquals(
            ManualSearchFilters("Key", "DLC", "2500", true),
            ManualSearchSettings.read(context)
        )
        ManualSearchSettings.rememberGame(context, GameSearchResolver.Candidate(281990, "Stellaris"))
    }

    @Test fun verifyFiltersAfterRestart() {
        val expected = ManualSearchFilters("Key", "DLC", "2500", true)
        assertEquals(expected, ManualSearchSettings.read(context))
        open()
        UiTestScroll.reveal(compose, "Цена до, ₽")
        compose.onAllNodes(hasSetTextAction()).get(1)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("2500")))
        compose.onNodeWithText("Цена до, ₽").assertIsDisplayed()
        assertTrue(ManualSearchSettings.games(context).any { it.appId == 281990 })
        assertEquals(expected, ManualSearchSettings.read(context))
        ManualSearchSettings.save(context, ManualSearchFilters())
    }
}
