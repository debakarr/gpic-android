package com.gpic.android.data.auth

import android.app.Activity
import android.content.Context
import android.util.Log
// NOTE: The full seamless link needs these deps (not yet added to keep debug build green):
// implementation("com.google.android.gms:play-services-auth:21.2.0")
// implementation("androidx.credentials:credentials:1.5.0")
// implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
// import androidx.credentials.CredentialManager
// import androidx.credentials.GetCredentialRequest
// import com.google.android.gms.auth.api.identity.AuthorizationRequest
// import com.google.android.gms.auth.api.identity.Identity
// import com.google.android.libraries.identity.googleid.GetGoogleIdOption

/**
 * Modern "link via installed Photos account" implementation.
 *
 * Does NOT extract the master Token from Google Photos (impossible without READ_LOGS/root).
 * Instead it re-uses the same *system Google account* that Photos uses, via:
 *  - CredentialManager (shows bottom sheet with the Photos email)
 *  - AuthorizationClient (requests OAuth scopes, one-tap consent, returns accessToken)
 *
 * The returned accessToken is for the *official* Google Photos Library/Picker API
 * (photoslibrary.googleapis.com), not the internal photosdata-pa API that gpic uses.
 * See docs/AUTH_LINKING_RESEARCH.md for comparison and why silent theft is blocked.
 *
 * Scopes post-March 2025 you can request without verification hell:
 *  - https://www.googleapis.com/auth/photoslibrary.appendonly
 *  - https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata
 *  - https://www.googleapis.com/auth/photospicker.mediaitems.readonly
 *
 * Internal scope https://www.googleapis.com/auth/photos.native is NOT
 * obtainable via public OAuth (UNREGISTERED_ON_API_CONSOLE).
 *
 * This class is a skeleton – plug your WEB_CLIENT_ID from Google Cloud Console
 * (OAuth 2.0 Client ID for Android + Web) to make it live.
 */
class GoogleAccountLinker(private val context: Context) {

    // TODO: replace with your Web OAuth client ID from https://console.cloud.google.com/apis/credentials
    // Create an OAuth 2.0 Client ID (Web application) – it’s used as audience for ID token.
    private val WEB_CLIENT_ID = "REPLACE_WITH_WEB_CLIENT_ID.apps.googleusercontent.com"

    sealed class LinkResult {
        data class Success(val email: String?, val accessToken: String?) : LinkResult()
        data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : LinkResult()
        data class Error(val msg: String) : LinkResult()
    }

    /**
     * Step 1: Identify user via Credential Manager (shows the same accounts Photos uses).
     * Returns email if available; call this for UI pre-fill.
     * STUB – enable after adding play-services-auth + credentials deps.
     * See commented code below for the real implementation.
     */
    suspend fun getCredentialEmail(): String? {
        Log.w("GoogleAccountLinker", "Stub – add WEB_CLIENT_ID and deps to enable. See docs/AUTH_LINKING_RESEARCH.md §3B")
        // Real impl (uncomment after adding deps):
        // val googleIdOption = GetGoogleIdOption.Builder()
        //     .setFilterByAuthorizedAccounts(true).setServerClientId(WEB_CLIENT_ID).build()
        // val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
        // val result = CredentialManager.create(context).getCredential(context, request)
        // ... parse GoogleIdTokenCredential.getId() -> email
        return null
    }

    /**
     * Step 2: Authorize for Photos scopes via AuthorizationClient.
     * STUB – enable after adding deps.
     */
    suspend fun authorize(activity: Activity, scopes: List<String> = defaultScopes()): LinkResult {
        Log.w("GoogleAccountLinker", "Stub – add deps; would request ${scopes.joinToString()}")
        return LinkResult.Error("Not wired – set WEB_CLIENT_ID and add play-services-auth + credentials. See docs/AUTH_LINKING_RESEARCH.md")
        // Real impl:
        // val request = AuthorizationRequest.builder().setRequestedScopes(scopes.map{Scope(it)}).build()
        // val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        // if (result.hasResolution()) LinkResult.NeedsConsent(result.pendingIntent!!) else LinkResult.Success(null, result.accessToken)
    }

    companion object {
        fun defaultScopes(): List<String> = listOf(
            "https://www.googleapis.com/auth/photoslibrary.appendonly",
            "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata",
            // Alternatively: "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
        )
    }
}
