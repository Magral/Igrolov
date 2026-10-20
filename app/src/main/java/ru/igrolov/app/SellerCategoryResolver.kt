package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Seller-specific category evidence. Never treat a marketplace category ID as global.
 * Used only during manual search; unavailable/ambiguous metadata leaves the title unchanged.
 */
object SellerCategoryResolver {
    private fun request(path: String): JSONObject? = try {
        val conn = URL("https://api.digiseller.com$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) null
            else JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                .takeIf { it.optInt("retval", -1) == 0 }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }

    /** Digiseller may wrap seller categories in content.category, not root.category. */
    fun categoryArray(root: JSONObject): JSONArray? {
        for (envelope in listOfNotNull(root, root.optJSONObject("content"),
                root.optJSONObject("result"), root.optJSONObject("data"))) {
            for (key in listOf("category", "categories", "items")) {
                envelope.optJSONArray(key)?.let { return it }
            }
        }
        return null
    }

    fun parseCategoryNames(root: JSONObject): Map<String, String> {
        if (root.optInt("retval", -1) != 0) return emptyMap()
        val array = categoryArray(root) ?: return emptyMap()
        val names = mutableMapOf<String, String>()
        // Real seller responses contain nested categories in `sub`.
        // Walk iteratively so malformed deep trees cannot overflow the stack.
        val pending = java.util.ArrayDeque<JSONArray>()
        pending.add(array)
        while (pending.isNotEmpty()) {
            val level = pending.removeFirst()
            for (index in 0 until level.length()) {
            val category = level.optJSONObject(index) ?: continue
            category.optJSONArray("sub")?.let { pending.add(it) }
            val id = category.optString("id").ifBlank { category.optString("category_id") }
            val name = category.optString("name").ifBlank { category.optString("title") }.trim()
            if (id.matches(Regex("[0-9]+")) && id != "0" && name.isNotBlank()) {
                // Duplicate category IDs with conflicting names are ambiguous, not proof.
                if (id in names && names[id] != name) names[id] = ""
                else if (id !in names) names[id] = name
            }
        }
        }
        return names.filterValues { it.isNotBlank() }
    }

    private fun categoryNames(sellerId: String): Map<String, String> {
        val root = request("/api/categories?seller_id=$sellerId") ?: return emptyMap()
        return parseCategoryNames(root)
    }

    private fun categoryId(productId: String, sellerId: String): String? {
        val root = request("/api/products/$productId/data?seller_id=$sellerId&currency=RUB&lang=ru-RU")
            ?: return null
        val id = root.optJSONObject("product")?.optString("category_id") ?: return null
        return id.takeIf { it.matches(Regex("[0-9]+")) && it != "0" }
    }

    /** Strict seller-scoped ID match. Zero, missing and cross-seller IDs are not evidence. */
    fun matchCategory(
        sellerId: String,
        productCategoryId: String?,
        categoriesBySeller: Map<String, Map<String, String>>
    ): String? {
        if (!sellerId.matches(Regex("[0-9]{1,12}"))) return null
        val id = productCategoryId ?: return null
        if (!id.matches(Regex("[0-9]+")) || id == "0") return null
        return categoriesBySeller[sellerId]?.get(id)?.takeIf { it.isNotBlank() }
    }

    /** Returns a category only when both seller and product resolve to the same ID. */
    fun label(productId: String, sellerId: String, cache: MutableMap<String, Map<String, String>>): String? {
        if (!productId.matches(Regex("[0-9]{4,12}")) || !sellerId.matches(Regex("[0-9]{1,12}"))) return null
        val id = categoryId(productId, sellerId) ?: return null
        val names = cache.getOrPut(sellerId) { categoryNames(sellerId) }
        return matchCategory(sellerId, id, mapOf(sellerId to names))
    }

    /** Only unambiguous category names may add evidence; broad Steam/Игры cannot. */
    fun kind(category: String): String? {
        val value = category.lowercase(Locale.ROOT).replace('ё', 'е')
        val words = value.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
        if (words.any { it in setOf("аккаунт", "аккаунты", "account", "accounts", "аренда", "rent", "rental") }) return "Excluded"
        // Account/rental products may be labeled as profiles, offline access or shared access.
        if (words.any { it in setOf("акк", "акки", "профиль", "профили", "profile", "profiles",
                "offline", "оффлайн", "офлайн", "прокат") }) return "Excluded"
        val gift = words.any { it in setOf("gift", "gifts", "гифт", "гифты", "подарки", "подарок") }
        val key = words.any { it in setOf("key", "keys", "ключ", "ключи", "ключей") }
        return when {
            gift && !key -> "Gift"
            key && !gift -> "Key"
            else -> null
        }
    }

    fun refine(offer: Offer, category: String): Offer? {
        return when (val categoryKind = kind(category)) {
            "Excluded" -> null
            "Gift", "Key" -> {
                // A conflicting seller title is not proof of delivery type.
                if (offer.type == "Не указан") offer.copy(categoryLabel = category)
                else if (offer.type != categoryKind) offer.copy(type = "Не указан", categoryLabel = category)
                else offer.copy(categoryLabel = category)
            }
            else -> offer.copy(categoryLabel = category)
        }
    }
}
