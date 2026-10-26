package ru.igrolov.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SteamGridDbKeyStoreTest {
    @Test fun apiKeyIsEncryptedAndCanBeRemoved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("steamgriddb_private", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        SteamGridDbArtwork.saveApiKey(context, "private-test-key-abc123")
        assertEquals("private-test-key-abc123", SteamGridDbArtwork.readApiKey(context))
        assertFalse(prefs.getString("api_key_ciphertext", "").orEmpty().contains("private-test-key-abc123"))

        SteamGridDbArtwork.saveApiKey(context, "")
        assertEquals("", SteamGridDbArtwork.readApiKey(context))
        assertFalse(prefs.contains("api_key_ciphertext"))
    }
}
