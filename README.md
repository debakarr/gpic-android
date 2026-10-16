# GPic

Android app that uploads photos and videos to Google Photos — from a DJI Action 4 over USB-C, any other USB storage, or the phone itself. Companion to [gpic](https://github.com/debakarr/gpic) (the Python version). The upload protocol is a port of [gotohp](https://github.com/xob0t/gotohp) by [xob0t](https://github.com/xob0t), who did the reverse engineering work on the internal Google Photos mobile API.

## Features

- Pick any source through the system file picker: the whole `DCIM` folder off a DJI Action 4, any other USB drive or SD card, or individual photos and videos already on the phone (access is kept across restarts)
- Concurrent uploads with automatic thread count, resume from last byte via `Content-Range`, SHA-1 dedup against files already in the library, retry with backoff
- Smallest files upload first
- Streams straight from the source, no temp copies
- Per-file status with live percent, speed and time left while uploading (e.g. `Uploading • 2.4 MB/s • 1m 20s left`)
- Live CPU / RAM / disk / network panel during uploads, with sparklines
- Copy button on every failed file (plus long-press to select text, and copy-all) for debugging failures
- Keeps photo locations: reads the original file (Android strips GPS from photos for apps without media-location access), and re-uploads photos an earlier version sent without their location
- **Free up space**: back up a folder's photos and videos older than N days / weeks / months / years, then delete them from the phone (only once Google Photos has them)
- File-type filter (Photos / RAW / Videos) for every source
- **Album suggestions**: groups the photos GPic backed up by when they were taken, on the phone, and creates the album in Google Photos when you tap Create (one at a time, or tick several and create them all)
- Fast re-runs over big folders (a WhatsApp folder with thousands of images): one query per folder to scan, up to 8 "already backed up?" checks at once, and files confirmed on an earlier run are skipped without asking Google again
- **Video editor**: trim (or keep several parts), speed, colour LUT (DJI D-Log M → Rec.709 for Osmo Action 4 clips, or any `.cube`), brightness / contrast / saturation, camera-sound volume, background music, crop to 16:9 / 9:16 / 1:1, resize, rotate; exports H.264 to `Movies/GPic` on the phone. Optional **Ask Claude**: describe the edit and Claude sets the controls, using your own Anthropic API key

## Privacy & Data

- **No analytics, no crash reporting, no ads.** Dependencies are AndroidX (incl. Media3 for video editing), OkHttp, Gson, protobuf, Coil and Anthropic's Java SDK (used only by Ask Claude).
- **Your photos go from your storage to Google, nowhere else.** Uploads talk only to Google endpoints: `android.googleapis.com/auth` (token exchange), `photos.googleapis.com` (uploads) and `photosdata-pa.googleapis.com` (library calls).
- **Ask Claude (video editor, optional)** talks to `api.anthropic.com` only when you tap it, with your own API key (stored in `EncryptedSharedPreferences`). It sends the video's name, length, size and frame rate, the current edit settings, the LUT names, the music track's file name and your request. With "Let Claude see frames" ticked it also sends 12 small JPEG frames (512 px). The video file itself is never sent; editing and export happen on the phone.
- **The Photos credential you paste** (`androidId`/`Email`/`Token`) is kept in `EncryptedSharedPreferences` on the device and sent only to Google. The short-lived bearer token lives in RAM and is refreshed as needed. Nothing is uploaded anywhere except Google Photos.
- **Local state** is an upload-resume cache (`upload_cache.json`, 24h expiry) in the app cache dir, an upload history, a list of files confirmed in Google Photos (path, size, date and media key; 30 days, so re-runs can skip them), imported LUTs, and a media index (media key, capture time, GPS and local file link of what GPic backed up; used only for album suggestions, never sent anywhere), plus your picker selection and settings. App backups are off. Clearing app data wipes all of it.
- **Album suggestions** are computed on the phone. Creating an album sends Google the album name and which of your photos go in it — nothing else. Album names are dates; GPic doesn't look up place names, which would send coordinates to a server.
- **Permissions** used: `INTERNET`, `ACCESS_NETWORK_STATE`, foreground-service + notifications (to keep uploads alive in background), `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` + `ACCESS_MEDIA_LOCATION` (`READ_EXTERNAL_STORAGE` on Android 12 and older) so photos keep their location, USB host (to detect the camera), and Bluetooth + nearby WiFi for camera over WiFi.
- The app cannot read your Photos login by itself (Android sandbox). That is why setup asks you to paste the auth string manually.

## Prerequisites

- **JDK 21+**
- **Android SDK** (compileSdk 37) — set `ANDROID_HOME` or let Android Studio manage it
- **Something to upload from** — DJI Action 4 over USB-C, any USB storage or SD card, or photos already on the phone (USB OTG needed for external storage, Android 8.0+ / API 26+)
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

1. Connect the source — plug the Action 4 or a USB drive in over USB-C, or skip this for photos already on the phone. The home card shows a detected USB device if there is one.
2. `Choose folder` for a whole folder (recursive — `DCIM` or anything else) or `Choose files` for specific shots.
3. Check the list (`X photos · Y videos · ZZ MB/GB`), then hit Upload.
4. Each file card shows live percent, speed and time left. Interrupted uploads resume.
5. `Already in library` means the SHA-1 matched and the file was skipped. `Force re-upload` and `Delete after upload` are in Settings.

## Video editor

Film icon at the top of the home screen. Choose a video, then:

- **Trim** with the slider; Claude can keep several parts (tap a part to drop it). The preview plays the cut, speed, colour and camera volume; music is only in the export.
- **Colour LUT**: for Osmo Action 4 footage shot in D-Log M, get DJI's free *D-Log M to Rec.709* LUT from [DJI's Action 4 download page](https://www.dji.com/osmo-action-4/downloads) and tap *Add DJI LUT*. GPic doesn't bundle it (it's DJI's file); once added it's the default, and a DJI clip that looks flat opens with it on. Until then GPic's own approximate "Flat → vivid" look stands in. Any other 3D `.cube` LUT can be added too.
- **Export** writes an H.264/AAC MP4 to `Movies/GPic`; *Back up* puts it in the Phone list for upload. HDR clips are tone-mapped to SDR. Old phones may not decode 4K or 10-bit HEVC (the Action 4's high-quality modes); the error says so.
- **Ask Claude**: add an Anthropic API key (console.anthropic.com), describe the edit, and Claude (`claude-opus-5-5`) fills in the controls and says what it changed. Nothing is exported until you tap Export. If Anthropic's safety filter declines a request, the API retries it on a fallback model (`fallbacks: "default"`).

## Status meanings

| Status | Meaning |
|---|---|
| Queued | Waiting for a free check or upload slot |
| Preparing | Opening the file on the camera |
| Hashing | Computing SHA-1 for the duplicate check |
| Checking | Asking Google if the file is already in the library |
| Uploading | Sending bytes (percent + speed + time left) |
| Resuming | Continuing an interrupted upload from X% |
| Finalizing | Server is committing the file to the library |
| Done | Uploaded and committed |
| Already backed up | Hash matched, skipped (or confirmed on an earlier run and unchanged since) |
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
