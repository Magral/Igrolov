package ru.igrolov.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Uses SteamGridDB's official API only when the user has supplied their own API key. */
object SteamGridDbArtwork {
    private const val API_ROOT = "https://www.steamgriddb.com/api/v2"
    private const val KEY_ALIAS = "igrolov_steamgriddb_api_key"
    private const val PREFS = "steamgriddb_private"
    private const val CIPHER_PREF = "api_key_ciphertext"
    private const val IV_PREF = "api_key_iv"
    private const val GCM_TAG_BITS = 128
    private const val EMPTY_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val POSTER_CACHE_TTL_MS = 30 * 24 * 60 * 60 * 1000L

    fun saveApiKey(context: Context, apiKey: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (apiKey.isBlank()) {
            prefs.edit().remove(CIPHER_PREF).remove(IV_PREF).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(CIPHER_PREF, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(IV_PREF, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun readApiKey(context: Context): String = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ciphertext = prefs.getString(CIPHER_PREF, null) ?: return ""
        val iv = prefs.getString(IV_PREF, null) ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")

    /** Returns highest-rated static 600x900 posters for one Steam App ID. */
    fun fetchPortraitUrls(context: Context, appId: Int, apiKey: String): List<String> {
        if (appId <= 0 || apiKey.isBlank()) return emptyList()
        val tokenFingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(apiKey.trim().toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences("steam_artwork_urls", Context.MODE_PRIVATE)
        val cacheKey = "sgdb_portrait_${appId}_$tokenFingerprint"
        val cachedAtKey = "${cacheKey}_at"
        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong(cachedAtKey, 0L)
        val cachedJson = prefs.getString(cacheKey, null)
        if (cachedAt > 0L && cachedJson != null) {
            val cachedUrls = runCatching {
                val array = JSONArray(cachedJson)
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(::isSafeGridUrl) }
            }.getOrDefault(emptyList())
            val ttl = if (cachedUrls.isEmpty()) EMPTY_CACHE_TTL_MS else POSTER_CACHE_TTL_MS
            if (now - cachedAt in 0..ttl) return cachedUrls
        }
        val urls = runCatching {
            val gameId = parseSteamGridDbGameId(request("$API_ROOT/games/steam/$appId", apiKey))
                ?: return@runCatching emptyList()
            parsePortraitUrls(
                request(
                    "$API_ROOT/grids/game/$gameId?dimensions=600x900&types=static&nsfw=false&humor=false&epilepsy=false&limit=10",
                    apiKey
                )
            )
        }.getOrDefault(emptyList())
        prefs.edit().putString(cacheKey, JSONArray(urls).toString()).putLong(cachedAtKey, now).apply()
        return urls
    }

    fun validateApiKey(apiKey: String): Boolean =
        apiKey.isNotBlank() && runCatching {
            parseSteamGridDbGameId(request("$API_ROOT/games/steam/7940", apiKey)) != null
        }.getOrDefault(false)

    internal fun parseSteamGridDbGameId(response: String): Int? = runCatching {
        val root = JSONObject(response)
        if (!root.optBoolean("success")) return null
        root.optJSONObject("data")?.optInt("id")?.takeIf { it > 0 }
    }.getOrNull()

    internal fun parsePortraitUrls(response: String): List<String> = runCatching {
        val root = JSONObject(response)
        if (!root.optBoolean("success")) return emptyList()
        val grids = root.optJSONArray("data") ?: return emptyList()
        (0 until grids.length())
            .mapNotNull { grids.optJSONObject(it) }
            .sortedByDescending { it.optInt("score") }
            .mapNotNull { grid -> grid.optString("url").trim().takeIf(::isSafeGridUrl) }
            .distinct()
    }.getOrDefault(emptyList())

    private fun isSafeGridUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("cdn2.steamgriddb.com", ignoreCase = true) &&
            url.path.matches(Regex("/grid/[A-Za-z0-9._-]+\\.(png|jpe?g|webp)", RegexOption.IGNORE_CASE))
    }.getOrDefault(false)

    private fun request(url: String, apiKey: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("User-Agent", "Igrolov Android")
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("SteamGridDB HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
