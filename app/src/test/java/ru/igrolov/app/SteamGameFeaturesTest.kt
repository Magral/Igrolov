package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class SteamGameFeaturesTest {
    @Test fun readsRussianLanguageAndFullControllerSupport() {
        val features = SteamGameFeatures.fromDetails(JSONObject("""{
            "supported_languages":"English, Russian <strong>*</strong>, French",
            "controller_support":"full"
        }"""))
        assertEquals(SteamRussianLocalization.SUPPORTED, features.russianLocalization)
        assertEquals(SteamControllerSupport.FULL, features.controllerSupport)
    }

    @Test fun distinguishesMissingRussianAndPartialControllerSupport() {
        val features = SteamGameFeatures.fromDetails(JSONObject("""{
            "supported_languages":"English, French",
            "controller_support":"partial"
        }"""))
        assertEquals(SteamRussianLocalization.NOT_SUPPORTED, features.russianLocalization)
        assertEquals(SteamControllerSupport.PARTIAL, features.controllerSupport)
    }

    @Test fun recognizesRussianLanguageNameLocalizedBySteam() {
        val features = SteamGameFeatures.fromDetails(JSONObject("""{
            "supported_languages":"Английский, Русский <strong>*</strong>, Французский",
            "controller_support":"full"
        }"""))
        assertEquals(SteamRussianLocalization.SUPPORTED, features.russianLocalization)
    }

    @Test fun missingDataIsUnknownAndMissingControllerFieldMeansNoSupport() {
        assertEquals(SteamGameFeatures(), SteamGameFeatures.fromDetails(JSONObject("{}")))
        assertEquals(SteamControllerSupport.NONE,
            SteamGameFeatures.fromDetails(JSONObject("""{"name":"A game","supported_languages":"English"}""")).controllerSupport)
    }

    @Test fun fallbackOnlyFillsUnknownValues() {
        val known = SteamGameFeatures(SteamRussianLocalization.SUPPORTED, SteamControllerSupport.UNKNOWN)
        val fallback = SteamGameFeatures(SteamRussianLocalization.NOT_SUPPORTED, SteamControllerSupport.FULL)
        assertEquals(SteamGameFeatures(SteamRussianLocalization.SUPPORTED, SteamControllerSupport.FULL), known.withFallback(fallback))
    }
}
