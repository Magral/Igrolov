package ru.igrolov.app

import org.json.JSONObject
import java.util.Locale

/** Delivery/account evidence shared by manual search and automatic price monitoring. */
object ManualOfferClassifier {
    private fun text(value: String): String = OfferRules.normalize(value)
        .replace(Regex("(?<=\\p{L})'(?=\\p{L})"), "")
        .replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun has(value: String, phrase: String) = " ${text(value)} ".contains(" ${text(phrase)} ")
    private fun any(value: String, phrases: List<String>): Boolean {
        val normalized = " ${text(value)} "
        return phrases.any { normalized.contains(" ${text(it)} ") }
    }
    private fun withoutNegation(value: String): String {
        var cleaned = text(value)
        for (phrase in listOf("не аккаунт", "не является аккаунтом", "не аренда", "not an account", "not account", "not a rental")) {
            cleaned = " $cleaned ".replace(" $phrase ", " ").trim()
        }
        return cleaned
    }
    fun titleType(name: String, query: String): String? {
        val cleaned = withoutNegation(name)
        if (any(cleaned, listOf("профиль", "профили", "аккаунта", "аккаунтом", "аккаунтов", "акки", "аккаунты", "account", "accounts",
                "аренду", "аренды", "rent", "rental", "offline", "оффлайн", "офлайн", "прокат", "shared", "family sharing"))) return null
        val type = OfferRules.classifySteamGame(cleaned, query) ?: return null
        // A bonus gift bundled with a key is not the delivery method of the game.
        return if (type == "Gift" && !any(cleaned, listOf("gift", "гифт", "подарочная копия"))) "Не указан" else type
    }
    fun excludedDescription(info: String): Boolean = any(withoutNegation(info), listOf(
        "вы получите аккаунт", "вы получаете аккаунт", "вы получите данные аккаунта", "продажа аккаунта",
        "данные для входа в аккаунт", "логин и пароль от аккаунта", "логин пароль", "login password",
        "you will receive an account", "you receive an account", "account credentials",
        "аккаунт в аренду", "аренда аккаунта", "аренда игры", "оффлайн активация", "офлайн активация",
        "offline activation", "offline account", "общий аккаунт", "общий steam аккаунт", "shared account", "семейный доступ",
        "смена региона", "сменой региона", "смену региона", "меняем регион", "мы заходим на ваш аккаунт", "логин и пароль от вашего"
    ))
    fun descriptionType(info: String): String? {
        val gift = any(info, listOf("steam gift", "steam gifts", "в виде подарка", "подарком в steam", "подарок в steam", "стим гифт"))
        val key = any(info, listOf("steam key", "steam keys", "ключ активации", "лицензионный ключ", "ключ для steam", "activation key", "цифровой ключ", "официальный ключ"))
        return when { gift && !key -> "Gift"; key && !gift -> "Key"; else -> null }
    }
    fun refine(offer: Offer, product: JSONObject?, category: String?): Offer? {
        if (product == null) return offer.copy(type = "Не указан", categoryLabel = "Карточка недоступна · способ передачи не подтверждён", detailsChecked = false)
        val actualName = product.optString("name").ifBlank { offer.name }
        // Re-read title evidence from the fresh card, rather than trusting obsolete search metadata.
        val freshType=titleType(actualName,withoutNegation(actualName))
        if(freshType==null && titleType("$actualName Steam",withoutNegation(actualName))==null)return null
        val titleEvidence=if(product.optString("name").isBlank())offer.type else freshType
        val info = listOf(product.optString("info"), product.optString("add_info")).joinToString("\n")
        if (excludedDescription(info)) return null
        val categoryKind = category?.let(SellerCategoryResolver::kind)
        if (categoryKind == "Excluded") return null
        val evidence = listOfNotNull(titleEvidence?.takeIf { it == "Gift" || it == "Key" },
            descriptionType(info), categoryKind?.takeIf { it == "Gift" || it == "Key" }).toSet()
        val resolved = if (evidence.size == 1) evidence.first() else "Не указан"
        val source = when {
            category != null -> "Категория продавца: $category"
            descriptionType(info) != null -> "Способ передачи указан в описании"
            else -> "По названию · категория продавца не задана"
        }
        val descriptionRegion = when {
            any(info, listOf("недоступно для россии", "недоступно в россии", "кроме россии", "без россии", "не активируется в россии", "не для россии")) -> "РФ исключена"
            else -> OfferRules.rfStatus(actualName)
        }
        val variable = product.optJSONArray("options")?.length()?.let { it > 0 } ?: false
        return offer.copy(name=actualName, type = resolved, categoryLabel = source, detailsChecked = true, rfStatus = descriptionRegion,
            variablePrice = variable, available = if (product.has("is_available")) product.optInt("is_available") != 0 else null)
    }
}
