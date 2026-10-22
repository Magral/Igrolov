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
        // Accessibility scrolling dispatches to Compose's list semantics.
        // It never generates touch events in an editor or keyboard.
        fun scroll(forward: Boolean) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: throw AssertionError("Нет окна приложения")
            val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
                if (node.isScrollable && node.className?.toString() != "android.widget.EditText") nodes.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it) }
            }
            collect(root)
            val list = nodes.maxByOrNull { node ->
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                bounds.width().toLong() * bounds.height()
            } ?: throw AssertionError("Нет списка для прокрутки к: $text")
            list.performAction(if (forward) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            android.os.SystemClock.sleep(700)
            device.waitForIdle()
        }
        if (visible()) return
        repeat(5) { scroll(false) }
        repeat(20) {
            if (visible()) return
            scroll(true)
        }
        if (!visible()) throw AssertionError("Не найден видимый элемент: $text")
    }
}
