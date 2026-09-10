# GPic

Android app that uploads DJI Action 4 photos and videos to Google Photos over USB-C. Companion to [gpic](https://github.com/debakarr/gpic) (the Python version). The upload protocol is a port of [gotohp](https://github.com/xob0t/gotohp) by [xob0t](https://github.com/xob0t), who did the reverse engineering work on the internal Google Photos mobile API.

## Features

- Pick the whole `DCIM` folder or individual files from the DJI camera through the system file picker (access is kept across restarts)
- Concurrent uploads with automatic thread count, resume from last byte via `Content-Range`, SHA-1 dedup against files already in the library, retry with backoff
- Smallest files upload first
- Streams straight from the camera, no temp copies
- Per-file status with live percent, speed and time left while uploading (e.g. `Uploading • 2.4 MB/s • 1m 20s left`)
- Live CPU / RAM / disk / network panel during uploads, with sparklines
- Copy button on every failed file (plus long-press to select text, and copy-all) for debugging failures

## Privacy & Data

- **No analytics, no crash reporting, no ads, no third-party SDKs.** Dependencies are AndroidX, OkHttp, Gson, protobuf and Coil only.
- **Your photos go from the camera to your phone to Google, nowhere else.** The app talks only to Google endpoints: `android.googleapis.com/auth` (token exchange), `photos.googleapis.com` (uploads) and `photosdata-pa.googleapis.com` (library calls).
- **The Photos credential you paste** (`androidId`/`Email`/`Token`) is kept in `EncryptedSharedPreferences` on the device and sent only to Google. The short-lived bearer token lives in RAM and is refreshed as needed. Nothing is uploaded anywhere except Google Photos.
- **Local state** is an upload-resume cache (`upload_cache.json`, 24h expiry) in the app cache dir, plus your picker selection and settings. Clearing app data wipes all of it.
- **Permissions** used: `INTERNET`, `ACCESS_NETWORK_STATE`, foreground-service + notifications (to keep uploads alive in background), `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`, and USB host (to detect the camera). `MANAGE_DOCUMENTS` is declared but is signature-level, so the system ignores it for this app.
- The app cannot read your Photos login by itself (Android sandbox). That is why setup asks you to paste the auth string manually.

## Prerequisites

- **JDK 21+**
- **Android SDK** (compileSdk 37) — set `ANDROID_HOME` or let Android Studio manage it
- **DJI Action 4** + USB-C cable (phone needs USB OTG, Android 8.0+ / API 26+)
- **Google Photos** installed and signed in on the phone
- **The auth string** from your Google Photos session (same one desktop gpic uses)

## Build Instructions

1. Clone the repo:
   ```bash
   git clone git@github.com:debakarr/gpic-android.git
   cd gpic-android
   ```

2. Point `local.properties` at your SDK (`make setup` creates a template; the file is git-ignored):
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

ABI splits are on, so one build also produces device-specific APKs in `app/build/outputs/apk/debug/`:

| APK | For |
|---|---|
| `app-arm64-v8a-debug.apk` | Modern phones (most devices) |
| `app-armeabi-v7a-debug.apk` | Older 32-bit ARM devices |
| `app-x86_64-debug.apk` | x86_64 emulators / Chromebooks |
| `app-x86-debug.apk` | x86 emulators |
| `app-universal-debug.apk` | Everything (largest) |

### Release build

```bash
make keystore   # once: local self-signed key (git-ignored)
./gradlew versionApks
```

Signed, versioned APKs land in `app/build/outputs/apk/versioned/` (`GPic-<version>-<abi>.apk`). Without `keystore.*` set the release build is unsigned — use Android Studio's Generate Signed Bundle/APK wizard instead.

## Install

```bash
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## Getting the auth string

On your PC with the phone on adb, capture the Photos line. It **must** contain `photos.native` — a `userinfo.profile` line fails every call with 403:

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

Open Google Photos on the phone, copy the FULL line from `androidId=` to the end.

In the app: **Home → Link** (or the Auth screen) → paste → Save. It shows the detected `service=` and warns if it is not a Photos scope. Multiple accounts can be stored; one is active at a time.

You can also copy `~/.config/gpic/config.json` off a desktop that already runs gpic and paste the `auth_string` value.

## Using the app

1. Plug the Action 4 in over USB-C. The home card shows the USB device if detected.
2. `Choose folder` for all of `DCIM` (recursive) or `Choose files` for specific shots.
3. Check the list (`X photos · Y videos · ZZ MB/GB`), then hit Upload.
4. Each file card shows live percent, speed and time left. Interrupted uploads resume.
5. `Already in library` means the SHA-1 matched and the file was skipped. `Force re-upload` and `Delete after upload` are in Settings.

## Status meanings

| Status | Meaning |
|---|---|
| Queued | Waiting for a free upload slot |
| Preparing | Opening the file on the camera |
| Hashing | Computing SHA-1 for the duplicate check |
| Checking | Asking Google if the file is already in the library |
| Uploading | Sending bytes (percent + speed + time left) |
| Resuming | Continuing an interrupted upload from X% |
| Finalizing | Server is committing the file to the library |
| Done | Uploaded and committed |
| Already backed up | Hash matched, skipped |
| Failed | Copy icon on the row copies the full error for debugging |

## How uploading works

Same endpoints as desktop `gpic`/`gotohp` with Pixel spoofing: master token → bearer at `android.googleapis.com/auth`, upload ID from `photos.googleapis.com/data/upload/...`, duplicate check via `HashCheck`, commit via `photosdata-pa` protobufs. 4xx errors (except 429) fail fast with an `[endpoint]` tag (`getUploadToken` / `hashCheck` / `commitUpload` / PUT) so a failure says where it happened. Replies are gunzipped before proto parsing; if the duplicate check itself comes back garbled the file uploads anyway (dedup is best-effort).

Photos and videos supported: `.avif .bmp .gif .heic .heif .ico .jpg .jpeg .png .tif .tiff .webp` · `.cr2 .cr3 .nef .arw .orf .raf .rw2 .pef .sr2 .dng` · `.3gp .3g2 .asf .avi .divx .m2t .m2ts .m4v .mkv .mmv .mod .mov .mp4 .mpg .mpeg .mts .tod .wmv .ts`

## Troubleshooting

- **`UNREGISTERED_ON_API_CONSOLE` (HTTP 400 at auth):** no OAuth client or SHA-1 setup needed on your side — the app reuses the Photos app registration. Re-copy the FULL logcat line *with* `service=...`, reopen Photos once, paste again. Strings missing `androidId`/`Email`/`Token` are rejected at save time.
- **403 after hashing (Checking/Uploading/Finalizing):** the token works but the scope is wrong — the pasted `service=` is almost always `userinfo.profile` (a login line) instead of `photos.native`. Re-capture with the filter above; the error shows the detected `service=` so you can confirm.
- **`Protocol message tag had invalid wire type` at Checking:** fixed in v1.3.5+. Update the app.
- **Stuck at Preparing:** fixed in v1.3.0+ (direct streaming, no temp copy). A stuck file now fails with the reason instead (e.g. permission lost → re-pick the folder/files).
- **CPU always 0%:** fixed in v1.3.0+ (fallback where `/proc/stat` is blocked).

## Disclaimer

This uses the unofficial internal Google Photos mobile API, not the official Library API. Google can change or block it at any time, and spoofed unlimited uploads are against Google's ToS. Use at your own risk.

## License

MIT — same as desktop [gpic](https://github.com/debakarr/gpic).
