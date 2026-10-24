# Full Deck — Retro Hi-Fi, Android v0.3

**Current status:** Android Studio source project, **not a precompiled APK**. In the authoring environment, Android SDK and Gradle are unavailable, so the full app has **not** been compiled or tested on physical Android hardware. Do not mistake passing standalone shuffle-engine unit tests for full app testing.

## What this Android version includes

- A genuine native-Android turntable (`TurntableView.java`), not a WebView. The record's asymmetric label rotates at approximately 33⅓ RPM **only while audio plays**. The tonearm is a rigid assembly rotating around a single pivot. Its stylus position tracks `MediaPlayer`'s playback position/duration, moving from the outer groove to inner groove. Pause freezes the record and stylus; a new song resets the needle.
- Local MP3/audio via Android's indexed MediaStore library, including *normal* folders and nested subfolders, with scanning in a background worker. Music hidden by `.nomedia`, in restricted app-private directories, or not indexed by Android may be missing; manual import of hidden folders is not implemented yet.
- Background playback through a media foreground service, notification controls, seek, previous/next, and saved deck progress.
- Once-through no-repeat shuffle, **Random Next** from remaining unplayed tracks, and best-effort 3-song artist spacing on initial shuffled rounds. Avoids repeating the immediately prior track at deck boundary. Artist-spacing cannot be guaranteed when there are too few different artists.
- Both **Remove from this shuffle** (keeps the file) and **Delete MP3 permanently** (Android system approval on Android 11+). Use the latter carefully.
- Android 8+; compile/target Android API 35; Java 17.

## Easiest way to produce an installable debug APK: GitHub Actions

This project includes `.github/workflows/build-apk.yml` to build the APK in a free cloud runner (subject to GitHub's current limits and availability) without setting up Android Studio on your computer.

1. Create a **private** GitHub repository for Full Deck and choose the default `main` branch.
2. Extract this ZIP, then upload the **contents** of `FullDeck_Retro_Android_Project` (not the outer folder) into the repository, including `.github/workflows/build-apk.yml`.
3. In GitHub, open **Actions** → **Build Full Deck Android APK** → **Run workflow**. If the initial push triggered a build already, use that run.
4. After it completes successfully, open that workflow run → **Artifacts** → **FullDeck-Retro-Android-APK**. Download and unzip the artifact; it contains **app-debug.apk**.
5. Copy `app-debug.apk` to the Android phone, open it from your Files app, and allow installation from that source if prompted. Android might warn that the app is unknown because it's a debug build. Review the package/source and only install if you trust the build. When Full Deck starts, grant access to Music/audio.

You can alternatively open this project in Android Studio with SDK 35 and Gradle 8.7; use **Build → Build APK(s)** to create `app/build/outputs/apk/debug/app-debug.apk`. The authoring environment couldn't supply a standard `gradle-wrapper.jar` or compile the APK; on a local machine you may need to configure Gradle 8.7 rather than relying on a wrapper. The GitHub workflow handles this by installing Gradle directly.

## Tests run here (Java shuffle only)

```sh
mkdir -p out
javac -d out app/src/main/java/com/fulldeck/player/{Song,ShuffleDeck}.java tests/*.java
java -cp out ShuffleDeckTest
java -cp out LargeLibraryTest
java -cp out ArtistSpacingTest
```

The `Full_Deck_Retro_Preview.html` file is a desktop/mobile browser preview from the previous design; it is included for visual comparison. The Android screen has been recreated natively, so it may differ slightly from the HTML preview.

## Files

- `app/src/main/java/com/fulldeck/player/TurntableView.java` — native rotating vinyl and physical tonearm rendering
- `MainActivity.java` — native retro interface bindings, delete confirmation, scanning and permissions
- `PlaybackService.java` — local audio player and device MediaStore scanner
- `ShuffleDeck.java` — saved nonrepeating shuffle and artist spacing
- `.github/workflows/build-apk.yml` — automated APK build

Known missing features: manual import of unindexed folders, optional SD-card permission overrides, fully configurable folder filters, scanned album artwork, direct on-phone installation tests, and a signed production/release APK.
