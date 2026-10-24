package ru.igrolov.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkViewportTest {
    @Test fun loadsVisibleAndNearbyImagesButSkipsFarOffscreenItems() {
        assertTrue(ArtworkViewport.isNearViewport(20f, 100f, 160f, 90f, 400f, 800f, 220f))
        assertTrue(ArtworkViewport.isNearViewport(20f, 950f, 160f, 90f, 400f, 800f, 220f))
        assertTrue(ArtworkViewport.isNearViewport(20f, -120f, 160f, 90f, 400f, 800f, 220f))
        assertFalse(ArtworkViewport.isNearViewport(20f, 1200f, 160f, 90f, 400f, 800f, 220f))
        assertFalse(ArtworkViewport.isNearViewport(450f, 100f, 160f, 90f, 400f, 800f, 0f))
    }

    @Test fun doesNotLoadBeforeViewportHasMeasured() {
        assertFalse(ArtworkViewport.isNearViewport(0f, 0f, 160f, 90f, 0f, 800f, 220f))
        assertFalse(ArtworkViewport.isNearViewport(0f, 0f, 0f, 90f, 400f, 800f, 220f))
    }
}
