package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class GameSearchResolverTest {
    @Test fun typosRussianAliasesRomanNumeralsAndAbbreviationsKeepIdentity() {
        for((query,id) in listOf("Stelaris" to 281990,"Стелларис" to 281990,"Цивилизация 6" to 289070,"Civ6" to 289070,"Civilization VI" to 289070,"Симс 4" to 1222670,"CS2" to 730)) {
            assertEquals(query,id,GameSearchResolver.rank(query,GameSearchResolver.seeds).first().appId)
        }
        assertEquals(8930,GameSearchResolver.exact("Civilization 5",GameSearchResolver.seeds)!!.appId)
        assertEquals(949230,GameSearchResolver.exact("Cities Skylines II",GameSearchResolver.seeds)!!.appId)
        assertEquals(0.0,GameSearchResolver.score("Civilization 6",GameSearchResolver.seeds.first {it.appId==8930}),0.0)
    }
    @Test fun wrongKeyboardLayoutCanAskSteamUsingCorrectedText() {
        assertTrue(GameSearchResolver.queries("ыеуддфкшы").contains("stellaris"))
    }
    @Test fun ambiguousNamesRequireAnExplicitSelectionAndKeepAppIdsDistinct() {
        val games=listOf(GameSearchResolver.Candidate(1,"Game"),GameSearchResolver.Candidate(2,"Game"))
        assertNull(GameSearchResolver.exact("Game",games))
        assertEquals(2,GameSearchResolver.rank("Game",games).size)
        assertNull(GameSearchResolver.exact("Stelaris",GameSearchResolver.seeds))
    }
    @Test fun staleSuggestionsAreCancelledAndNetworkFailurePreservesLocalAliases() {
        var active=true
        assertTrue(GameSearchResolver.suggest("Stellaris",emptyList(),{active}) {active=false;emptyList()}.isEmpty())
        assertEquals(281990,GameSearchResolver.suggest("Стелларис",emptyList(),{true}) {throw java.io.IOException()}.first().appId)
    }
    @Test fun aliasesDoNotTurnSequelsOrNamedAddonsIntoBaseGames() {
        val game=ManualGameReference.Game("Cities: Skylines",255710,aliases=GameSearchResolver.aliases(255710,"Cities: Skylines"))
        assertNotEquals("Игра",ManualContentRules.classify("Cities Skylines 2 Steam Key РФ","",game))
        assertNotEquals("Игра",ManualContentRules.classify("Cities Skylines Snowfall Steam Key РФ","",game))
        assertEquals("Игра",ManualContentRules.classify("Cities Skylines Steam Key РФ","",game))
        val civ=ManualGameReference.Game("Sid Meier's Civilization VI",289070,aliases=GameSearchResolver.aliases(289070,"Sid Meier's Civilization VI"))
        assertEquals("Игра",ManualContentRules.classify("Civilization 6 Steam Key РФ","",civ))
        assertNotEquals("Игра",ManualContentRules.classify("Civilization VII Steam Key РФ","",civ))
    }
}
