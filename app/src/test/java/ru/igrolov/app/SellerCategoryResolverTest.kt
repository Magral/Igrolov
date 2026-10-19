package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class SellerCategoryResolverTest {
    private fun offer(type: String) = Offer(
        "123456", "Hogwarts Legacy Steam", 999.0, type,
        "РФ указана в названии", "Игра"
    )

    @Test fun giftCategory() {
        assertEquals("Gift", SellerCategoryResolver.kind("Steam Gifts"))
        assertEquals("Gift", SellerCategoryResolver.kind("Гифты Steam"))
        assertEquals("Gift", SellerCategoryResolver.refine(offer("Gift"), "Steam Gifts")?.type)
    }

    @Test fun keyCategory() {
        assertEquals("Key", SellerCategoryResolver.kind("Стим Ключи"))
        assertEquals("Key", SellerCategoryResolver.kind("Steam Keys"))
        assertEquals("Key", SellerCategoryResolver.refine(offer("Key"), "Стим Ключи")?.type)
    }

    @Test fun accountsAndRentalExcluded() {
        for (category in listOf("Аккаунты Steam", "Steam Accounts", "Аренда", "Steam Rental")) {
            assertEquals("Excluded", SellerCategoryResolver.kind(category))
            assertNull(SellerCategoryResolver.refine(offer("Gift"), category))
        }
    }

    @Test fun ambiguousCategoriesDoNotChangeType() {
        for (category in listOf("Steam", "Игры", "Steam Gift Keys", "Цифровые товары")) {
            assertNull(SellerCategoryResolver.kind(category))
            assertEquals("Key", SellerCategoryResolver.refine(offer("Key"), category)?.type)
        }
    }

    @Test fun conflictingTitleAndCategoryBecomeUnknown() {
        assertEquals("Не указан", SellerCategoryResolver.refine(offer("Key"), "Steam Gifts")?.type)
        assertEquals("Не указан", SellerCategoryResolver.refine(offer("Gift"), "Стим Ключи")?.type)
    }

    @Test fun categoryCannotPromoteUnknownTitle() {
        assertEquals("Не указан", SellerCategoryResolver.refine(offer("Не указан"), "Steam Gifts")?.type)
        assertEquals("Не указан", SellerCategoryResolver.refine(offer("Не указан"), "Steam Keys")?.type)
    }

    @Test fun categoryEvidencePreserved() {
        val result = SellerCategoryResolver.refine(offer("Gift"), "Steam Gifts")
        assertEquals("Steam Gifts", result?.categoryLabel)
        assertEquals("Gift", result?.type)
    }

    @Test fun noCategoryMeansNoChange() {
        assertEquals(offer("Key"), offer("Key"))
        assertEquals("Key", SellerCategoryResolver.refine(offer("Key"), "Steam")?.type)
    }
}
