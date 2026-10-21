package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject

/** Buyer-facing GGSEL catalog parsing. Seller API and seller credentials are not used. */
object GgselCatalog {
    data class Category(val id: Long, val name: String, val url: String)

    private fun objects(root: JSONObject): Sequence<JSONObject> = sequence {
        val pending = ArrayDeque<Any>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 20_000) {
            when (val value = pending.removeLast()) {
                is JSONObject -> {
                    yield(value)
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val child = value.opt(keys.next())
                        if (child is JSONObject || child is JSONArray) pending.add(child)
                    }
                }
                is JSONArray -> for (index in value.length() - 1 downTo 0) {
                    val child = value.opt(index)
                    if (child is JSONObject || child is JSONArray) pending.add(child)
                }
            }
        }
    }

    fun categories(root: JSONObject): List<Category> = objects(root).mapNotNull { item ->
        val id = item.optLong("digi_catalog", 0L)
        val name = item.optString("name").ifBlank { item.optString("title") }.trim()
        val url = item.optString("url").trim()
        if (id < 0 || name.isBlank() || !url.matches(Regex("[A-Za-z0-9_-]{1,160}"))) null
        else Category(id, name, url)
    }.distinctBy { it.url }.toList()

    /** Read the category ID embedded in the public catalog page's Next.js bootstrap data. */
    /** Resolve the current category ID from embedded page data, validating the exact category slug and name. */
    fun categoryIdFromPageHtml(html: String, expectedName: String, expectedUrl: String): Long {
        fun matches(name: String, url: String, id: Long): Boolean =
            id > 0L &&
                GameSearchResolver.normalized(name) == GameSearchResolver.normalized(expectedName) &&
                url == expectedUrl

        val nextData = Regex(
            """(?is)<script\b(?=[^>]*\bid=["']__NEXT_DATA__["'])[^>]*>(.*?)</script\s*>"""
        ).find(html)?.groupValues?.getOrNull(1)
        if (nextData != null) {
            val category = runCatching {
                JSONObject(nextData.trim())
                    .optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optJSONObject("category")
            }.getOrNull()
            if (category != null) {
                val id = category.optLong("digi_catalog", 0L)
                if (matches(category.optString("name"), category.optString("url"), id)) return id
            }
        }

        // Current GGSEL pages serialize category data in the App Router hydration stream.
        val categoryObject = Regex(
            """(?is)\{[^{}]{0,5000}?"digi_catalog"\s*:\s*"?(\d+)"?[^{}]{0,2000}?"name"\s*:\s*"([^"\\]+)"[^{}]{0,1000}?"url"\s*:\s*"([^"\\]+)"[^{}]{0,5000}?\}"""
        )
        for (candidate in categoryObject.findAll(html)) {
            val id = candidate.groupValues[1].toLongOrNull() ?: continue
            val name = candidate.groupValues[2]
            val url = candidate.groupValues[3]
            if (matches(name, url, id)) return id
        }
        return 0L
    }

    /** Only a category matching the canonical Steam title or a saved title alias is accepted. */
    fun chooseCategory(categories: List<Category>, names: List<String>): Category? {
        val expected = names.map(GameSearchResolver::normalized).filter { it.isNotBlank() }.toSet()
        return categories.firstOrNull { GameSearchResolver.normalized(it.name) in expected }
    }

    private fun price(item: JSONObject): Double? {
        val raw = item.optString("price_for_one_wmr")
            .ifBlank { item.optString("price_wmr") }
            .ifBlank { item.optString("price") }
        return raw.replace(" ", "").replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * Listing rows are accepted only when the public catalog marks them as active, non-preorder,
     * fixed-price goods. Options are excluded because their final variant price is not yet proven.
     */
    fun offers(root: JSONObject, game: ManualGameReference.Game): List<Offer> {
        val gameName = game.canonical
        val identityNames = listOf(gameName) + game.aliases
        val accepted = linkedMapOf<String, Offer>()
        for (item in objects(root)) {
            if (!item.has("id_goods")) continue
            val productId = item.optString("id_goods")
            val title = item.optString("name").trim()
            if (!productId.matches(Regex("[0-9]{4,12}")) || title.isBlank()) continue
            if (!item.has("forbidden_type") || item.optInt("forbidden_type", -1) != 0) continue
            if (item.optBoolean("is_preorder") || item.optBoolean("options_check") ||
                (item.optJSONArray("options")?.length() ?: 0) > 0) continue
            val matchingName = identityNames.firstOrNull { ManualOfferClassifier.titleType(title, it) != null } ?: continue
            val type = ManualOfferClassifier.titleType(title, matchingName) ?: continue
            if (type !in setOf("Gift", "Key")) continue
            val edition = ManualContentRules.classify(title, "", game)
            if (edition != "Игра") continue
            val rf = OfferRules.rfStatus(title)
            if (rf == "РФ исключена") continue
            val amount = price(item) ?: continue
            val path = item.optString("url").trim()
            val url = if (path.matches(Regex("[A-Za-z0-9_-]{1,180}")))
                "https://ggsel.net/en/catalog/product/$path" else "https://ggsel.net/en/catalog/product/$productId"
            accepted.putIfAbsent(productId, Offer(
                id = "ggsel:$productId",
                name = title,
                price = amount,
                type = type,
                rfStatus = rf,
                edition = edition,
                categoryLabel = "GGSEL · активное предложение · цена за 1 шт.",
                detailsChecked = true,
                available = true,
                priceVerified = true,
                canonical = gameName,
                steamAppId = game.appId,
                marketplace = "GGSEL",
                productUrl = url
            ))
        }
        return accepted.values.sortedBy { it.price }
    }
}
