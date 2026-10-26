# AGENTS.md — GPic Android

> This file is the canonical AI context for this repo. It is read by OpenCode (`/init`), Claude Code (`CLAUDE.md` is symlinked to it), Cursor, Codex, and other agents. Keep it concise and actionable.

## Project Overview

**GPic** is an Android companion for [gpic](https://github.com/debakarr/gpic) (Python tool, itself a port of [gotohp](https://github.com/xob0t/gotohp)). It uploads DJI Action 4 photos & videos to Google Photos over USB-C, using the same reverse-engineered internal mobile API (Pixel spoofing for unlimited storage).

- **Package:** `com.gpic.android`
- **App entry:** `MainActivity` → Compose NavHost (home/auth/settings)
- **Current version:** `1.8.0 (21)` — see `app/build.gradle.kts` (`versionCode`/`versionName` + `releaseVersion`)
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
Device test: `./gradlew connectedDebugAndroidTest` runs `TransitionRenderTest` (androidTest): exports a tour of every
transition between photos and between decoded videos, checks pixels at each cut, and plays the preview player through
transitions with seeks (~25 s on an arm64 emulator). Gradle uninstalls the app afterwards; to keep the frames it saves,
`installDebug installDebugAndroidTest` then `adb shell am instrument -w com.gpic.android.test/androidx.test.runner.AndroidJUnitRunner`
and pull `/sdcard/Android/data/com.gpic.android/files/transition-frames`.
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
      dji/      DjiScanner.kt (SAF trees + multi-file pick), FolderSet.kt (several folders: nesting, dedupe; pure), DjiFile.kt, UsbReceiver.kt
      progress/ FileProgress.kt (UploadStatus + ProgressTracker.snapshot())
      stats/    SystemStatsCollector.kt (/proc/stat + TrafficStats), SystemStats.kt
      upload/   UploadManager.kt (one pipeline over MediaSource: hash→dedup→resume→stream→commit), MediaSource.kt, OriginalMedia.kt (unredacted GPS)
      cache/    UploadCache.kt (resume ids) + UploadHistory.kt (name+size → media key, for single-pass sources)
                + CheckedCache.kt (files confirmed in the library: key+size+mtime → media key, 30 days)
      cleanup/  AgeRule.kt (free-up-space age cutoff, EXIF date parsing; pure, unit-tested)
    editor/     EditPlan.kt (parts/texts/music/canvas model + JSON; pure), Timeline.kt (output time ↔ part/clip time,
                split/trim/move/snap-to-beats; pure), Look.kt (filters + Adjust baked into one 3D LUT; pure),
                Beats.kt (on-phone beat tracking; detect() pure), AutoEdit.kt (Quick edit styles; pure),
                CubeLut.kt (.cube parser; pure), LutStore.kt, VideoProbe.kt (metadata, frames, thumbnails, photos),
                VideoExporter.kt (CompositionBuilder shared by preview + export, effects, Transformer, Encoders),
                TransitionMath.kt (transition/Ken Burns maths per frame; pure), TransitionFx.kt (the part shader),
                TextRender.kt (text/sticker bitmaps + animation maths), AudioFx.kt (fades/gain), Openverse.kt (CC music),
                ProjectStore.kt (drafts), ClaudeEditPlanner.kt (prompts, reply parser, API conversation),
                ClaudeEdits.kt (Claude's edit list: labels, apply, summary; pure),
                Templates.kt (VideoTemplate rhythms + MusicPicker mood/track choice; pure),
                Proxies.kt (540p edit copies), VideoAnalyzer.kt (on-phone frame measurements), SmartEdit.kt (shots,
                scoring, duplicates, beat slots; pure), Cards.kt (title-card backgrounds, storyboard sheet)
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
        editor/   EditorScreen.kt (projects, workspace, toolbar, text layer), EditorTimeline.kt, EditorPanels.kt,
                  EditorViewModel (undo/redo, selection, music, Quick edit, Claude, export)
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
Sizes: the DUML manifest's size is u32, so videos over 4 GB (an 8-min 4K60 clip is 6.3 GB) list as 0 B (user report:
0 B, then "Local file missing" from an empty staged copy). `CameraLink.fileSize` asks the HTTP server (Content-Range
total of a 1-byte Range read, HEAD Content-Length) and `SizeProbe` (pure, tested) checks a candidate with Range reads
at size−1 / size (a wrapped size fails: the byte after it exists), else finds the end by doubling + bisection. Run for
0-byte files after listing and for every selected video before a transfer. Not yet tried on a real > 4 GB file.

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

### Several folders (Phone / USB and Free up space)
Both tabs keep a list of SAF trees (`dji_folders` / `cleanup_folders` in `gpic_prefs`, one Uri per line; the old
single-folder keys migrate once). `FolderSet` (pure, tested): a folder inside one already listed adds nothing
(when subfolders are scanned) and adding a parent drops its listed subfolders, with a note; phone-storage tree ids
are paths so this is seen up front, MTP ids are opaque so `DjiScanner.listTrees` also dedupes files by
authority + document id (no double upload or double delete). A folder that lost its permission is skipped with a
note. Removing a folder releases its persisted permission unless the other tab still lists it. `DjiFile.group` is
the folder label: per-folder counts on the list and in the delete confirmation.

### Free up space (Home tab)
SAF tree folders (`DjiScanner.listTrees`, one query per folder, hidden entries skipped) → files whose
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

### Batched library calls (the big speedup for thousands of new files)
Measured from the PC against a real account (scratch script, test items dated 2001-01-01): Google serializes one
account's commits (~1.1 s each; 6 in parallel took 6.5 s), but `CommitUpload.field1` is repeated: 20 items in one
request committed in 1.09 s, replies in request order (verified by hash lookup). `HashCheck.field1.field1` is
repeated too: 100 hashes in 0.6 s, each match echoes its SHA-1. Re-committing identical bytes returns the existing
media key (no duplicate) but the reply doesn't say it already existed, so the per-file duplicate check stays.
`UploadManager` routes every check and commit through `Batcher` (100 hashes / 250 ms, 20 commits / 300 ms; a failed
commit batch falls back to single commits); the upload slot is released before the commit wait; small files
(< 2 MB) get 24 upload slots; checks 32. Library calls are paced app-wide (`Pacer`, AIMD, 8/s start).
On the Redmi 3S: 30 new small files 63.8 s before → 60 new files 11.6 s. `UploadService` holds a partial wake lock
and a WiFi high-perf lock while running. The protos' Java classes are checked in: regenerate with the system
`protoc` 3.21.12 (`protoc --java_out=../java X.proto` from app/src/main/proto).

### Not media / refused by Google
Folder scans skip subfolders named like "WhatsApp Stickers" (`DjiScanner.listTree`, shown as `scanNote`). Before
hashing, `MediaSniff` checks the first 16 bytes against the extension for jpg/png/gif/webp/bmp/heic/avif/mp4/mov/3gp
(RAW and rarer video containers aren't checked); a mismatch is `UploadStatus.REJECTED`, not sent. Google itself
accepted random bytes named .webp (so the sniff matters) and a 1×1 animated WebP; a user saw real WhatsApp stickers
refused at commit (no media key) → `RejectedByPhotosException` → REJECTED too. REJECTED is final (not in Retry, never
deleted) and remembered in `CheckedCache` (`REJECTED` marker) so re-runs don't resend it.

### Video editor (film icon on Home) — CapCut/LightCut-style
Layout taken from LightCut 4.107 / CapCut 19.8 resources (strings + layout trees via aapt2; no code or assets
copied): projects list → workspace = preview, transport (time, play, undo/redo), timeline (ruler, filmstrip main
track with "Sound on" toggle, text/sticker rows, music lane with waveform + beat dots, fixed centre playhead; drag
to scrub with fling, pinch to zoom), bottom tool row that switches with the selection, panels with ✕ / Apply to
all / ✓. Always dark (`EditorColors`).

Model: `EditPlan.segments` are the main-track parts, each with clip range, speed or `SpeedCurve` (24 constant steps;
`Timeline` maths matches Media3: a curved part ended at exactly the computed output time), volume, own `Look` or the
video's, `Motion` (Ken Burns), rotation/mirror and the `Transition` into the next part (moves on both sides of the
cut; dissolves, blur and slides after it; always inside the parts, so length never changes). Plus `texts` (titles +
emoji stickers, output time, animated),
`music` (`MusicTrack`: file, credit, length, volume, start, fades, beats), aspect, `Canvas` (crop / black / blur),
export size + fps. Projects autosave to filesDir/projects/<id> (Google Photos picks and extracted audio copied into
the project; SAF picks keep a persisted grant). Undo/redo = stacks of plans (`commit()` is one step).

Rendering: `CompositionBuilder` builds one `Composition` for the preview (`CompositionPlayer`, short side 480,
text drawn by Compose from the same `TextRender` bitmaps so it can be dragged) and the export (text as a
composition-level `OverlayEffect`). Per-part chain: baked look LUT → rotate/mirror → fit (crop / letterbox /
blur: fit to the picture's size, then `GaussianBlurWithFrameOverlaid`, which GROWS the output by 1/scale, then pin
w×h) → `EdgeBlur` (Blur transition only) → `PartFx` (a part with any transition) or `KenBurnsMotion` matrix (motion
only) → vignette overlay. Time-independent effects are one instance per setting per composition (`Shared`):
Media3's `DefaultVideoFrameProcessor` rebuilds every shader at a cut unless the next item's effect list is equal
(same instances), and `SingleColorLut` frees its texture on release, so never share instances across compositions.
Transitions (earlier builds eased every move to a stop at the cut, slid the picture out to black, dimmed zoom/spin
to 35% and had no crossfade): `PartFx` is one GLSL ES 1.00 pass whose per-frame uniforms come from
`TransitionMath.state` (pure, tested). Moves (zoom in/out, whip, spin) run on both sides of the cut and are fastest at
it (cubic), with motion blur from a 45 ms virtual shutter (up to 16 taps between the shutter's two sampling matrices)
and mirrored edges instead of black; spin turns in aspect-correct space. Dissolve, Blur and Slide need both pictures:
Media3 draws a sequence's items one after another through one pipeline, so the outgoing part's `PartFx` copies its
input frame (glReadPixels into `CarryOver`, a CPU buffer per composition: GL objects die with each part's program)
in its last ~2.5 frame gaps, and the incoming part draws it over itself (its Ken Burns move carried on, clamped so no
edge shows) for the whole transition length (≤ 45% of the part). After a seek into the transition (preview only)
there's no copy: a plain cut. Fade/Flash/Glitch are colour/sampling on both sides. Camera sound fades 12 ms at every
cut (clicks), longer under fades and dissolves (`FadeAudioProcessor`, 16-bit and float PCM). Checked on an Android 15
arm64 emulator (`TransitionRenderTest`, frames looked at), not yet on the Redmi. The preview decodes photos at ≤ 1440
px (`PREVIEW_PHOTO_PX`; Media3's default is 4096). Measured on the Redmi: effects get composition time (part i starts
at `Timeline.starts()[i]`) in both
`CompositionPlayer` and Transformer, with speed applied (`PartClock`). Gotchas: every `EditedMediaItem` needs
`setDurationUs` (whole media length) or `CompositionPlayer` throws; the music sequence must be declared audio-only
(`EditedMediaItemSequence.Builder(setOf(TRACK_TYPE_AUDIO))`, and then no `experimentalSetForceAudioTrack`) or the
preview zooms; `PlayerView` must use RESIZE_MODE_FILL inside a box of the output's shape; after the preview resizes,
`experimentalRedrawLastFrame()`. Camera sound is upmixed to stereo so music stays stereo. Music repeats to cover
the video (no looping sequence) with fades on the first/last piece. Export: H.264 at ~0.1 bit/px/frame
(`Encoders`), sizes limited to what the AVC encoder supports. 22 s at 720p ≈ 30 s on the Redmi; blurred canvas
≈ 5× real time.

Colour: `Look` = camera LUT (DJI D-Log M slot or imported .cube; DJI's isn't bundled) → `Filter` preset (GPic's own
functions, strength) → Adjust sliders, baked by `LookMath.bake` into one 33³ cube per distinct look
(`SingleColorLut.createFromCube` indexes `[r][g][b]`). Filter thumbnails run the same maths on a 96 px frame.
Music: `Openverse` (anonymous: 20/min, 200/day per phone → search only on submit, 24 h cache; license
commercial+modification, ND/NC dropped again; download sniffed by bytes; credit kept beside the file), phone audio,
or a clip's own sound. Beats: `Beats.decodeMono` (11 kHz mono) → spectral flux → autocorrelation tempo (60–180,
weighted to 120) → fractional period/phase comb → snap to onsets (tested on click tracks: < 40 ms). Pace
Fast/Normal/Chillout = every 1/2/4 beats; "Cut on beats" = `Timeline.snapToBeats`. Quick edit cuts are a whole
number of beats (`AutoEdit.beatCutMs`) and the song starts on a beat.
Ask Claude (1.8, ideas from jub0t/concat's MCP agent: AGPL, so ideas only, no code): Claude edits like a person at the
timeline instead of rewriting the plan. The prompt (`ClaudeEditPlanner.chatPrompt`, same text for the Claude app and the
API) has what GPic can do, cutting advice (`ClaudeEdits.CRAFT`), the reply format with one example per edit
(`ClaudeEdits.guide`), the clips, every shot (S#), beats, and a compact summary of the edit (`ClaudeEdits.summary`: P#/T#
labels, output times, only non-default settings). The reply is `{"explanation", "edits": [...]}`; `ClaudeEdits.apply`
(pure, tested) runs montage (shots by label, cut around each shot's peak, kept inside it), set/remove/move_part,
add/set/remove_text, look, audio, format in order as one undo step; labels resolve through `EditRefs` (ids captured when
the prompt was built, so an earlier removal or an edit made between share and paste can't retarget them); a bad edit is
skipped and reported ("edit 3 (set_part P9): there's no P9"). find_music / template / cut_on_beats are `FollowUp`s the
view model runs after (music first, template rebuilds the track then the non-track edits go on top, beats last). Old
whole-plan replies still apply. "Check result in Claude app" shares a result board (`Cards.resultFrame`: a frame from the
middle of each part through `Timeline.clipTime`, rotation, look, canvas fit, vignette, texts as they animate; ≤ 16 frames)
with a self-contained `reviewPrompt` that includes the skipped edits. With an API key the same prompt goes as a
conversation (`ClaudeEditPlanner.ask(turns)`, no structured-output schema any more), then one automatic review turn with
the result board when frames are allowed. API path still untested (no key). The refs are also saved with the project
(`claude-refs.json`, parts by clip range and texts by start+text, since drafts don't keep ids): Android may kill GPic
while the user is in the Claude app. Music settings in the same reply as find_music are applied again once the track
arrives. Tested on the Redmi with Haiku 5.5 (incognito, synthetic clips): it used the edit format as given (montage by
S#, slow-mo, title, find_music, audio, cut_on_beats); after GPic was killed mid-answer the paste still applied with
nothing skipped; on "Check result" it noticed the edit was 12 s of a 15 s request and a flat green part, and fixed both.
Fixed on the way: `Openverse.download` could return a track's `.json` credit (or a `.part`) as the audio when it had been
downloaded before, so beats failed and the preview errored.
Not done: reverse, keyframes, chroma key, voice-over, auto captions, stabilisation, a two-track cross-dissolve where
both clips keep playing (two overlapping sequences; too heavy for old phones: dissolves hold the outgoing frame).
hypit-ai/hypit was looked at: Node + headless Chromium + FFmpeg (desktop/server, can't run on the phone) under a
modified Apache licence that bars commercial redistribution of derivatives, so no code from it.

Edit copies (1.6): clips over 720p, over 60 fps or HEVC get a 540p ≤ 30 fps H.264 copy (keyframe every 0.5 s) in
the project's media/proxies, made one at a time behind a skippable screen with no preview decoding meanwhile (the
Redmi's decoder failed when both ran). `VideoInfo.uri` stays the original; `editUri` (proxy or original) feeds the
preview (`build(useProxies = true)`), thumbnails and analysis; export logs "from originals" and never reads a copy.
Copies can be a few ms longer: clips are clamped to the copy's own length in the preview. Google Photos picks are
still copied in full (its picker only hands over the original file).

Smart edit (1.6, LightCut's one-tap edit without its models): LightCut's native `libmlace_calculator.so` shows its
pipeline — camera-motion shot split (pan/tilt/FPV, "bad jitter"), image aesthetic score, face/person/pet detection
and shot scale, frame-feature clustering of similar shots, template fill (its Java is packed with AppGuard; not
read). CapCut's AutoCut has per-media tags/score/face info plus beat fetch/match nodes and runs on its servers.
GPic: `VideoAnalyzer` samples every 0.5 s up to 2 min (240; exact frames from originals — nearest-keyframe sampling gave
identical frames on long-GOP clips — keyframes from edit copies); longer clips get 40–120 bursts of 4 frames 0.5 s
apart spread over the clip (`sampleTimes`), because motion/shake/action are measured between neighbouring samples
(a 20-min clip used to be sampled every 5 s and read as shaky noise); a burst's first sample is `FrameStat.gap`
(motion taken from the next one; skipped for jitter). Results are also saved in cacheDir/analysis (binary, `VERSION`),
so a long clip is measured once: brightness/contrast, Laplacian sharpness,
colourfulness, global motion from row/column profile shifts, residual "action", histogram change, and
`android.media.FaceDetector` about once a second. `SmartEdit` (tested): shots at hard cuts and lasting motion
changes; quality/interest scores; shaky (motion jitter), blurry (clip-relative, absolute floor, and < ¼ of the
project's median), dark; near-duplicate groups by cosine of colour histogram + 4×4 grey; slots of the style's
beat-length cut: good shots one per group first, then other good shots, then second windows, weak ones last;
story order or best first; ≥ 100 fps shots at half speed; the style's transition on every `transitionEvery`th cut
only (it used to be on every cut: a flash every 5 s in Vlog), still shots get its slow move; a panning shot cuts with a
whip that carries the pan on (tilts: slide), not right after another transition; photos take turns with
`PHOTO_MOVES` and photo-to-photo cuts become the style's `photoTransition` (Dissolve in the calm styles).
Photos added to a project become a slideshow the same way: changing moves, 0.8 s dissolves; up to `MAX_SEGMENTS` 200
parts (was 80, silently dropping the rest of a big photo pick).
Measured on synthetic clips: the shaky clip read motion 0.15 / jitter 0.17, a blur sigma 14 clip 0.0014 sharpness vs
0.013–0.07; zooms and repeating test patterns can read as shake (needs tuning on real footage; `adb logcat -s GPicSmart`).
Generated frames: cover suggestions (best frame per group) save at the original's resolution to Pictures/GPic and
become the project cover; intro/end title cards are photo clips made from the best frame (blurred, darkened;
`gpic-card-*`, skipped by analysis) with editable text items.

Claude without an API key (1.6): Pro/Max plans don't include API use (that's billed in the Anthropic Console).
"Ask in Claude app" shares `ClaudeEditPlanner.chatPrompt` (instructions, plain field list, clips, current plan,
beats, request) plus an optional labelled storyboard (`Cards.storyboard`, via FileProvider) to `com.anthropic.claude`
(or the share menu), and copies the prompt too. "Paste reply" reads the clipboard: ```json block or first balanced
{…}; missing fields stay as they were; one undoable step.
Tested with the real Claude app (1.261015.20) on Haiku 5.5, incognito: the app keeps shared images (one or two
storyboards via SEND_MULTIPLE) but drops EXTRA_TEXT when images come along, so the prompt is on the clipboard;
pasting a long prompt becomes a "Pasted content" attachment, which works. Its JSON block's Copy button + Paste
reply applied the plan. API key requests can choose Opus 5.5 / Sonnet 5.5 / Haiku 5.5 (`claude_model` in gpic_prefs);
Haiku 5.5 has no server-side fallback, so `fallbacks`/beta header go only to the others.
Long clips (1.7): Claude gets the shots found (`shotInfo`: clip, range, camera, quality/action, faces, flags,
look-alike group) as text, at most 120: `SmartEdit.limitShots` gives each clip a share and takes the best shot of
each stretch, so a long clip is covered end to end (it used to be the first 120 in order, i.e. a long clip's
first minutes only); the frames are the peak of the best shots, one per look-alike group first
(`claudeFrames`, up to 32 = two 16-frame storyboards; 24 for the API) instead of evenly spaced samples. Checked on a
2-minute clip: Haiku chose moments from the start, middle and last seconds. Analysis of a 2-min 720p clip took ~100 s
on the Redmi (once a session). Google Photos' own "key moments" aren't available: they aren't in the upload API.

Templates (1.7): `VideoTemplate` = music mood (Openverse query), a rhythm of beat-length slots (transition, speed
curve, camera move each), look, shape, canvas, title/end cards, default length. Applying one: analysis → matching
music if none (`applyAutoMusic`, awaited so beats are known; 120 BPM assumed otherwise) → `slotPlan` →
`SmartEdit.pickSlots` (photos fill slots up to 10 s with the slot's move or `PHOTO_MOVES` in turn, and the
template's `photoTransition` between photos; transition length ≈ a third of the slot, 250–900 ms; ramps get
`MusicPicker.sourceMs` of clip, the harmonic mean of the step speeds) → snap to beats → cards. With few good shots
the edit is shorter than the
template's length. Automatic music elsewhere: Smart edit (switch, on by default) and "Pick for me" / "Another track"
in Music; `MusicPicker.mood` reads action/brightness/colour/camera motion/faces, photos-only → piano/acoustic;
`choose` prefers tracks long enough to cover the video, not huge mixes, non-share-alike.

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
