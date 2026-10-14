# AGENTS.md — GPic Android

> This file is the canonical AI context for this repo. It is read by OpenCode (`/init`), Claude Code (`CLAUDE.md` is symlinked to it), Cursor, Codex, and other agents. Keep it concise and actionable.

## Project Overview

**GPic** is an Android companion for [gpic](https://github.com/debakarr/gpic) (Python tool, itself a port of [gotohp](https://github.com/xob0t/gotohp)). It uploads DJI Action 4 photos & videos to Google Photos over USB-C, using the same reverse-engineered internal mobile API (Pixel spoofing for unlimited storage).

- **Package:** `com.gpic.android`
- **App entry:** `MainActivity` → Compose NavHost (home/auth/settings)
- **Current version:** `1.3.5 (10)` — see `app/build.gradle.kts` (`versionCode`/`versionName` + `releaseVersion`)
- **Repo:** `github.com/debakarr/gpic-android` (private), branch `main`

## Tech Stack

| Layer | Choice | Version |
|-------|--------|---------|
| Language | Kotlin | 2.3.0 (JvmTarget 17) |
| UI | Jetpack Compose + Material3 | BOM 2026.06.01 |
| Nav | Navigation Compose | 2.9.0 |
| DI | Manual (GpicApp builds CredentialStore/DjiScanner) | — |
| Prefs | EncryptedSharedPreferences + DataStore | security-crypto 1.1.0-alpha06 |
| DB | Room + KSP | 2.8.4 / 2.3.10 |
| Network | OkHttp (+logging) + Gson, protobuf-java | 4.12.0 / 4.28.2 |
| Async | Coroutines | 1.10.2 |
| USB/SAF | DocumentFile | 1.0.1 |
| Build | AGP | 9.1.1, Gradle 9.6.1 |

`compileSdk = 37`, `targetSdk = 37`, `minSdk = 26`.

## Prerequisites

1. **JDK 21**: `java -version` must show 21.x.
2. **Android SDK** with `ANDROID_HOME` set:
   ```
   platform-tools
   platforms;android-37.0   # note the .0 suffix
   build-tools;37.0.0
   ```
3. **Google Photos** installed + signed in on the test device, and a pasted master-token auth string (see `make auth-log`). No API key needed.
4. **`local.properties`** (git-ignored, never commit):
   ```properties
   sdk.dir=/path/to/android-sdk
   # optional — only needed for signed release builds:
   keystore.path=gpic.keystore
   keystore.password=gpic123
   keystore.alias=gpic
   ```
5. **Keystore** for releases: `make keystore` (`*.keystore` is git-ignored).

## Common Commands

```bash
export ANDROID_HOME=$HOME/android-sdk
make debug      # ./gradlew assembleDebug — fastest verification (5 ABI APKs)
make release    # ./gradlew versionApks — signed versioned APKs in app/build/outputs/apk/versioned/:
#   GPic-<version>-arm64-v8a.apk, -armeabi-v7a, -x86_64, -x86, -universal
make install    # adb install arm64 debug APK (ABI=... to override)
make publish V=1.4 CODE=11 NOTES="..."   # bump → release build → commit → push → tag → gh release
make auth-log   # adb logcat filtered by photos.native for the master-token line
```

Unit tests: `./gradlew testDebugUnitTest` (JVM; includes vendored osmosis Action 4 manifest fixtures).
`GooglePhotosLiveTest` uploads one random image to the real account, so it is skipped unless both
`GPIC_LIVE_TEST=1` and `G_TOKEN` (photos.native line; `.envrc` is git-ignored, quote the value) are set.
Run it with `--rerun` — env vars aren't Test-task inputs, so Gradle otherwise reuses a cached skip.
Lint is not enforced.

## Project Structure

```
app/src/main/
  java/com/gpic/android/
    GpicApp.kt                   # Application — builds CredentialStore, DjiScanner, UploadCache
    MainActivity.kt              # entry, sets GpicTheme + NavHost
    data/
      api/      GooglePhotosApi.kt (internal Photos API port), Retry.kt
      auth/     AuthManager.kt (master-token exchange), CredentialStore.kt, GoogleAccountLinker.kt (stub)
      dji/      DjiScanner.kt (SAF tree + multi-file pick), DjiFile.kt, UsbReceiver.kt
      progress/ FileProgress.kt (UploadStatus + ProgressTracker.snapshot())
      stats/    SystemStatsCollector.kt (/proc/stat + TrafficStats), SystemStats.kt
      upload/   UploadManager.kt (one pipeline over MediaSource: hash→dedup→resume→stream→commit), MediaSource.kt
      cache/    UploadCache.kt (resume ids) + UploadHistory.kt (name+size → media key, for single-pass sources)
    camera/     CameraLink.kt (BLE pair → camera WiFi → DUML list), CameraTransfer.kt (self-resuming HTTP stream, stager)
      osmosis/  vendored KonradIT/osmosis protocol code (MIT, see NOTICE.md); only change: CameraNet socket binding
    ui/
      theme/      Theme.kt
      components/ StatusCard.kt, SystemStatsCard.kt (Morphe-style CPU/RAM/disk/net sparklines)
      screens/
        home/     HomeScreen.kt + HomeViewModel (DJI pick → scan → upload + live stats)
        auth/     AuthScreen.kt (paste master-token string, service validation)
        settings/ SettingsScreen.kt (threads, force, saver/quota, delete-after)
    upload/ UploadService.kt (foreground service)
    util/   Format.kt, Constants.kt
  proto/ AddMediaToAlbum|CommitToken|CommitUpload|CommitUploadResponse|CreateAlbum|CreateAlbumResponse|GetUploadToken|HashCheck|RemoteMatches.proto
  res/   values/{themes,strings,colors}.xml, mipmap-*, drawable/ic_launcher_foreground.xml, xml/{device_filter,file_paths}.xml
gradle/libs.versions.toml        # version catalog — single source of truth for deps
app/build.gradle.kts             # splits ABI (arm64-v8a, armeabi-v7a, x86, x86_64 + universal), versionCode/versionName/releaseVersion
docs/AUTH_LINKING_RESEARCH.md    # why silent Photos linking is impossible; CredentialManager plan
```

## Architecture Notes

### Upload flow (mirrors `gphotos/upload.py`)
`HomeViewModel.startUpload` → `UploadManager.start(files.sortedBy sizeBytes)` → per file (coroutine `Semaphore.withPermit`): PREPARING (open check) → HASHING (SHA-1 with progress) → CHECKING (`findMediaByHash` dedup) → token (`getUploadToken`, cached for resume) → UPLOADING (streaming PUT `Content-Range`, 256KB chunks) → COMMITTING (`commitUpload`) → COMPLETED/SKIPPED/ERROR. `ProgressTracker.snapshot()` deep-copies into `progressFlow` (StateFlow drops shallow `toMap()` mutations — previously stuck at Queued).

### Internal Photos API (`data/api/GooglePhotosApi.kt`, mirrors `gphotos/api.py`)
`android.googleapis.com/auth` (master-token → bearer) → `photos.googleapis.com/data/upload/...` (`X-GUploader-UploadID`, resumable via `bytes */size` → 308) → `photosdata-pa` protobufs (`HashCheck`, `CommitUpload`, `CreateAlbum`). `postProtoRaw` fails fast on 4xx (except 429); errors carry `[endpoint]` tags. Replies gunzipped before proto parse; hashCheck parse failure falls back to upload (dedup best-effort).

### Auth (two options, see AuthScreen)
- **Option B (current uploader):** paste the FULL `adb logcat | grep photos.native` line (`androidId`+`Email`+`Token`+`service=...photos.native`). `AuthManager` mirrors `auth.py` exactly — never hardcode `service`. A `userinfo.profile` line 403s every call. Stored in EncryptedSharedPreferences.
- **Option A (stub):** official OAuth via CredentialManager + AuthorizationClient (`GoogleAccountLinker.kt`, needs `WEB_CLIENT_ID` + Play verification). Post-Mar-2025 scopes are app-created-data only.

### DJI camera over WiFi (Android 10+)
`CameraLink` follows osmosis's verified sequence; only camera sockets are bound to the camera network
(`CameraNet`), never the whole process, so Google uploads keep the default network. Two modes
(Settings): download-to-phone first (default; works without mobile data) or stream (single-pass hash,
needs internet alongside the camera AP; falls back to download). `adb logcat -s GPicCamera` traces it.
On a 2.4 GHz-only phone (Redmi 3S) the Action 4's AP never appeared in scans after a successful BLE pair — likely 5 GHz.

### DJI over USB-C
Action 4 exposes MTP, which Android can't auto-mount: `OpenDocumentTree` (whole DCIM, persisted) or `OpenMultipleDocuments` (multi-file). `DjiScanner` resolves display name/size via DocumentFile + OpenableColumns, sorted smallest-first. Uploads stream straight from the Uri (no temp copy) with resume `skip()`.

### Live stats (Morphe-style)
`SystemStatsCollector` 1s flow on Default dispatcher: CPU via `/proc/stat` delta with `getElapsedCpuTime` fallback (some SELinux blocks hide it → 0%), RAM/storage via `ActivityManager`+`StatFs` (same as Morphe `DeviceStats`), app PSS, `TrafficStats` total+UID speeds, upload B/s from progress deltas, 60-sample histories. `SystemStatsCard` renders bars + canvas sparklines.
