package com.gpic.android.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Port of gphotos/config.py ConfigManager
 * On Android we use EncryptedSharedPreferences for credential storage.
 * Plain fallback (own file, no backup) only if the device Keystore is broken.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        openEncrypted(context) ?: run {
            // A crypto failure means the Keystore key no longer matches the file (reinstall, OS
            // keystore reset): the stored token is unreadable for good, so start a fresh encrypted
            // file and the user re-pastes once. Anything else may be transient — never wipe for it.
            if (lastOpenError.isCryptoFailure()) {
                Log.w("CredentialStore", "encrypted prefs undecryptable — resetting", lastOpenError)
                context.deleteSharedPreferences(ENCRYPTED_FILE)
                openEncrypted(context)
            } else null
        } ?: run {
            // Last resort on devices whose Keystore is broken outright. Its own file — never the
            // UI-state "gpic_prefs" — and excluded from backup (allowBackup=false).
            Log.e("CredentialStore", "Keystore unavailable — storing credentials unencrypted")
            context.getSharedPreferences("gpic_credentials_plain", Context.MODE_PRIVATE)
        }
    }

    private fun openEncrypted(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        lastOpenError = e
        null
    }

    private var lastOpenError: Throwable? = null

    private fun Throwable?.isCryptoFailure(): Boolean =
        generateSequence(this) { it.cause }.any { it is java.security.GeneralSecurityException }

    companion object {
        private const val ENCRYPTED_FILE = "gpic_encrypted_prefs"
        private const val CLAUDE_KEY = "claude_api_key"
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
                // 3 was the old default; configs saved before Auto became the default move to Auto once.
                uploadThreads = obj.optInt("upload_threads", 0).let { if (it == 3 && !obj.has("threads_v2")) 0 else it },
                forceUpload = obj.optBoolean("force_upload", false),
                deleteAfterUpload = obj.optBoolean("delete_after_upload", false),
                saverMode = obj.optBoolean("saver_mode", false),
                useQuota = obj.optBoolean("use_quota", false),
                cameraStream = obj.optBoolean("camera_stream", false),
            )
        } catch (e: Exception) {
            AppConfig()
        }
    }

    fun saveConfig(config: AppConfig) {
        val obj = JSONObject().apply {
            put("selected_email", config.selectedEmail)
            put("upload_threads", config.uploadThreads)
            put("threads_v2", true)
            put("force_upload", config.forceUpload)
            put("delete_after_upload", config.deleteAfterUpload)
            put("saver_mode", config.saverMode)
            put("use_quota", config.useQuota)
            put("camera_stream", config.cameraStream)
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

    /**
     * Why this auth line can't upload, or null if it looks right. The Photos API only accepts a
     * token minted for the photos.native service; the userinfo.profile line (Google sign-in) that
     * sits next to it in logcat yields a token every Photos call rejects with 403.
     */
    fun serviceProblem(authString: String): String? {
        val svc = parseQueryString(authString)["service"]
            ?: return "This line has no service= field. Copy the whole line that ends in service=…photos.native."
        if (svc.contains("photos")) return null
        if (svc.contains("userinfo")) return "This is the Google sign-in line (userinfo.profile), not the Photos one. Every upload would fail with 403. Copy the line containing photos.native instead."
        return "service=$svc isn't the Photos service. Copy the line containing photos.native."
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

    /** The user's own Anthropic API key for the video editor's "Ask Claude"; same encrypted file as the Photos token. */
    fun getClaudeApiKey(): String? = prefs.getString(CLAUDE_KEY, null)?.takeIf { it.isNotBlank() }

    fun setClaudeApiKey(key: String?) {
        prefs.edit().apply { if (key.isNullOrBlank()) remove(CLAUDE_KEY) else putString(CLAUDE_KEY, key.trim()) }.apply()
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
