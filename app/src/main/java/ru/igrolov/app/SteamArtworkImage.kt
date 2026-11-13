package ru.igrolov.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Each artwork identity owns its fallback jobs, retry counters, and request state. */
@Composable
internal fun SteamArtworkImage(
    url: String, modifier: Modifier = Modifier, appId: Int? = null,
    fallbackUrls: (Int, Boolean) -> List<String>, communityUrls: (Int) -> List<String>
) {
    key(url, appId) {
        SteamArtworkContent(url, modifier, appId, fallbackUrls, communityUrls)
    }
}

@Composable
private fun SteamArtworkContent(
    url: String, modifier: Modifier, appId: Int?,
    fallbackUrls: (Int, Boolean) -> List<String>, communityUrls: (Int) -> List<String>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val artworkPrefs = remember(context) { context.getSharedPreferences("steam_artwork_urls", Context.MODE_PRIVATE) }
    val requestedPortrait = remember(url) { SteamArtworkPresentation.isPortrait(url) }
    val artworkCacheKey = remember(appId, requestedPortrait) {
        appId?.let { SteamArtworkPresentation.cacheKey(it, requestedPortrait) }
    }
    LaunchedEffect(artworkCacheKey, requestedPortrait) {
        artworkCacheKey?.let { key ->
            val cached = artworkPrefs.getString(key, null)
            if (!cached.isNullOrBlank() && !SteamArtworkPresentation.matchesAspect(cached, requestedPortrait)) {
                artworkPrefs.edit().remove(key).apply()
            }
        }
    }
    val initialCandidates = remember(url, appId, artworkCacheKey) {
        val cachedRequested = artworkCacheKey?.let { artworkPrefs.getString(it, null) }
            ?.takeIf { it.isNotBlank() && SteamArtworkPresentation.matchesAspect(it, requestedPortrait) }
        listOfNotNull(cachedRequested, url.takeIf { SteamArtworkPresentation.matchesAspect(it, requestedPortrait) }).distinct()
    }
    var candidates by remember(url, appId) { mutableStateOf(initialCandidates) }
    var candidateIndex by remember(url, appId) { mutableIntStateOf(0) }
    var shouldLoad by remember(url, appId) { mutableStateOf(true) }
    var imageLoaded by remember(url, appId) { mutableStateOf(false) }
    var imageFailed by remember(url, appId) { mutableStateOf(false) }
    var communityLoaded by remember(url, appId) { mutableStateOf(false) }
    var retryCount by remember(url, appId) { mutableIntStateOf(0) }
    var imageUrl by remember(url, appId) { mutableStateOf(initialCandidates.firstOrNull().orEmpty()) }
    var fallbackCandidatesLoaded by remember(url, appId) { mutableStateOf(false) }
    val displayedPortrait = remember(imageUrl) { SteamArtworkPresentation.isPortrait(imageUrl) }
    val displayedCacheKey = remember(appId, displayedPortrait) {
        appId?.let { SteamArtworkPresentation.cacheKey(it, displayedPortrait) }
    }

    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text("🎮", style = MaterialTheme.typography.bodySmall)
        if (shouldLoad && imageUrl.isNotBlank()) {
            val imageReferer = if (imageUrl.contains("steamgriddb.com", ignoreCase = true)) {
                "https://www.steamgriddb.com/"
            } else {
                "https://store.steampowered.com/"
            }
            // Keep one request for the same URL across price/progress recompositions.
            val request = remember(context, imageUrl, imageReferer) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .httpHeaders(NetworkHeaders.Builder()
                        .set("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36 Igrolov/1.5.7")
                        .set("Referer", imageReferer)
                        .set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                        .build())
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = if (imageLoaded) "Обложка Steam $appId загружена" else "Обложка Steam $appId загружается",
                contentScale = if (displayedPortrait) androidx.compose.ui.layout.ContentScale.Crop
                    else androidx.compose.ui.layout.ContentScale.Fit,
                modifier = modifier,
                onSuccess = {
                    android.util.Log.i("SteamArtwork", "decoded app=$appId url=$imageUrl")
                    SteamArtworkDiagnostics.record(appId, imageUrl)
                    imageFailed = false
                    imageLoaded = true
                    displayedCacheKey?.let { artworkPrefs.edit().putString(it, imageUrl).apply() }
                },
                onError = {
                    android.util.Log.w("SteamArtwork", "failed app=$appId url=$imageUrl", it.result.throwable)
                    SteamArtworkDiagnostics.record(appId, imageUrl, it.result.throwable)
                    imageFailed = true
                    imageLoaded = false
                    displayedCacheKey?.let { artworkPrefs.edit().remove(it).apply() }
                    val nextIndex = candidateIndex + 1
                    if (nextIndex < candidates.size) {
                        candidateIndex = nextIndex
                        imageUrl = candidates[nextIndex]
                    } else if (!fallbackCandidatesLoaded && appId != null && appId > 0) {
                        fallbackCandidatesLoaded = true
                        scope.launch {
                            val additional = runInterruptible(Dispatchers.IO) {
                                fallbackUrls(appId, requestedPortrait)
                            }
                            val merged = (candidates + additional).distinct()
                            val nextUrl = merged.drop(candidateIndex + 1).firstOrNull()
                            candidates = merged
                            if (nextUrl != null) {
                                candidateIndex = merged.indexOf(nextUrl)
                                imageUrl = nextUrl
                            } else if (!communityLoaded && requestedPortrait) {
                                // Steam may return only missing/obsolete URLs (or none).
                                // Continue to the community portrait source instead of
                                // leaving the card stuck after its first 404.
                                communityLoaded = true
                                val community = runInterruptible(Dispatchers.IO) {
                                    communityUrls(appId)
                                }
                                val withCommunity = (merged + community).distinct()
                                val communityUrl = withCommunity.drop(candidateIndex + 1).firstOrNull()
                                candidates = withCommunity
                                if (communityUrl != null) {
                                    candidateIndex = withCommunity.indexOf(communityUrl)
                                    imageUrl = communityUrl
                                } else if (retryCount < 2 && withCommunity.isNotEmpty()) {
                                    retryCount++
                                    kotlinx.coroutines.delay(2000L * retryCount)
                                    fallbackCandidatesLoaded = false
                                    communityLoaded = false
                                    candidateIndex = 0
                                    imageUrl = withCommunity.first()
                                }
                            }
                        }
                    } else if (!communityLoaded && requestedPortrait && appId != null && appId > 0) {
                        communityLoaded = true
                        scope.launch {
                            val additional = runInterruptible(Dispatchers.IO) {
                                communityUrls(appId)
                            }
                            val merged = (candidates + additional).distinct()
                            val nextUrl = merged.drop(candidateIndex + 1).firstOrNull()
                            candidates = merged
                            if (nextUrl != null) {
                                candidateIndex = merged.indexOf(nextUrl)
                                imageUrl = nextUrl
                            } else if (retryCount < 2) {
                                retryCount++
                                kotlinx.coroutines.delay(2000L * retryCount)
                                fallbackCandidatesLoaded = false
                                communityLoaded = false
                                candidateIndex = 0
                                imageUrl = candidates.first()
                            }
                        }
                    } else if (retryCount < 2) {
                        retryCount++
                        scope.launch {
                            kotlinx.coroutines.delay(2000L * retryCount)
                            fallbackCandidatesLoaded = false
                            communityLoaded = false
                            candidateIndex = 0
                            imageUrl = candidates.first()
                        }
                    }
                }
            )
        }
    }
}
