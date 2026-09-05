package com.gpic.android.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Port of gphotos/config.py ConfigManager
 * On Android we use EncryptedSharedPreferences for credential storage.
 * Plain fallback if device doesn't support encryption.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "gpic_encrypted_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("gpic_prefs", Context.MODE_PRIVATE)
        }
    }

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<AppConfig> = _configFlow

    fun loadConfig(): AppConfig {
        val json = prefs.getString("config_json", null) ?: return AppConfig()
        return try {
            val obj = JSONObject(json)
            val credsArray = obj.optJSONArray("credentials") ?: JSONArray()
            val creds = mutableListOf<Credential>()
            for (i in 0 until credsArray.length()) {
                val c = credsArray.getJSONObject(i)
                creds.add(
                    Credential(
                        email = c.optString("email", ""),
                        authString = c.optString("auth_string", ""),
                        language = c.optString("language", "en")
                    )
                )
            }
            AppConfig(
                selectedEmail = obj.optString("selected_email", ""),
                credentials = creds,
                uploadThreads = obj.optInt("upload_threads", 3),
                forceUpload = obj.optBoolean("force_upload", false),
                deleteAfterUpload = obj.optBoolean("delete_after_upload", false),
                saverMode = obj.optBoolean("saver_mode", false),
                useQuota = obj.optBoolean("use_quota", false)
            )
        } catch (e: Exception) {
            AppConfig()
        }
    }

    fun saveConfig(config: AppConfig) {
        val obj = JSONObject().apply {
            put("selected_email", config.selectedEmail)
            put("upload_threads", config.uploadThreads)
            put("force_upload", config.forceUpload)
            put("delete_after_upload", config.deleteAfterUpload)
            put("saver_mode", config.saverMode)
            put("use_quota", config.useQuota)
            val arr = JSONArray()
            config.credentials.forEach { c ->
                arr.put(JSONObject().apply {
                    put("email", c.email)
                    put("auth_string", c.authString)
                    put("language", c.language)
                })
            }
            put("credentials", arr)
        }
        prefs.edit().putString("config_json", obj.toString()).apply()
        _configFlow.value = config
    }

    fun addCredential(authString: String): Credential? {
        // Parse Email from query string like "androidId=...&Email=...&Token=...&service=..."
        // Require master Token + androidId too, otherwise auth will fail later at hashing->token
        // exchange with cryptic UNREGISTERED_ON_API_CONSOLE.
        val params = parseQueryString(authString)
        val email = params["Email"] ?: params["email"] ?: return null
        if (email.isBlank()) return null
        val token = params["Token"] ?: params["token"] ?: return null
        if (token.isBlank()) return null
        val androidId = params["androidId"] ?: params["android_id"] ?: return null
        if (androidId.isBlank()) return null
        val lang = params["lang"] ?: "en"
        val current = loadConfig()
        val existingIdx = current.credentials.indexOfFirst { it.email.equals(email, ignoreCase = true) }
        val credential = Credential(email = email, authString = authString, language = lang)
        val newConfig = if (existingIdx >= 0) {
            val updated = current.credentials.toMutableList()
            updated[existingIdx] = credential
            current.copy(credentials = updated)
        } else {
            current.copy(credentials = current.credentials + credential)
        }
        // Auto-select first credential if none selected
        val finalConfig = if (newConfig.selectedEmail.isBlank()) newConfig.copy(selectedEmail = email) else newConfig
        saveConfig(finalConfig)
        return credential
    }

    fun describeService(authString: String): String {
        val params = parseQueryString(authString)
        return params["service"] ?: "(no service= field — re-copy full line if auth fails)"
    }

    fun removeCredential(email: String) {
        val current = loadConfig()
        val filtered = current.credentials.filterNot { it.email.equals(email, ignoreCase = true) }
        val newSelected = if (current.selectedEmail.equals(email, ignoreCase = true)) {
            filtered.firstOrNull()?.email ?: ""
        } else current.selectedEmail
        saveConfig(current.copy(credentials = filtered, selectedEmail = newSelected))
    }

    fun setActive(email: String): Boolean {
        val current = loadConfig()
        val match = current.credentials.firstOrNull { it.email.contains(email, ignoreCase = true) } ?: return false
        saveConfig(current.copy(selectedEmail = match.email))
        return true
    }

    fun getActiveCredential(): Credential? {
        val config = loadConfig()
        if (config.selectedEmail.isBlank()) return config.credentials.firstOrNull()
        return config.credentials.firstOrNull { it.email.equals(config.selectedEmail, ignoreCase = true) }
            ?: config.credentials.firstOrNull()
    }

    fun getCredential(email: String): Credential? =
        loadConfig().credentials.firstOrNull { it.email.equals(email, ignoreCase = true) }

    private fun parseQueryString(qs: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        // authString may be raw query string without leading '?'
        val pairs = qs.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val k = pair.substring(0, idx).trim()
                val v = pair.substring(idx + 1).trim()
                // URL decode basic
                try {
                    map[k] = java.net.URLDecoder.decode(v, "UTF-8")
                } catch (e: Exception) {
                    map[k] = v
                }
            }
        }
        return map
    }
}
