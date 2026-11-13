package ru.igrolov.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ArtworkLifecycleUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun iconUrl() = "android.resource://${context.packageName}/${R.drawable.ic_launcher}"
    private fun clearArtworkCache() {
        context.getSharedPreferences("steam_artwork_urls", 0).edit().clear().commit()
    }

    @Test fun missingImageRecoversThroughFallback() {
        clearArtworkCache()
        val fallbackUsed = AtomicBoolean()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        SteamArtworkImage("file:///igrolov-missing-artwork-recovery.jpg",
                            Modifier.size(90.dp), 91001,
                            fallbackUrls = { _, _ ->
                                fallbackUsed.set(true)
                                listOf(iconUrl())
                            }, communityUrls = { emptyList() })
                    }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("Обложка Steam 91001 загружена")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(fallbackUsed.get())
            compose.onNodeWithContentDescription("Обложка Steam 91001 загружена").assertIsDisplayed()
        }
    }

    @Test fun changingGameCancelsOldFallbackAndLoadsCurrentCover() {
        clearArtworkCache()
        val fallbackStarted = CountDownLatch(1)
        val releaseFallback = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val identity = mutableStateOf(91003)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme {
                            val appId = identity.value
                            SteamArtworkImage(
                                if (appId == 91003) "file:///igrolov-missing-artwork-cancellation.jpg" else iconUrl(),
                                Modifier.size(90.dp), appId,
                                fallbackUrls = { _, _ ->
                                    fallbackStarted.countDown()
                                    try {
                                        releaseFallback.await()
                                    } catch (e: InterruptedException) {
                                        interrupted.set(true)
                                        throw e
                                    }
                                    listOf("file:///igrolov-obsolete-artwork.jpg")
                                }, communityUrls = { emptyList() })
                        }
                    }
                }
                compose.waitUntil(15_000) { fallbackStarted.count == 0L }
                compose.runOnIdle { identity.value = 91004 }
                compose.waitUntil(15_000) { interrupted.get() }
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithContentDescription("Обложка Steam 91004 загружена")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                releaseFallback.countDown()
                compose.waitForIdle()
                compose.onNodeWithContentDescription("Обложка Steam 91004 загружена").assertIsDisplayed()
            }
        } finally {
            releaseFallback.countDown()
        }
    }
}
