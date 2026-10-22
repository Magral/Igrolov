package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchGameIdentityTest {
    @Test fun sameAppIdMatchesRenamedGameButDifferentKnownIdsNeverMatch() {
        val entry = JSONObject().put("name", "Old title").put("steamAppId", 281990)
        assertTrue(WatchGameIdentity.matches(entry, 281990, "New title"))
        assertFalse(WatchGameIdentity.matches(entry, 12345, "Old title"))
    }
    @Test fun legacyWatchFallsBackToTrimmedCaseInsensitiveName() {
        val entry = JSONObject().put("name", " Stellaris ")
        assertTrue(WatchGameIdentity.matches(entry, 281990, "stellaris"))
        assertFalse(WatchGameIdentity.matches(entry, 281990, "Another game"))
    }
}
