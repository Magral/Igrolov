package ru.igrolov.app

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice

/** Scroll the containing list, never the scrollable editor inside that list. */
object UiTestScroll {
    fun reveal(device: UiDevice, text: String, contains: Boolean = false) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { activity ->
                activity.currentFocus?.let { focus ->
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(focus.windowToken, 0)
                    focus.clearFocus()
                }
            }
        }
        device.waitForIdle()
        val selector = if (contains) By.textContains(text) else By.text(text)
        if (device.hasObject(selector)) return
        fun container() = device.findObjects(By.scrollable(true))
            .filter { it.className != "android.widget.EditText" }
            .maxByOrNull { it.visibleBounds.height().toLong() * it.visibleBounds.width() }
        for (attempt in 0 until 8) {
            val scroll = container() ?: break
            if (!scroll.scroll(Direction.UP, 1f)) break
        }
        repeat(20) {
            if (device.hasObject(selector)) return
            val scroll = container() ?: throw AssertionError("Нет списка для прокрутки к: $text")
            scroll.scroll(Direction.DOWN, 0.7f)
        }
        if (!device.hasObject(selector)) throw AssertionError("Не найден элемент: $text")
    }
}
