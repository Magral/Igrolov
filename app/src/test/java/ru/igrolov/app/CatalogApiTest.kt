package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogApiTest {
    @Test
    fun missingProductResponseDoesNotFailWholeMarketplace() {
        assertTrue(CatalogRetryPolicy.isMissingProduct(404))
        assertTrue(CatalogRetryPolicy.isMissingProduct(410))
        assertFalse(CatalogRetryPolicy.isMissingProduct(503))
        assertFalse(CatalogRetryPolicy.isSourceTransportFailure("/api/products/123/data", requestFailed = false))
        assertFalse(CatalogRetryPolicy.isSourceTransportFailure("/api/categories?seller_id=456", requestFailed = false))
    }

    @Test
    fun onlyTransportFailureOnProductCheckMarksMarketplaceFailure() {
        assertTrue(CatalogRetryPolicy.isSourceTransportFailure("/api/products/123/data", requestFailed = true))
        assertFalse(CatalogRetryPolicy.isSourceTransportFailure("/api/categories?seller_id=456", requestFailed = true))
    }

    @Test
    fun throttlingHonorsRetryAfterAndUsesLongerBackoff() {
        assertEquals(3_000L, CatalogRetryPolicy.delayMillis(429, "3", attempt = 0))
        assertEquals(1_200L, CatalogRetryPolicy.delayMillis(429, null, attempt = 0))
        assertEquals(2_400L, CatalogRetryPolicy.delayMillis(429, null, attempt = 1))
    }

    @Test
    fun serverErrorsUseExponentialRetryDelay() {
        assertEquals(500L, CatalogRetryPolicy.delayMillis(503, null, attempt = 0))
        assertEquals(1_000L, CatalogRetryPolicy.delayMillis(503, null, attempt = 1))
    }
}
