package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class SteamEditionReferenceTest {
    @Test fun discoversOnlyPurchasePackagesContainingSelectedGame() {
        val appId = 1771300
        val json = """{"1771300":{"success":true,"data":{"type":"game","name":"Kingdom Come: Deliverance II","package_groups":[{"subs":[{"packageid":635513,"option_text":"Kingdom Come: Deliverance II - $59.99"},{"packageid":1109733,"option_text":"Kingdom Come: Deliverance II Royal Edition - $79.99"},{"packageid":1200000,"option_text":"DLC only - $9.99"}]}]}}}"""
        val options = SteamEditionReference.parse(appId, "KCD II", json, mapOf(
            635513 to setOf(appId),
            1109733 to setOf(appId, 3119920),
            1200000 to setOf(3119920)
        ))
        assertEquals(2, options.size)
        assertTrue(options.first().isBase)
        assertEquals("Kingdom Come: Deliverance II", options.first().name)
        assertEquals("Kingdom Come: Deliverance II Royal Edition", options[1].name)
        assertFalse(options[1].isBase)
    }

    @Test fun separateEditionMatcherDoesNotMixBaseRoyalAndDlc() {
        val base = "Kingdom Come: Deliverance II"
        assertTrue(ManualContentRules.matchesSelectedSteamEdition("Kingdom Come: Deliverance II Steam Key РФ", base, base))
        assertFalse(ManualContentRules.matchesSelectedSteamEdition("Kingdom Come: Deliverance II Royal Edition Steam Key РФ", base, base, listOf("$base Royal Edition")))
        assertFalse(ManualContentRules.matchesSelectedSteamEdition("Kingdom Come: Deliverance II Anniversary Edition Steam Key РФ", base, base, listOf("$base Anniversary Edition")))
        assertTrue(ManualContentRules.matchesSelectedSteamEdition("Kingdom Come: Deliverance II Royal Edition Steam Key РФ", base, "$base Royal Edition"))
        assertFalse(ManualContentRules.matchesSelectedSteamEdition("Kingdom Come: Deliverance II From the Ashes DLC Steam Key РФ", base, "$base Royal Edition"))
    }

    @Test fun selectedVerifiedPackageNameConfirmsUnknownEditionSuffixOnlyForThatSearch() {
        val base = "Example Game"
        val royal = "$base Anniversary Edition"
        val game = ManualGameReference.Game(base, 123, aliases = listOf(royal))
        assertEquals("Игра", ManualContentRules.classify("Example Game Anniversary Edition Steam Key РФ", "", game))
        assertNotEquals("Игра", ManualContentRules.classify("Example Game Anniversary Edition Steam Key РФ", "", ManualGameReference.Game(base, 123)))
    }
}
