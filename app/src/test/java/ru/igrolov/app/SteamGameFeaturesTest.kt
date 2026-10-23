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

    @Test fun recognizesEntityEncodedRussianAndIgnoresRussianInUnrelatedWords() {
        val encoded = SteamGameFeatures.fromDetails(JSONObject("""{
            "supported_languages":"English, &#1056;&#1091;&#1089;&#1089;&#1082;&#1080;&#1081; <strong>*</strong>"
        }"""))
        val unrelated = SteamGameFeatures.fromDetails(JSONObject("""{
            "supported_languages":"English, French (Russian subtitle font support)"
        }"""))
        assertEquals(SteamRussianLocalization.SUPPORTED, encoded.russianLocalization)
        assertEquals(SteamRussianLocalization.NOT_SUPPORTED, unrelated.russianLocalization)
    }

    @Test fun missingDataAndMissingControllerFieldRemainUnknown() {
        assertEquals(SteamGameFeatures(), SteamGameFeatures.fromDetails(JSONObject("{}")))
        assertEquals(SteamControllerSupport.UNKNOWN,
            SteamGameFeatures.fromDetails(JSONObject("""{"name":"A game","supported_languages":"English"}""")).controllerSupport)
        assertEquals(SteamControllerSupport.NONE,
            SteamGameFeatures.fromDetails(JSONObject("""{"controller_support":"none"}""")).controllerSupport)
    }

    @Test fun fallbackOnlyFillsUnknownValues() {
        val known = SteamGameFeatures(SteamRussianLocalization.SUPPORTED, SteamControllerSupport.UNKNOWN)
        val fallback = SteamGameFeatures(SteamRussianLocalization.NOT_SUPPORTED, SteamControllerSupport.FULL)
        assertEquals(SteamGameFeatures(SteamRussianLocalization.SUPPORTED, SteamControllerSupport.FULL), known.withFallback(fallback))
    }
}
