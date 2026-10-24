package ru.igrolov.app

/** Keeps off-screen Steam covers from starting network requests until they approach the viewport. */
object ArtworkViewport {
    fun isNearViewport(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        margin: Float
    ): Boolean {
        if (width <= 0f || height <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) return false
        val prefetch = margin.coerceAtLeast(0f)
        return left + width > -prefetch && left < viewportWidth + prefetch &&
            top + height > -prefetch && top < viewportHeight + prefetch
    }
}
