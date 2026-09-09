# GPic

An Android companion for [gpic](https://github.com/debakarr/gpic) — upload DJI Action 4 photos & videos to Google Photos over USB-C.

Built as a direct port of the Python `gpic` desktop tool. Credit to [gotohp](https://github.com/xob0t/gotohp) for reverse-engineering the internal Google Photos mobile API.

## Features

- **DJI Action 4 via USB-C** — plug the camera into your phone; pick the whole `DCIM` folder or individual files through the system file picker (persisted across restarts)
- **Smart uploads** — concurrent uploads (auto-tuned thread count), resumable via `Content-Range`, SHA-1 hash dedup (skips files already in your library), retry with exponential backoff
- **Smallest-first order** — quick wins first, so a single GB video can't head-of-line-block dozens of photos
- **No temp copies** — uploads stream straight from the DJI document, with live hash progress and resume support
- **Human-friendly status** — every file shows Queued → Preparing → Hashing → Checking → Uploading/Resuming → Finalizing → Done / Already backed up / Failed, with live percentage, speed and remaining time while uploading (e.g. `Uploading • 2.4 MB/s • 1m 20s left`)
- **Morphe-style live stats** — CPU %, RAM used/total + app PSS, disk used/total + free + app cache, network ↑/↓ + app UID speeds and upload throughput, with sparklines
- **Copyable errors** — each failed file has a copy button (plus long-press selectable text and a Copy-all-errors button) so failures can be pasted back for debugging
- **Auth via existing Google Photos** — paste the same `androidId=...&Email=...&Token=...` string as desktop gpic (see below); stored in `EncryptedSharedPreferences`, with `service=` validation that warns before a guaranteed 403

## Prerequisites

- **JDK 21+**
- **Android SDK** (compileSdk 37) — set `ANDROID_HOME` or let Android Studio manage it
- **A DJI Action 4** + USB-C cable (phone needs USB OTG support, Android 8.0+ / API 26+)
- **Google Photos** installed and signed in on the phone
- **The auth string** from your Google Photos session (same as desktop gpic) — the app cannot read it from the Photos app itself (Android sandbox); see below

## Build Instructions

1. Clone the repo:
   ```bash
   git clone git@github.com:debakarr/gpic-android.git
   cd gpic-android
   ```

2. Make sure `local.properties` in the project root points at your SDK (`make setup` creates a template; it is git-ignored):
   ```properties
   sdk.dir=/path/to/android-sdk
   # optional — only needed for signed release builds:
   keystore.path=gpic.keystore
   keystore.password=gpic123
   keystore.alias=gpic
   ```

3. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   # Windows: gradlew.bat assembleDebug
   ```

4. The APK is written to:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Per-architecture APKs

ABI splits are enabled, so you can also build device-specific APKs:

```bash
./gradlew assembleDebug
```

Outputs in `app/build/outputs/apk/debug/`:

| APK | Architecture |
|---|---|
| `app-arm64-v8a-debug.apk` | Modern phones (most devices) |
| `app-armeabi-v7a-debug.apk` | Older 32-bit ARM devices |
| `app-x86_64-debug.apk` | x86_64 emulators / Chromebooks |
| `app-x86-debug.apk` | x86 emulators |
| `app-universal-debug.apk` | All architectures (largest) |

### Release build

```bash
make keystore   # once: self-signed local key (git-ignored)
./gradlew versionApks
```

Versioned, signed APKs land in `app/build/outputs/apk/versioned/`:

| APK | Architecture |
|---|---|
| `GPic-<version>-arm64-v8a.apk` | Modern phones (most devices) |
| `GPic-<version>-armeabi-v7a.apk` | Older 32-bit ARM devices |
| `GPic-<version>-x86_64.apk` | x86_64 emulators / Chromebooks |
| `GPic-<version>-x86.apk` | x86 emulators |
| `GPic-<version>-universal.apk` | All architectures (largest) |

> The release build signs with `local.properties` `keystore.*` when set — configure it (or run `make keystore`) before distributing. Or use Android Studio's Generate Signed Bundle/APK wizard.

## Install

```bash
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## Getting the auth string

Same credential as desktop gpic. On your PC with the phone connected via adb, capture the Photos internal line — it **must** contain `photos.native` (a `userinfo.profile` line 403s every call):

```bash
# Linux / Mac
adb logcat -c
adb logcat | grep -i "photos.native"
# Windows (PowerShell)
adb logcat -c
adb logcat | Select-String "photos.native"
# ...or shortcut:
make auth-log
```

Now open Google Photos on the phone and copy the FULL line from `androidId=` to the end (`androidId=...&Email=...&Token=...&service=...photos.native...`).

In GPic: **Home → Link** (or the Auth screen) → paste → Save. The app shows the detected `service=` and warns if it doesn't look like Photos. You can store multiple accounts and switch the active one.

Alternative: copy `~/.config/gpic/config.json` from desktop and paste the `auth_string` value.

## Using the app

1. **Plug DJI Action 4 via USB-C** — the home card shows the detected USB device.
2. **Choose folder OR files** — `Choose folder` for the whole `DCIM` (recursive) or `Choose files` for individual photos/videos (multi-select). Access is persisted.
3. Review the list: `X photos · Y videos · ZZ MB/GB`, always smallest-first.
4. **Upload** — the FAB appears when files + auth are ready. Each file card shows live percentage plus speed and remaining time.
5. Interrupted uploads resume from the last byte (persisted `upload_cache.json`, 24h TTL, plus server-side `Range` query).
6. `Already in library` = SHA-1 hash matched, skipped without re-upload. `Force re-upload` and `Delete after upload` live in Settings.

## Status meanings

| Status | What it means |
|---|---|
| Queued | Waiting for an upload slot |
| Preparing | Opening the DJI file (brief — streams directly, no copy) |
| Hashing | Computing SHA-1 for duplicate detection (live %) |
| Checking | Asking Google if this exact file is already in your library |
| Uploading | Sending bytes (live % + speed + remaining time) |
| Resuming | Continuing an interrupted upload from X% |
| Finalizing | Server is committing the file into your library |
| Done | Uploaded and committed |
| Already backed up | Hash found in library — skipped, no duplicate |
| Failed | Tap the copy icon on the row (or Copy-all-errors) and paste it back for debugging |

## How uploading works

The app talks to the same internal Google Photos mobile endpoints as desktop `gpic`/`gotohp` (Pixel spoofing for unlimited storage): master-token → bearer via `android.googleapis.com/auth`, upload-ID via `photos.googleapis.com/data/upload/...`, dedup via `HashCheck`, commit via `photosdata-pa` protobufs. Retries use exponential backoff and fail fast on 4xx (except 429); errors carry `[endpoint]` tags (`getUploadToken`/`hashCheck`/`commitUpload`/PUT) so failures are diagnosable. Replies are gunzipped before proto parsing, and dedup-check parse failures fall back to uploading (dedup is best-effort).

Supported files: `.avif .bmp .gif .heic .heif .ico .jpg .jpeg .png .tif .tiff .webp` · `.cr2 .cr3 .nef .arw .orf .raf .rw2 .pef .sr2 .dng` · `.3gp .3g2 .asf .avi .divx .m2t .m2ts .m4v .mkv .mmv .mod .mov .mp4 .mpg .mpeg .mts .tod .wmv .ts`

## Troubleshooting

- **`UNREGISTERED_ON_API_CONSOLE` (HTTP 400 at auth):** you do NOT need your own SHA-1/OAuth client — GPic reuses the Photos app registration. Re-copy the FULL logcat line *with* `service=...`, reopen Photos once, paste again. The save screen rejects strings missing `androidId`/`Email`/`Token`.
- **403 right after hashing (Checking/Uploading/Finalizing):** bearer is valid but the scope is denied — the pasted `service=` is almost certainly `userinfo.profile` (a Google-login line) instead of `photos.native`. Re-capture with the `photos.native` filter above; the per-file error shows the detected `service=` to confirm.
- **`Protocol message tag had invalid wire type` at Checking:** fixed in v1.3.5+ (gunzip before proto parse, dedup fallback). Update the app.
- **Stuck at Preparing:** fixed in v1.3.0+ (direct Uri streaming, no temp copy). If it still sticks, the error now names the cause (e.g. permission lost → re-pick folder/files).
- **CPU always 0%:** fixed in v1.3.0+ (`getElapsedCpuTime` fallback where `/proc/stat` is SELinux-blocked).

## Disclaimer

GPic uses the unofficial internal Google Photos mobile API (same as desktop `gpic`/`gotohp`), not the official Photos Library API. Use at your own risk: Google can change or block it at any time, and Pixel-spoofed unlimited uploads violate Google's ToS. Credentials stay on-device (encrypted prefs + in-RAM bearer) and are only sent to Google endpoints.

## License

MIT — see desktop [gpic](https://github.com/debakarr/gpic).
