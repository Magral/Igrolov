package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class SteamReviewsTest {
    @Test fun currentApiReadsSummaryInsteadOfOneSampledReview() {
        val result = SteamReviews.parse("""{"response":{"query_summary":{"total_positive":92,"total_reviews":100},"reviews":[{"voted_up":false}]}}""")!!
        assertEquals(92, result.percent)
        assertEquals(SteamReviewTone.POSITIVE, result.tone)
    }
    @Test fun legacyFallbackUsesTheSameTotals() {
        assertEquals(41, SteamReviews.parse("""{"success":1,"query_summary":{"total_positive":41,"total_reviews":100}}""")?.percent)
    }
    @Test fun missingMalformedOrFailedResultsNeverProduceAZeroPercentRating() {
        for (json in listOf("invalid", "{}", """{"success":0,"query_summary":{"total_positive":0,"total_reviews":100}}""",
            """{"response":{"query_summary":{"total_positive":20,"total_reviews":10}}}""",
            """{"response":{"query_summary":{"total_positive":-1,"total_reviews":10}}}""")) {
            assertNull(SteamReviews.parse(json))
        }
        assertNull(SteamReviews.parse("""{"response":{"query_summary":{"total_positive":0,"total_reviews":0}}}""")!!.percent)
    }
    @Test fun thresholdHasDistinctHigherLowerAndExactSixtyStates() {
        assertEquals(SteamReviewTone.POSITIVE, SteamReviewSummary(61, 100).tone)
        assertEquals(SteamReviewTone.NEGATIVE, SteamReviewSummary(59, 100).tone)
        assertEquals(SteamReviewTone.NEUTRAL, SteamReviewSummary(60, 100).tone)
        assertEquals(0, SteamReviewSummary(0, 100).percent)
    }
    @Test fun requestSelectsAllLanguagesAndSteamPurchasesWithoutDateFilter() {
        val query = SteamReviews.requestUrl(281990).substringAfter("input_json=")
        val input = JSONObject(URLDecoder.decode(query, "UTF-8"))
        assertEquals(281990, input.getInt("appid"))
        assertEquals("all", input.getJSONArray("languages").getString(0))
        assertEquals(0, input.getInt("purchase_type"))
        assertEquals(0, input.getInt("review_type"))
        assertFalse(input.has("date_range_start"))
        assertFalse(input.has("date_range_end"))
    }
}
