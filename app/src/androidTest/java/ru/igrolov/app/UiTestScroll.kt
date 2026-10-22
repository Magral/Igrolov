package ru.igrolov.app

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
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
        fun container() = device.findObjects(By.scrollable(true))
            .filter { it.className != "android.widget.EditText" }
            .maxByOrNull { it.visibleBounds.height().toLong() * it.visibleBounds.width() }
        fun visible(): Boolean {
            val bounds = device.findObject(selector)?.visibleBounds ?: return false
            val list = container()?.visibleBounds ?: return false
            return bounds.width() > 0 && bounds.height() > 20 &&
                bounds.centerY() > list.top + 30 && bounds.centerY() < list.bottom - 60
        }
        // The outer gutter belongs to the list and never intersects an editor.
        // Centered gestures can focus a TextField and continue over the IME.
        fun swipe(down: Boolean) {
            val list = container()?.visibleBounds ?: throw AssertionError("Нет списка для прокрутки к: $text")
            val x = list.left + 12
            val top = list.top + 100
            val bottom = list.bottom - 100
            val middle = top + (bottom - top) / 3
            if (down) device.swipe(x, bottom, x, middle, 35)
            else device.swipe(x, top, x, bottom, 35)
            device.waitForIdle()
        }
        if (visible()) return
        repeat(5) { swipe(false) }
        repeat(20) {
            if (visible()) return
            swipe(true)
        }
        if (!visible()) throw AssertionError("Не найден видимый элемент: $text")
    }
}
