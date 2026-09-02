# Research: Can GPic Android link via the already-installed Google Photos app?

**Question:** Photos app is already installed – can we get the token silently from it and avoid the `adb logcat` paste?

**Short answer:**  
- **No (without root / privileged permission):** you **cannot** silently steal the master token (`androidId=…&Token=…`) from Google Photos/GmsCore due to Android sandbox + `READ_LOGS` restrictions.  
- **Yes, seamlessly with consent:** you **can** offer a one-tap “Link Google account on this device” that re-uses the *same Google account* Photos uses, via system `AccountManager` / `CredentialManager` + `AuthorizationClient`. The user sees the familiar Google account picker (pre-filled with the Photos account) and grants consent once. This uses the **official** Photos Library/Picker APIs, not the internal `photosdata-pa` API that `gpic`/`gotohp` uses. Trade-off: unlimited Pixel spoofing is lost, and post-March 2025 scopes are restricted to app-created data unless verified.

---

## 1. How gpic/gotohp credentials work

- `gpic` and `gotohp` mimic the **internal** Google Photos mobile API (`photosdata-pa.googleapis.com`, `photos.googleapis.com/data/upload/...`, `android.googleapis.com/auth`).
- Credential string example: `androidId=…&Email=you@gmail.com&Token=…[&assertion_jwt=…]`
- Obtained **once** via:
  - **ReVanced (no root):** GmsCore patched Photos logs the auth request → `adb logcat | grep auth%2Fphotos.native` → copy `androidId` to end of line. (gotohp README Option 1)
  - **Official APK (root):** HTTP Toolkit intercept `contains(https://www.googleapis.com/auth/photos.native)` or read `lstBindingKeyAlias` from `AccountManager` via `adb shell` (gotohp README Option 2 + issue #43 token-binding `assertion_jwt` rollout).

- Python `gphotos/auth.py:32` forwards every param from that string (except `it_caveat_types`, `assertion_jwt`, `token_binding_alias`) plus `app=com.google.android.apps.photos`, `callerPkg`, `device=androidId` to `https://android.googleapis.com/auth` → gets a short-lived bearer, then calls `X-GUploader-UploadID`, `CommitUpload`, `HashCheck` etc with protobufs and Pixel `User-Agent`.

- Key: this **master `Token`** is a long-lived Google auth token held by GmsCore/`AccountManager`. It is **not** an OAuth access token you can request via public SDK without special scope.

## 2. Why the installed Photos app does not expose its token to our app

- **Sandbox:** each app's data (`/data/data/com.google.android.apps.photos`, `/data/system/users/0/accounts*`) is private. No IPC exposes the bearer or master token.
- **`READ_LOGS`:** Since Android 4.1 (API 16) `READ_LOGS` is `signature|privileged|development` (StackOverflow #45270547, Android docs `Log Info Disclosure`). Third-party apps cannot read another app's logcat buffer. Even the `adb shell pm grant … READ_LOGS` trick (LogcatReader, tananaev/rootless-logcat) needs a PC-connected `adb shell` grant and, on Android 12+, a user one-time prompt (`support.google.com/android/answer/12986432`, `source.android.com/docs/core/tests/debug/understanding-logging`). Google recommends **not** logging to logcat in production and R8-strips it. So in-app `logcat -d | grep auth` will **not** see Photos logs on a normal consumer device.
- **GmsCore log trick only works externally:** ReVanced GmsCore deliberately writes an *unredacted* log line. Capturing it still needs `adb logcat` from a PC or wireless `adb` – not silently from inside GPic.
- **Result:** on a non-rooted, non-ADB-granted phone, `GPic` **cannot** call `logcat` and extract `androidId`+`Token`.

## 3. What *is* possible with the Photos account on-device?

The Photos app uses the **system Google account** (`com.google`). That same account *is* visible to other apps via system APIs – with user consent:

### A. AccountManager (legacy)
```kotlin
val am = AccountManager.get(this)
val accounts = am.getAccountsByType("com.google") // needs GET_ACCOUNTS (install-time on 26-? but runtime considerations)
// or
AccountManager.newChooseAccountIntent(...)
am.getAuthToken(account, "oauth2:https://www.googleapis.com/auth/photoslibrary", null, activity, callback, null)
```
- Shows system account picker pre-filled with the Photos email.
- First time triggers `UserRecoverableAuthException` → system consent screen.
- `GoogleAuthUtil.getToken()` is **deprecated** (since Dec 2024, docs `developers.google.com/android/reference/com/google/android/gms/auth/GoogleAuthUtil`) – replacement is `AuthorizationClient`.

### B. Modern: Credential Manager + AuthorizationClient (recommended)
```kotlin
// build.gradle
implementation("com.google.android.gms:play-services-auth:22.0.0")
implementation("androidx.credentials:credentials:1.5.0")
implementation("androidx.credentials:credentials-play-services-auth:1.5.0")

// 1. Identify user (optional, for UX)
val googleIdOption = GetGoogleIdOption.Builder()
    .setFilterByAuthorizedAccounts(true) // only accounts already consented
    .setServerClientId(WEB_CLIENT_ID) // from Google Cloud Console
    .setAutoSelectEnabled(true)
    .build()
val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
credentialManager.getCredential(context, request) // bottom sheet, no password re-entry

// 2. Authorize for Photos scopes
val scopes = listOf(Scope("https://www.googleapis.com/auth/photoslibrary.appendonly"),
                    Scope("https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata"))
// or Picker: Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly")
val authRequest = AuthorizationRequest.builder().setRequestedScopes(scopes).build()
Identity.getAuthorizationClient(activity).authorize(authRequest)
    .addOnSuccessListener { result ->
        if (result.hasResolution()) {
            startIntentSenderForResult(result.pendingIntent!!.intentSender, RC_AUTH, null, 0,0,0)
        } else {
            val token = result.accessToken // use immediately
            // result.getServerAuthCode() if offline access needed
        }
    }
```
Refs: `developer.android.com/identity/authorization`, `developers.google.com/identity/protocols/oauth2/resources/loopback-migration` (client-side), `developers.google.com/identity/android-credential-manager`.

This re-uses the *same* account Photos uses, because it enumerates device Google accounts (Photos’ account is one of them). The user sees “GPic wants to access Google Photos – you@gmail.com” – identical to linking Photos, with one tap.

### C. What scopes can we get?

- **Before 31 Mar 2025:** `photoslibrary`, `photoslibrary.readonly`, `photoslibrary.sharing` existed.
- **After 31 Mar 2025:** Google removed them for new apps ( `developers.google.com/photos/overview/authorization` warning). Remaining:
  - `photoslibrary.appendonly` + `photoslibrary.readonly.appcreateddata` → **only media *your app* created**, not the whole library.
  - `photospicker.mediaitems.readonly` → Picker session (user manually picks items in system UI).

- `gotohp/gpic` internal scope `https://www.googleapis.com/auth/photos.native` / `auth%2Fphotos.native` is **not** in the public API Console; requesting it via `AuthorizationClient` will fail with `UNREGISTERED_ON_API_CONSOLE` (`stackoverflow.com/questions/45925190`) unless your Cloud project is allow-listed (partner program). So you cannot get an internal unlimited bearer via public OAuth – you’d still need the ReVanced master token.

## 4. Comparison: Internal vs Official

|  | Internal (`gpic` current) | Official (CredentialManager+AuthorizationClient) |
|---|---|---|
| Token | `androidId`+ master `Token` via GmsCore log | Short-lived OAuth `accessToken` (1h) + optional `serverAuthCode` → refresh |
| Unlimited Pixel spoof? | Yes (`Pixel XL`/`Pixel 2` UA) | No |
| Scope | `photosdata-pa`, `photos.native` (undocumented) | `appendonly` / Picker only |
| Can auto-link from installed Photos without paste? | **No** (needs PC adb or root) | **Yes** – one-tap account picker, consent once, then silent `authorize()` |
| Verification | None | Requires Cloud project, OAuth consent screen, Play verification (`support.google.com/cloud/answer/7454865`) |

## 5. Recommendation for GPic Android

Keep **both** flows, default to seamless:

1. **Primary (new):** “Link Google account on this device” button → `CredentialManager` + `AuthorizationClient` → official Library/Picker API. Explain limits (app-created only) and that uploads count against quota. Implement `OfficialPhotosApi` (`photoslibrary.googleapis.com/v1/uploads` → `mediaItems:batchCreate`). This satisfies “Photos is already installed, so link easily” with **no PC**.
2. **Advanced fallback (keep):** “Paste adb logcat auth string (unlimited)” → existing `AuthManager` + `GooglePhotosApi` (internal). Show gotohp instructions inside the app (QR share `config.json` from desktop). This remains for users who want unlimited Pixel quality.
3. Add `GET_ACCOUNTS` / `USE_CREDENTIALS` handling, show which email Photos uses by listing `AccountManager.getAccountsByType("com.google")` (no token yet) – purely for UX pre-fill.

This respects sandbox, complies with Play policy, and matches user mental model: “GPic sees my Photos accounts” (because it lists the same system accounts Photos uses).

## 6. What we will implement next (skeleton already added)

- `data/auth/GoogleAccountLinker.kt` – wraps `CredentialManager` + `AuthorizationClient` (see code snippet above), exposes `StateFlow<LinkState>`.
- `ui/screens/auth/AuthScreen.kt` – now shows two cards: *One-tap link (recommended)* vs *Paste unlimited token*.
- `app/build.gradle.kts` – add `play-services-auth`, `credentials-*` deps.
- Update `README.md` with “Option A: One-tap link” vs “Option B: Unlimited via adb”.

## 7. Evidence / references

- gotohp README: `github.com/xob0t/gotohp` – credential acquisition via `adb logcat | FINDSTR auth%2Fphotos.native` / HTTP Toolkit `auth/photos.native`.
- DeepWiki credential acquisition docs.
- StackOverflow `is READ_LOGS normal or dangerous?` – `signature|privileged|development` since Android 4.1.
- Android docs: `Log Info Disclosure`, `READ_LOGS` restricted (`developer.android.com/privacy-and-security/risks/log-info-disclosure`, `source.android.com/docs/core/tests/debug/understanding-logging`).
- `tananaev/rootless-logcat`, `darshanparajuli/LogcatReader` – need `adb shell pm grant … READ_LOGS`.
- GoogleAuthUtil deprecated: `developers.google.com/android/reference/com/google/android/gms/auth/GoogleAuthUtil`.
- Authorization: `developer.android.com/identity/authorization`, `developers.google.com/identity/protocols/oauth2/resources/loopback-migration`.
- Photos scopes post-Mar 2025: `developers.google.com/photos/overview/authorization`.
- `UNREGISTERED_ON_API_CONSOLE` for internal scopes: StackOverflow #40997205.
- `AccountManager` docs, Credential Manager codelab.

---

**Conclusion:** Don’t promise silent token theft from Photos – it’s impossible without system priv. Promise *seamless account reuse* via modern Google Identity – which *feels* like “linking via Photos” because it shows the same account, but under the hood is standard OAuth with user consent. Keep manual paste as expert path for unlimited.
