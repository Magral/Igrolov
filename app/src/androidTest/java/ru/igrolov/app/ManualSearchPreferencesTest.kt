package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Methods run in separate instrumentation processes to prove disk persistence. */
@RunWith(AndroidJUnit4::class)
class ManualSearchPreferencesTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun open():UiDevice {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Игролов 1.0.7")),15000))
        return device
    }
    @Test fun saveFiltersFromScreen() {
        ManualSearchSettings.save(context,ManualSearchFilters())
        val device=open()
        device.findObject(By.text("Key")).click()
        device.findObject(By.text("Только подтверждённая РФ")).click()
        val scroll=UiScrollable(UiSelector().scrollable(true))
        assertTrue(scroll.scrollTextIntoView("Цена до, ₽"))
        val fields=device.findObjects(By.clazz("android.widget.EditText"))
        // Price is the last visible text field; the query field may have scrolled away.
        fields.last().text="2500"
        assertTrue(scroll.scrollTextIntoView("DLC"))
        device.findObject(By.text("DLC")).click()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(ManualSearchFilters("Key","DLC","2500",true),ManualSearchSettings.read(context))
        ManualSearchSettings.rememberGame(context,GameSearchResolver.Candidate(281990,"Stellaris"))
    }
    @Test fun verifyFiltersAfterRestart() {
        val expected=ManualSearchFilters("Key","DLC","2500",true)
        assertEquals(expected,ManualSearchSettings.read(context))
        assertTrue(ManualSearchSettings.games(context).any {it.appId==281990})
        val device=open()
        val scroll=UiScrollable(UiSelector().scrollable(true))
        assertTrue(scroll.scrollTextIntoView("2500"))
        assertTrue(device.hasObject(By.text("2500")))
        assertEquals(expected,ManualSearchSettings.read(context))
        ManualSearchSettings.save(context,ManualSearchFilters())
    }
}
