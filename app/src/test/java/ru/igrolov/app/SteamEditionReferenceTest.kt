package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class SteamEditionReferenceTest {
    @Test fun concurrentDiscoverySharesRequestsAndKeepsPackageContents() {
        val appId=987654
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val calling=java.util.concurrent.CountDownLatch(2)
        val requests=AtomicInteger()
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        val fetch:(String)->JSONObject={url->
            requests.incrementAndGet()
            if("cc=us" in url && "appdetails" in url) {
                entered.countDown()
                check(release.await(3,java.util.concurrent.TimeUnit.SECONDS))
            }
            if("packagedetails" in url) JSONObject("""{"876543":{"success":true,"data":{"name":"Example Game Deluxe","apps":[{"id":987654,"name":"Example Game"},{"id":987655,"name":"Expansion"}]}}}""")
            else JSONObject("""{"987654":{"success":true,"data":{"type":"game","name":"Example Game","package_groups":[{"subs":[{"packageid":876543}]}]}}}""")
        }
        try {
            val futures=(1..2).map {executor.submit<List<SteamEditionReference.Edition>> {
                calling.countDown()
                SteamEditionReference.discoverCached(appId,fetch)
            }}
            assertTrue(calling.await(3,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            val first=futures[0].get(3,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(first,futures[1].get(3,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(3,requests.get())
            assertEquals(listOf("Example Game","Expansion"),first.single().contents)
            assertEquals(first,SteamEditionReference.discoverCached(appId) {error("Cache must avoid network")})
        } finally {release.countDown();executor.shutdownNow()}
    }
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

    @Test fun liveDiscoveryLooksUpManyPackagesInParallel() {
        val appId = 321
        val packageIds = (1001..1040).toList()
        val subs = packageIds.joinToString(",") { id ->
            "{\"packageid\":$id,\"option_text\":\"Example Game Edition $id\"}"
        }
        val appJson = JSONObject("""{"321":{"success":true,"data":{"type":"game","name":"Example Game","package_groups":[{"subs":[$subs]}]}}}""")
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val options = SteamEditionReference.discover(appId) { url ->
            if ("/api/appdetails?" in url) return@discover appJson
            val running = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, running) }
            try {
                Thread.sleep(25)
                val id = Regex("packageids=([0-9]+)").find(url)!!.groupValues[1].toInt()
                val name = if (id == 1001) "Example Game" else "Example Game Edition $id"
                JSONObject("""{"$id":{"success":true,"data":{"name":"$name","apps":[{"id":$appId}]}}}""")
            } finally {
                active.decrementAndGet()
            }
        }
        assertEquals(packageIds.size, options.size)
        assertTrue(options.first().isBase)
        assertTrue("Expected overlapping package requests, peak was ${peak.get()}", peak.get() in 2..4)
    }

    @Test fun discoveryKeepsSteamRuPackagePriceSeparateFromUsCatalogue() {
        val appId = 321
        val usApp = JSONObject("""{"321":{"success":true,"data":{"type":"game","name":"Example Game","package_groups":[{"subs":[{"packageid":1001,"option_text":"Example Game - $29.99"},{"packageid":1002,"option_text":"Example Game Deluxe Edition - $39.99"}]}]}}}""")
        val ruApp = JSONObject("""{"321":{"success":true,"data":{"type":"game","name":"Example Game","package_groups":[{"subs":[{"packageid":1001,"option_text":"Example Game - 1 499 руб.","price_in_cents_with_discount":149900}]}]}}}""")
        val editions = SteamEditionReference.discover(appId) { url ->
            when {
                "cc=ru" in url -> ruApp
                "/api/appdetails?" in url -> usApp
                "packageids=1001" in url -> JSONObject("""{"1001":{"success":true,"data":{"name":"Example Game","apps":[{"id":321}]}}}""")
                else -> JSONObject("""{"1002":{"success":true,"data":{"name":"Example Game Deluxe Edition","apps":[{"id":321}]}}}""")
            }
        }
        assertEquals(true, editions.first { it.packageId == 1001 }.steamRuAvailable)
        assertEquals("1 499 ₽", editions.first { it.packageId == 1001 }.steamRuPrice)
        assertEquals(false, editions.first { it.packageId == 1002 }.steamRuAvailable)
        assertNull(editions.first { it.packageId == 1002 }.steamRuPrice)
    }

    @Test fun separateEditionMatcherDoesNotMixBaseRoyalAndDlc() {
        val base = "Kingdom Come: Deliverance II"
        assertTrue(ManualContentRules.matchesSelectedSteamEdition("Kingdom Come: Deliverance II Steam Key РФ", base, base))
        assertFalse(ManualContentRules.matchesSelectedSteamEdition("Kingdom Come: Deliverance II Royal Edition Steam Key РФ", base, base, listOf("$base Royal Edition")))
        assertFalse(ManualContentRules.matchesSelectedSteamEdition("Kingdom Come: Deliverance II Anniversary Edition Steam Key РФ", base, base, listOf("$base Anniversary Edition")))
        assertTrue(ManualContentRules.matchesSelectedSteamEdition("Kingdom Come: Deliverance II Royal Edition Steam Key РФ", base, "$base Royal Edition"))
        assertFalse(ManualContentRules.matchesSelectedSteamEdition("Kingdom Come: Deliverance II From the Ashes DLC Steam Key РФ", base, "$base Royal Edition"))
    }

    @Test fun selectedSteamEditionMatchesCommonRussianSellerLabels() {
        val base = "Kingdom Come: Deliverance II"
        assertTrue(ManualContentRules.matchesSelectedSteamEdition(
            "Kingdom Come Deliverance II Королевское издание Steam Key РФ", base, "$base Royal Edition"
        ))
        assertFalse(ManualContentRules.matchesSelectedSteamEdition(
            "Kingdom Come Deliverance II Золотое издание Steam Key РФ", base, "$base Royal Edition"
        ))
        assertFalse(ManualContentRules.matchesSelectedSteamEdition(
            "Kingdom Come Deliverance II Королевское издание Steam Key РФ", base, base,
            listOf("$base Royal Edition")
        ))
        assertTrue(ManualContentRules.matchesSelectedSteamEdition(
            "Kingdom Come Deliverance II Золотое издание Steam Key РФ", base, "$base Gold Edition"
        ))
        assertTrue(ManualContentRules.matchesSelectedSteamEdition(
            "Example Game Расширенное издание Steam Key РФ", "Example Game", "Example Game Deluxe Edition"
        ))
        val reference = ManualGameReference.Game(base, appId = 1771300, aliases = listOf("$base Royal Edition"))
        assertEquals("Игра", ManualContentRules.classify(
            "Kingdom Come Deliverance II Королевское издание Steam Key РФ", "", reference
        ))
    }

    @Test fun marketplaceQueriesIncludeEnglishAndLocalizedEditionNamesWithoutBroadBaseQuery() {
        val base = "Kingdom Come: Deliverance II"
        val queries = ManualContentRules.marketplaceEditionSearchQueries(base, "$base Royal Edition")
        assertEquals("$base Royal Edition", queries.first())
        assertTrue(queries.any { GameSearchResolver.normalized(it) == GameSearchResolver.normalized("$base Королевское издание") })
        assertFalse(queries.any { GameSearchResolver.normalized(it) == GameSearchResolver.normalized(base) })
        assertEquals(queries.size, queries.distinctBy(GameSearchResolver::normalized).size)
        assertEquals(listOf("Example Game Founder Edition"), ManualContentRules.marketplaceEditionSearchQueries(
            "Example Game", "Example Game Founder Edition"
        ))
    }

    @Test fun selectedVerifiedPackageNameConfirmsUnknownEditionSuffixOnlyForThatSearch() {
        val base = "Example Game"
        val royal = "$base Anniversary Edition"
        val localizedAliases = ManualContentRules.localizedEditionAliases(base, royal)
        val game = ManualGameReference.Game(base, 123, aliases = listOf(royal) + localizedAliases)
        assertEquals("Игра", ManualContentRules.classify("Example Game Anniversary Edition Steam Key РФ", "", game))
        assertEquals("Игра", ManualContentRules.classify("Example Game Юбилейное издание Steam Key РФ", "", game))
        assertNotEquals("Игра", ManualContentRules.classify("Example Game Anniversary Edition Steam Key РФ", "", ManualGameReference.Game(base, 123)))
        assertNotEquals("Игра", ManualContentRules.classify("Example Game Юбилейное издание Steam Key РФ", "", ManualGameReference.Game(base, 123)))
    }
}

