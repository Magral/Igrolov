package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class CatalogAliasResolverTest {
    @Test fun usesDistinctiveColonPrefixAndSuggestsOnlyGameListingNames() {
        assertEquals("ZERO PARADES", CatalogAliasResolver.fallbackQuery("ZERO PARADES: Director's Cut"))
        assertEquals("Baldur's Gate 3", CatalogAliasResolver.fallbackQuery("Baldur's Gate 3 Deluxe Edition"))
        assertNull(CatalogAliasResolver.fallbackQuery("Hades II"))
        assertEquals(listOf("ZERO PARADES: For Dead Spies"), CatalogAliasResolver.suggestions("ZERO PARADES", listOf(
            "ZERO PARADES: For Dead Spies Steam Ключ Россия + СНГ",
            "ZERO PARADES: For Dead Spies Steam аккаунт",
            "ZERO PARADES: For Dead Spies DLC Steam Key",
            "Other Game Steam Key"
        )))
    }
}
