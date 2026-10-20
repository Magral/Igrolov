package ru.igrolov.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Shared by search and price monitoring. All candidates are checked, using at most four concurrent requests and a per-search seller cache. */
class ManualCatalogEnricher(private val request: (String) -> JSONObject? = { CatalogApi.get(it) }) {
    fun enrich(offers: List<Offer>, sellerIds: Map<String, String>, active: () -> Boolean,
               reference: ManualGameReference.Game? = null,
               progress: (Int, Int) -> Unit = { _, _ -> }): List<Offer> {
        if (offers.isEmpty() || !active()) return emptyList()
        val sellers = ConcurrentHashMap<String, Map<String, String>>()
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val results = arrayOfNulls<Offer>(offers.size)
        val workers = Executors.newFixedThreadPool(minOf(4, offers.size))
        try {
            val futures = workers.invokeAll((0 until minOf(4, offers.size)).map {
                Callable {
                    while (active() && !Thread.currentThread().isInterrupted) {
                        val index = next.getAndIncrement()
                        if (index >= offers.size) break
                        val offer = offers[index]
                        val seller = sellerIds[offer.id].orEmpty()
                        val validSeller = seller.matches(Regex("[1-9][0-9]{0,11}"))
                        val suffix = if (validSeller) "&seller_id=$seller" else ""
                        val root = runCatching { request("/api/products/${offer.id}/data?currency=RUB&lang=ru-RU$suffix") }.getOrNull()
                        val product = root?.takeIf { it.optInt("retval", -1) == 0 }?.optJSONObject("product")
                            ?.takeIf { it.optString("id") == offer.id }
                        var category: String? = null
                        val categoryId = product?.optString("category_id")
                        // Seller identity returned in the card must agree with the search result.
                        val actualSeller = product?.optJSONObject("seller")?.optString("id")
                        if (active() && validSeller && actualSeller == seller && categoryId?.matches(Regex("[1-9][0-9]*")) == true) {
                            val names = sellers.computeIfAbsent(seller) {
                                runCatching { request("/api/categories?seller_id=$seller") }.getOrNull()?.let(SellerCategoryResolver::parseCategoryNames) ?: emptyMap()
                            }
                            category = SellerCategoryResolver.matchCategory(seller, categoryId, mapOf(seller to names))
                        }
                        if (active()) {
                            val refined = ManualOfferClassifier.refine(offer, product, category)
                            if (refined != null) {
                                val canonical = reference?.canonical ?: offer.name
                                val proof = ManualPurchaseProof.inspect(product, canonical, request)
                                var content = reference?.let { ManualContentRules.classify(product?.optString("name")?.ifBlank { offer.name } ?: offer.name,
                                    listOf(product?.optString("info"), product?.optString("add_info")).joinToString("\n"), it) } ?: refined.edition
                                if(content == "Не подтверждено" && proof.price != null && proof.gameSelected) content="Игра"
                                val rfStatus = if (proof.russiaSelected && refined.rfStatus != "РФ исключена") "РФ выбрана в параметрах" else refined.rfStatus
                                results[index] = refined.copy(price = proof.price ?: refined.price,
                                    available = proof.available, priceVerified = proof.price != null,
                                    variablePrice = proof.price == null, purchaseNote = proof.label, edition = content, rfStatus = rfStatus)
                            }
                            progress(done.incrementAndGet(), offers.size)
                        }
                    }
                }
            })
            futures.forEach { it.get() }
        } finally { workers.shutdownNow() }
        return if (active()) results.filterNotNull().sortedBy { it.price } else emptyList()
    }
}
