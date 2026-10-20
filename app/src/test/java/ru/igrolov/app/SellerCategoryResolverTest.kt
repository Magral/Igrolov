package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

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
        for (category in listOf("Аккаунты Steam", "Steam Accounts", "Аренда", "Steam Rental", "Steam Profiles", "Профили Steam", "Оффлайн Steam")) {
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


    @Test fun sellerScopedIdMatch() {
        val categories = mapOf(
            "101" to mapOf("7" to "Steam Gifts", "8" to "Стим Ключи"),
            "202" to mapOf("7" to "Аккаунты Steam")
        )
        assertEquals("Steam Gifts", SellerCategoryResolver.matchCategory("101", "7", categories))
        assertEquals("Аккаунты Steam", SellerCategoryResolver.matchCategory("202", "7", categories))
        assertEquals("Стим Ключи", SellerCategoryResolver.matchCategory("101", "8", categories))
        assertNull(SellerCategoryResolver.matchCategory("303", "7", categories))
        assertNull(SellerCategoryResolver.matchCategory("202", "8", categories))
    }

    @Test fun missingOrZeroCategoryNeverClassifies() {
        val categories = mapOf("101" to mapOf("0" to "Steam Gifts", "7" to "Steam Gifts"))
        assertNull(SellerCategoryResolver.matchCategory("101", "0", categories))
        assertNull(SellerCategoryResolver.matchCategory("101", null, categories))
        assertNull(SellerCategoryResolver.matchCategory("", "7", categories))
        assertNull(SellerCategoryResolver.matchCategory("abc", "7", categories))
        assertNull(SellerCategoryResolver.matchCategory("101", "invalid", categories))
    }

    @Test fun nestedCategoryResponseMatchesSellerProduct() {
        val response = JSONObject("""{"retval":0,"content":{"category":[
          {"id":7,"name":"Steam Gifts"},
          {"category_id":8,"name":"Стим Ключи"},
          {"id":0,"name":"Unknown"}
        ]}}""")
        val names = SellerCategoryResolver.parseCategoryNames(response)
        assertEquals("Steam Gifts", names["7"])
        assertEquals("Стим Ключи", names["8"])
        assertFalse(names.containsKey("0"))
        assertEquals("Steam Gifts",
            SellerCategoryResolver.matchCategory("101", "7", mapOf("101" to names)))
        assertNull(SellerCategoryResolver.matchCategory("202", "7", mapOf("101" to names)))
    }

    @Test fun categoryApiErrorsDoNotClassify() {
        val error = JSONObject("""{"retval":1,"category":[{"id":7,"name":"Steam Gifts"}]}""")
        assertTrue(SellerCategoryResolver.parseCategoryNames(error).isEmpty())
        assertTrue(SellerCategoryResolver.parseCategoryNames(JSONObject("""{"retval":0}""")).isEmpty())
    }

    @Test fun duplicateCategoryIdsCannotBeTrusted() {
        val response = JSONObject("""{"retval":0,"category":[
            {"id":7,"name":"Steam Gifts"},
            {"id":7,"name":"Аккаунты Steam"},
            {"id":8,"name":"Стим Ключи"}
        ]}""")
        val names = SellerCategoryResolver.parseCategoryNames(response)
        assertFalse(names.containsKey("7"))
        assertEquals("Стим Ключи", names["8"])
    }

    @Test fun sameDuplicateCategoryDoesNotBecomeAmbiguous() {
        val response = JSONObject("""{"retval":0,"category":[
            {"id":7,"name":"Steam Gifts"},
            {"id":7,"name":"Steam Gifts"}
        ]}""")
        assertEquals("Steam Gifts", SellerCategoryResolver.parseCategoryNames(response)["7"])
    }

    @Test fun nestedSellerSubcategoriesAreResolvedWithoutInheritingParentType() {
        val response = JSONObject("""{"retval":0,"category":[
            {"id":1,"name":"Steam","sub":[
                {"id":7,"name":"Steam Gifts"},
                {"id":8,"name":"Аккаунты Steam","sub":[{"id":9,"name":"Other"}]}
            ]}
        ]}""")
        val names = SellerCategoryResolver.parseCategoryNames(response)
        assertEquals("Steam Gifts", names["7"])
        assertEquals("Excluded", SellerCategoryResolver.kind(names.getValue("8")))
        assertNull(SellerCategoryResolver.kind(names.getValue("9")))
        assertNull(SellerCategoryResolver.refine(offer("Key"), names.getValue("8")))
        assertEquals("Steam Gifts",
            SellerCategoryResolver.matchCategory("101", "7", mapOf("101" to names)))
    }

    @Test fun conflictingIdsAcrossTreeLevelsRemainUntrusted() {
        val response = JSONObject("""{"retval":0,"category":[
            {"id":7,"name":"Steam Gifts","sub":[{"id":7,"name":"Аккаунты Steam"}]},
            {"id":8,"name":"Steam Keys"}
        ]}""")
        val names = SellerCategoryResolver.parseCategoryNames(response)
        assertFalse(names.containsKey("7"))
        assertEquals("Steam Keys", names["8"])
    }

    @Test fun noCategoryMeansNoChange() {
        val original = offer("Key")
        assertEquals("Key", SellerCategoryResolver.refine(original, "Steam")?.type)
        assertEquals("Steam", SellerCategoryResolver.refine(original, "Steam")?.categoryLabel)
    }
    @Test fun singleObjectSubcategoryIsSupported() {
        val root=JSONObject("""{"retval":0,"category":[{"id":1,"name":"Steam","sub":{"id":7,"name":"Steam Gifts"}}]}""")
        assertEquals("Steam Gifts",SellerCategoryResolver.parseCategoryNames(root)["7"])
    }
}

