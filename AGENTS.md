# AGENTS.md — GPic Android

> This file is the canonical AI context for this repo. It is read by OpenCode (`/init`), Claude Code (`CLAUDE.md` is symlinked to it), Cursor, Codex, and other agents. Keep it concise and actionable.

## Project Overview

**GPic** is an Android companion for [gpic](https://github.com/debakarr/gpic) (Python tool, itself a port of [gotohp](https://github.com/xob0t/gotohp)). It uploads DJI Action 4 photos & videos to Google Photos over USB-C, using the same reverse-engineered internal mobile API (Pixel spoofing for unlimited storage).

- **Package:** `com.gpic.android`
- **App entry:** `MainActivity` → Compose NavHost (home/auth/settings)
- **Current version:** `1.4.1 (13)` — see `app/build.gradle.kts` (`versionCode`/`versionName` + `releaseVersion`)
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
| Video | Media3 Transformer + effect + ExoPlayer/UI | 1.10.1 |
| Claude | anthropic-java (OkHttp client; ships its own R8 rules) | 2.73.0 |
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
      upload/   UploadManager.kt (one pipeline over MediaSource: hash→dedup→resume→stream→commit), MediaSource.kt, OriginalMedia.kt (unredacted GPS)
      cache/    UploadCache.kt (resume ids) + UploadHistory.kt (name+size → media key, for single-pass sources)
                + CheckedCache.kt (files confirmed in the library: key+size+mtime → media key, 30 days)
      cleanup/  AgeRule.kt (free-up-space age cutoff, EXIF date parsing; pure, unit-tested)
    editor/     EditPlan.kt (the edit model + Claude JSON schema; pure), CubeLut.kt (.cube parser, built-in look; pure),
                LutStore.kt, VideoProbe.kt (metadata, frames, flat-footage guess), VideoExporter.kt (Media3 Transformer),
                ClaudeEditPlanner.kt (Anthropic SDK, structured output → EditPlan)
    camera/     CameraLink.kt (BLE pair → camera WiFi → DUML list), CameraTransfer.kt (self-resuming HTTP stream, stager)
      osmosis/  vendored KonradIT/osmosis protocol code (MIT, see NOTICE.md); only change: CameraNet socket binding
    ui/
      theme/      Theme.kt
      components/ StatusCard.kt, SystemStatsCard.kt (Morphe-style CPU/RAM/disk/net sparklines)
      screens/
        home/     HomeScreen.kt + HomeViewModel (DJI pick → scan → upload + live stats)
        auth/     AuthScreen.kt (paste master-token string, service validation)
        settings/ SettingsScreen.kt (threads, force, saver/quota, delete-after)
        albums/   AlbumsScreen.kt + AlbumsViewModel (suggestions, multi-select "Create N albums")
        editor/   EditorScreen.kt + EditorViewModel (preview via ExoPlayer.setVideoEffects, export, Ask Claude)
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

### Photo location (GPS) — read originals
Android 10+ zero-fills GPS in photos it hands to apps without `ACCESS_MEDIA_LOCATION`, so a plain
SAF/MediaStore read uploads a location-less copy (and its hash then dedups against that copy).
`OriginalMedia` maps the Uri to MediaStore (`getMediaUri`, else RELATIVE_PATH lookup for
`com.android.externalstorage.documents`) and opens it with `setRequireOriginal`. Home asks for photo
access + media location before any picker. When the original's hash is new, `UploadManager` also
hashes the plain read: if *that* is in the library, an older GPic uploaded it stripped → upload the
original and flag `staleKey` (the old copy stays; there's no known edit/trash call). Delete-after
refuses photos read without the original (`MediaSource.mayHaveLostLocation`). `adb logcat -s GPicMedia`.
Measured on the Redmi 3S (Android 13): folder (tree) reads without the permission were stripped (hash
changed, same size); single picks from the picker's Recent (media documents) were not. Stripping zeroes the
GPS values but keeps the tags (GPSLatitude reads 0/1,0/1,0/1), so `UriSource.knownStrippedGps` = plain read
has GPS tags and no original was available. JPEGs without GPS tags lose nothing and delete normally; with
the permission granted a known-stripped photo fails ("Retry") instead of uploading a GPS-less duplicate.
Videos and non-JPEG photos (stream EXIF may read nothing) can't be checked: never deleted without the original. Google Photos does show EXIF GPS for GPic
uploads (checked on a cloud-only test item).

### Free up space (Home tab)
SAF tree folder (`DjiScanner.listTree`, one query per folder, hidden entries skipped) → files whose
taken time (earlier of EXIF DateTimeOriginal and mtime; `data/cleanup/AgeRule.kt`, EXIF read only when
mtime alone isn't old enough) is before `AgeRule` (days/weeks/months/years, calendar maths, min 1) →
confirm dialog → upload with delete-after forced on (exact-hash skips delete too). Folder/age/subfolder
choices live in `gpic_prefs` (`cleanup_*`), not AppConfig.

### Home screen
Type filter (Photos / RAW / Videos chips, `MediaKind`, persisted as `type_filter`) applies to every
source: `HomeUiState.files` is `djiFiles` after the filter and is what the list shows and Upload sends.
After a run the FAB becomes "Retry N" (failed/cancelled only) or disappears; a new pick/rescan resets it.
Progress card sits right under the account card; the top bar shows "Uploading n of N" (tap → top); a
small go-to-top FAB appears once scrolled; device stats collapse; list chips All / Not done / Failed.
A saved phone-storage folder is rescanned on launch (MTP camera folders wait for Rescan).

### Big folders (WhatsApp) — speed
Measured on the Redmi 3S with 600 tiny JPEGs already in the library (`adb logcat -s GPicPerf` prints per-step totals):
folder scan 33.4 s → 1.0 s (`listTree`: one DocumentsContract query per folder; DocumentFile did ~5 IPCs per file);
upload run 146.5 s → 68.9 s, and a re-run of the same folder 6.0 s (`CheckedCache`). A first run is bound by `hashCheck` round trips (~0.5–0.9 s each), so `UploadManager`
has two pools: checks get `max(threads, 8)` slots (MTP/camera caps still apply), transfers use the thread setting
(default now Auto; configs still on the old default 3 migrate once via `threads_v2`). Google rate-limits these calls
per minute per account (429 "PhotosFeService RPC calls per minute per user", hit after ~1,800 checks in a few minutes):
`postProtoRaw` then pauses every request app-wide (Retry-After, else 10/20/40/60 s, up to 6 times) instead of failing files. Progress is published by a
250 ms ticker (a snapshot copies every row; per-step emits were quadratic). History/index/checked writes are batched.
`UriSource` reads the first 128 KB once: a JPEG with no GPS tags, or with real (non-zero) coordinates, is read as is
(no MediaStore lookup or second hash). `.nomedia` folders (WhatsApp Sent) are indexed as plain files that apps can't
open with `setRequireOriginal` (SecurityException), so the zeroed/real check is what decides there.
`CheckedCache` skips files confirmed on an earlier run (same key, size, mtime; ≤ 30 days) — never when deleting
originals (Free up space needs today's exact-hash answer) and never for reads that may have lost GPS.

### Video editor (film icon on Home)
`EditPlan` is the single model: the controls edit it and Claude returns one (strict JSON schema, `explanation` +
every field; `sanitized()` clamps it). Export = Media3 `Composition`: one clipped `EditedMediaItem` per kept segment
(speed via `setSpeed`, camera volume via `ChannelMixingAudioProcessor`, video effects LUT → brightness/contrast/HSL →
rotate → `Presentation` crop/resize), plus a looping music sequence; HDR is tone-mapped to SDR; H.264/AAC into
`Movies/GPic` via MediaStore. `SingleColorLut.createFromCube` indexes `[r][g][b]` (checked in the shader:
row = r·N + g, column = b). DJI's D-Log M → Rec.709 LUT isn't bundled (DJI's download); importing it into the
`dji_dlogm_rec709` slot makes it the default, and a flat-looking `DJI_*` clip opens with it on. Ask Claude:
`claude-opus-5-5`, effort medium, `fallbacks: "default"` (beta `server-side-fallback-2026-07-01`), refusal and
max_tokens handled; metadata + plan + request, frames (12 × 512 px) only when ticked. Key in the encrypted prefs.

### Album suggestions (Albums screen, top-bar icon)
`MediaIndex` (filesDir JSON, singleton) records media key + EXIF capture time/GPS + local Uri for every
file `UploadManager` uploads or matches (before any delete). `AlbumSuggester` (pure, unit-tested) groups
by time on the phone: new group after an 18 h gap; groups spanning > 7 days are re-split at 3 h; min size
3/5/10/20 (`album_min_size`). Default names are dates only (no geocoder: it would send coordinates).
"Create album" is the only network step: `createAlbum` (first 250 keys) + `addMediaToAlbum` batches;
items are then marked with the album and never suggested again; dismissals are stored by group id.
Verified live: a 3-photo album appeared in Google Photos. Older backups join the pool when a folder is
re-run (already-uploaded files hit "Already in library", which also indexes them).

### DJI over USB-C
Action 4 exposes MTP, which Android can't auto-mount: `OpenDocumentTree` (whole DCIM, persisted) or `OpenMultipleDocuments` (multi-file). `DjiScanner` resolves display name/size via DocumentFile + OpenableColumns, sorted smallest-first. Uploads stream straight from the Uri (no temp copy) with resume `skip()`.

### Live stats (Morphe-style)
`SystemStatsCollector` 1s flow on Default dispatcher: CPU via `/proc/stat` delta with `getElapsedCpuTime` fallback (some SELinux blocks hide it → 0%), RAM/storage via `ActivityManager`+`StatFs` (same as Morphe `DeviceStats`), app PSS, `TrafficStats` total+UID speeds, upload B/s from progress deltas, 60-sample histories. `SystemStatsCard` renders bars + canvas sparklines.
