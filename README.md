# GPic Android

Android companion for [gpic](https://github.com/debakarr/gpic) — upload DJI Action 4 photos & videos to Google Photos over USB-C.

Built as a direct port of the Python `gpic` desktop tool. Credit to [gotohp](https://github.com/xob0t/gotohp) for reverse-engineering the internal Google Photos mobile API.

## What it does

- **DJI Action 4 via USB-C**: plug the camera into your Android phone with a Type-C cable. The camera exposes its storage as MTP. Android can't auto-mount MTP, so GPic uses the Storage Access Framework (SAF) folder picker – you pick the `DCIM` folder once, the app scans all supported files.
- **Smart uploads**: concurrent uploads (auto-tuned), resumable via `Content-Range`, SHA-1 hash dedup (skips files already in your library), progress with speed/ETA, retry with exponential backoff.
- **Auth via existing Google Photos**: uses the same `androidId=...&Email=...&Token=...` string you extract with `adb logcat` (see below). Stored in `EncryptedSharedPreferences`, never leaves the device except to Google's `android.googleapis.com/auth` and `photos.googleapis.com` endpoints.
- **Human-friendly status**: every file shows Queued → Hashing → Checking (already backed up?) → Uploading/Resuming → Finalizing → Done / Already backed up / Failed. Overall stats and per-file progress bars make it obvious what's happening.
- **Morphe-style live stats**: during upload shows CPU %, RAM used/total + app PSS, network ↑/↓ + app UID speeds, upload throughput, storage free, with sparklines (like Morphe Expert patching screen).

## Requirements

- Android 8.0+ (API 26), USB OTG support
- DJI Action 4 + USB-C cable
- Google Photos installed and signed in on the phone
- The `auth string` from your Google Photos session (same as desktop gpic)

## Getting the auth string

Same as desktop gpic README:

```powershell
# On your PC with phone connected via adb
adb logcat -c
adb logcat | Select-String "auth"
# Now open Google Photos on the phone – a line with androidId=...&Email=...&Token=... appears
```

Copy the full line. In GPic Android: **Home → Link** (or Auth screen) → paste → Save. The app marks the account as `Active`. You can store multiple accounts and switch.

Alternative: copy `~/.config/gpic/config.json` from desktop and paste the `auth_string` value.

## Using the app

1. **Plug DJI Action 4 via USB-C** – you should see `USB device: DJI ...` on the home card.
2. **Choose DJI folder** → system picker opens → navigate into the DJI volume → select the `DCIM` folder (or the root if you want everything) → Grant access.
3. The app scans recursively for supported extensions (`jpg`, `dng`, `mp4`, `mov`, … same list as `GooglePhotosAPI.get_supported_extensions()`).
4. Review the file list: `X photos · Y videos · ZZ MB/GB`.
5. **Upload** – FAB appears when files + auth are ready. Tap to start. Each file's card shows live percentage and status.
6. If upload is interrupted, next run resumes from the last byte (persisted `upload_cache.json`, TTL 24h, plus server-side `Range` query).
7. Skipped = `Already in library` (hash match). You can enable `Force re-upload` or `Delete after upload` in Settings.
8. While uploading, a **Live** card shows CPU / RAM / network just like Morphe's patching screen: `Live • uploading 3/20` + `2.4 MB/s`, per-resource bars and tx/rx sparklines.

## Supported files

```
.avif .bmp .gif .heic .heif .ico .jpg .jpeg .png .tif .tiff .webp
.cr2 .cr3 .nef .arw .orf .raf .rw2 .pef .sr2 .dng
.3gp .3g2 .asf .avi .divx .m2t .m2ts .m4v .mkv .mmv .mod .mov .mp4 .mpg .mpeg .mts .tod .wmv .ts
```

## Status meanings (for anyone using the app)

| Chip | What it means |
|------|---------------|
| Queued | Waiting for an upload slot |
| Hashing | Computing SHA-1 to check duplicates |
| Checking | Asking Google if this exact file is already in your library |
| Uploading | Sending bytes to Google (progress bar + % shows live) |
| Resuming | Continuing an interrupted upload from X% |
| Finalizing | Server finalizes the file into your library |
| Done | Uploaded and committed |
| Already backed up | Hash found in library – skipped, no duplicate |
| Failed | Network/server error – will retry, stays red |

Top cards: **Google Photos: you@gmail.com • Ready** and **DJI Action 4 • N files** summarize overall health. FAB shows `Upload N files`, progress bar shows `done / skipped / failed` counts.

## Tech notes

- **Package**: `com.gpic.android`, Kotlin + Compose Material 3, Navigation, OkHttp, protobuf-java (generated from `app/src/main/proto/*.proto`), Room + WorkManager + EncryptedSharedPreferences, DocumentFile (SAF).
- **Upload flow** (`data/upload/UploadManager.kt`): mirrors `gphotos/upload.py` – hash pool → dedup → token (or resume) → streaming PUT with `Content-Range` → commit. Auto thread count same heuristic as Python (`UploadManager.autoThreads`).
- **API** (`data/api/GooglePhotosApi.kt`): mirrors `gphotos/api.py` – `getUploadToken` (with `X-Goog-Hash`), `uploadFile` (chunked `256KB`, progress callback, on-the-fly SHA-1 option), `tryResumeSession` (query `bytes */size`), `commitUpload`, `findMediaByHash`, `createAlbum`.
- **Auth** (`data/auth/AuthManager.kt`): mirrors `gphotos/auth.py` – double-checked locking token cache, `android.googleapis.com/auth` exchange.
- **Cache** (`data/cache/UploadCache.kt`): disk JSON with 24h TTL, same as desktop.
- **DJI** (`data/dji/DjiScanner.kt`): SAF tree scan, plus `UsbManager` status and common mount fallback (`/storage/DCIM`). Takes persistable URI permission.
- **Protobuf**: protos in `app/src/main/proto/` are the same as `gpic/protos/` but with renamed inner types (`InnerField1Type`, `RM*`) to avoid Java duplicate class-name compilation errors while keeping wire format identical.

## Build

```bash
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease # needs keystore.properties
```

SDK: compileSdk 37, minSdk 26, targetSdk 37, AGP 9.1.1, Kotlin 2.3.0.

## Security

- Credentials are stored only in `EncryptedSharedPreferences`; bearer token is in RAM and auto-refreshed.
- On shared devices, remove credentials after use (Auth screen → Remove).
- The internal Photos mobile API is unofficial; use at your own risk (same as desktop gpic/gotohp).

## Roadmap

- Foreground notification for background uploads
- Album auto-creation by folder
- Thumbnail previews via Coil
- Optional official Google Photos Library API OAuth path

## License

MIT – see desktop gpic.
