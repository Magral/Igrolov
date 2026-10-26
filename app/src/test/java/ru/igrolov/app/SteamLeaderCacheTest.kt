package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamLeaderCacheTest {
    @Test fun freshEntryRoundTripsSteamMetadataAndRegionalEvidence() {
        val original = SteamTopSeller(
            appId = 281990,
            title = "Stellaris",
            steamPrice = "399 руб.",
            steamInitialPrice = "1 599 руб.",
            discountPercent = 75,
            availability = SteamRuAvailability.AVAILABLE,
            headerImageUrl = "https://cdn.example/cover.jpg",
            description = "Strategy game",
            features = SteamGameFeatures(
                SteamRussianLocalization.SUPPORTED,
                SteamControllerSupport.PARTIAL
            ),
            genres = listOf("Стратегии", "Симуляторы")
        )

        assertEquals(original, SteamLeaderCache.decode(
            SteamLeaderCache.encode(original, 10_000L),
            10_001L
        ))
    }

    @Test fun expiredOrFutureDatedEntriesAreIgnored() {
        val encoded = SteamLeaderCache.encode(
            SteamTopSeller(1, "Game", availability = SteamRuAvailability.UNAVAILABLE),
            10_000L
        )

        assertNull(SteamLeaderCache.decode(encoded, 10_000L + SteamLeaderCache.TTL_MILLIS + 1L))
        assertNull(SteamLeaderCache.decode(encoded, 9_999L))
    }

    @Test fun unknownRegionalStatusIsNeverReusedAsVerifiedCacheData() {
        val encoded = SteamLeaderCache.encode(
            SteamTopSeller(1, "Game", availability = SteamRuAvailability.UNKNOWN),
            10_000L
        )

        assertNull(SteamLeaderCache.decode(encoded, 10_001L))
    }

    @Test fun malformedEntriesAreIgnored() {
        assertNull(SteamLeaderCache.decode("not json", 10_001L))
        assertNull(SteamLeaderCache.decode("""{"checkedAt":10000,"appId":0}""", 10_001L))
    }
}
