package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MonitorEditionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun royalEditionCanBeSelectedAndRunningCheckCanBeStopped() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val title = "Kingdom Come: Deliverance II"
        val royal = "$title Royal Edition"
        URL.setURLStreamHandlerFactory { protocol -> if (protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream(): ByteArrayInputStream {
                    val json = when {
                        url.path.contains("storesearch") -> """{"items":[{"id":1771300,"name":"$title"}]}"""
                        url.path.contains("appdetails") -> """{"1771300":{"success":true,"data":{"type":"game","name":"$title","dlc":[],"package_groups":[{"subs":[{"packageid":990001},{"packageid":990002}]}]}}}"""
                        url.path.contains("packagedetails") -> {
                            val id = if (url.query.contains("990002")) 990002 else 990001
                            val name = if (id == 990002) royal else title
                            """{"$id":{"success":true,"data":{"name":"$name","apps":[{"id":1771300}]}}}"""
                        }
                        url.path.endsWith("search2") -> {
                            started.countDown()
                            release.await(25, TimeUnit.SECONDS)
                            """{"items":{"item":[]}}"""
                        }
                        else -> """{"data":{"categories":[]},"items":[]}"""
                    }
                    return ByteArrayInputStream(json.toByteArray())
                }
            }
        } }
        val context = ApplicationProvider.getApplicationContext<Context>()
        androidx.work.WorkManager.getInstance(context).cancelAllWork().result.get()
        WatchStore.write(context, JSONArray())
        try {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            compose.waitUntil(15000) { compose.onAllNodesWithText("Мониторинг").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Мониторинг").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("page-list").performScrollToIndex(0)
            compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(title)
            compose.waitUntil(15000) { compose.onAllNodes(hasText(title) and hasClickAction() and hasSetTextAction().not()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasText(title) and hasClickAction() and hasSetTextAction().not()).onLast()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("monitor-edition") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("monitor-edition").performClick()
            compose.onNodeWithText(royal).performClick()
            compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("2000")
            UiTestScroll.reveal(compose, "Отслеживать игру")
            compose.onNodeWithText("Отслеживать игру").performClick()
            compose.waitUntil(10000) { WatchStore.read(context).length() == 1 }
            val watch = WatchStore.read(context).getJSONObject(0)
            assertEquals(1771300, watch.getInt("steamAppId"))
            assertEquals(990002, watch.getInt("steamPackageId"))
            assertEquals(royal, watch.getString("steamEditionName"))
            assertTrue("Price check did not start", started.await(15, TimeUnit.SECONDS))
            compose.waitUntil(10000) { compose.onAllNodesWithText("Остановить").fetchSemanticsNodes().isNotEmpty() }
            UiTestScroll.reveal(compose, "Остановить")
            compose.onNodeWithText("Остановить").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("Проверка остановлена. Сохранённые цены не изменены.").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(-1.0, WatchStore.read(context).getJSONObject(0).getDouble("last"), 0.0)
        } finally {
            release.countDown()
            WatchScheduler.stop(context)
            WatchStore.write(context, JSONArray())
            androidx.work.WorkManager.getInstance(context).cancelAllWork().result.get()
        }
    }
}
