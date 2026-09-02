package com.gpic.android.data.auth

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Port of gphotos/auth.py AuthManager
 * Efficient bearer token caching with double-checked locking.
 * Token comes from android.googleapis.com/auth exchange using authString.
 */
class AuthManager(
    private val client: OkHttpClient,
    private val language: String = "en",
) {
    @Volatile private var token: String = ""
    @Volatile private var expiry: Long = 0L
    private val mutex = Mutex()

    private fun parseAuthString(authString: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        authString.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = pair.substring(0, idx)
                val v = pair.substring(idx + 1)
                map[k] = try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
            }
        }
        return map
    }

    suspend fun getBearerToken(authString: String): String {
        val now = System.currentTimeMillis() / 1000
        if (token.isNotEmpty() && expiry > now + 60) return token
        return mutex.withLock {
            val now2 = System.currentTimeMillis() / 1000
            if (token.isNotEmpty() && expiry > now2 + 60) return token
            val (t, e) = fetchToken(authString)
            token = t
            expiry = e
            token
        }
    }

    fun getBearerTokenBlocking(authString: String): String {
        val now = System.currentTimeMillis() / 1000
        if (token.isNotEmpty() && expiry > now + 60) return token
        // Blocking path for OkHttp interceptors – use runBlocking style manual lock
        synchronized(this) {
            val now2 = System.currentTimeMillis() / 1000
            if (token.isNotEmpty() && expiry > now2 + 60) return token
            val (t, e) = fetchToken(authString)
            token = t
            expiry = e
            return token
        }
    }

    private fun fetchToken(authString: String): Pair<String, Long> {
        val params = parseAuthString(authString)
        val androidId = params["androidId"] ?: params["android_id"] ?: ""

        val formBuilder = FormBody.Builder()
            .add("app", "com.google.android.apps.photos")
            .add("callerPkg", "com.google.android.apps.photos")
            .add("device", androidId)
            .add("service", "oauth2:https://www.googleapis.com/auth/photoslibrary")

        // Forward relevant params excluding excluded keys (as in Python)
        val excluded = setOf("it_caveat_types", "assertion_jwt", "token_binding_alias")
        for ((k, v) in params) {
            if (k !in excluded) {
                // Avoid duplicate keys already added
                if (k == "app" || k == "callerPkg" || k == "device") continue
                formBuilder.add(k, v)
            }
        }

        val request = Request.Builder()
            .url("https://android.googleapis.com/auth")
            .header("app", "com.google.android.apps.photos")
            .header("device", androidId)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "GoogleAuth/1.4 (Pixel XL PQ2A.190205.001); gzip")
            .post(formBuilder.build())
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string()?.take(500) ?: ""
                throw RuntimeException("Auth failed HTTP ${resp.code}: $body")
            }
            val text = resp.body?.string() ?: throw RuntimeException("Empty auth response")
            val parsed = mutableMapOf<String, String>()
            text.lines().forEach { line ->
                val t = line.trim()
                if ("=" in t) {
                    val idx = t.indexOf('=')
                    parsed[t.substring(0, idx)] = t.substring(idx + 1)
                }
            }
            val authToken = parsed["Auth"] ?: throw RuntimeException("Auth response missing Auth token: $text")
            val expiryStr = parsed["Expiry"] ?: "0"
            val expiryLong = expiryStr.toLongOrNull() ?: 0L
            // If expiry is 0, default to 1 hour
            val effectiveExpiry = if (expiryLong == 0L) System.currentTimeMillis() / 1000 + 3600 else expiryLong
            Log.d("AuthManager", "Fetched bearer token, expires at $effectiveExpiry")
            return authToken to effectiveExpiry
        }
    }

    fun clear() {
        token = ""
        expiry = 0
    }
}
