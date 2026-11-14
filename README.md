# Klang

A native Android music player by **Lynli**, written in Kotlin with Jetpack Compose, Material 3 Expressive, and Media3. Android 16 or newer.

## Try it

Install the 0.7.1 test APK on your phone. During this setup session it is available at http://127.0.0.1:8765/klang.apk in the phone browser. Start the tiny download server later with `python3 scripts/serve-apk.py` after copying the APK to `artifacts/Klang-0.7.1.apk`; the server always serves the highest version in `artifacts/`. A simple first-launch screen lets you allow access to local music and optional playback notifications, or choose individual audio files. Klang includes no bundled songs and does not install any music automatically.

Allow **Music & audio** access during onboarding to scan your device. You can also use **Add music** to choose audio documents; Klang copies them to Music/Klang, preserving their embedded tags and artwork. Music access can be changed later in Settings. The app does not ask for all-files access.

## In this version

- Home with a compact My Mix collage, stretched display typography, albums and recently added music.
- My Mix automatically includes all local songs, with a larger dedicated playlist page.
- Saved playlists: create, rename, add/remove songs, reorder and delete in Library → Playlists. Songs can be added from their options menu.
- Expressive album and playlist pages, a morphing play button, a squiggly animated seek bar, and full-page settings.
- Functional LRCLIB lyrics plugin with a persistent toggle, manifest-defined overview, links and settings. Spotify and YouTube Music remain planned integrations.
- Local library, title/artist/album search, albums, sorting and persistent favorites.
- MP3, M4A and other audio formats supported by Android/Media3.
- Embedded artwork and metadata, with immediate memory-cache reuse and persistent, bounded disk thumbnails.
- Animated playing indicators and spring feedback on favorite buttons.
- A fixed Now playing layout with artwork gradient, large rounded transport buttons, a squiggly seek bar and icon actions for playlists/lyrics/Android output/EQ, plus an options menu for queue, properties, go to artist/album and the sleep timer. Enable LRCLIB in Settings → Plugins for synchronized lyrics, soft left-to-right word filling, bloom, tiny completion springs, line-tap seeking and offline caching. Songs with supplied word timestamps use word sync; other timed songs use line sync. Lyrics controls hide after three seconds of inactivity and return on touch. EQ is also accessible through Settings.
- Ten-band service-owned EQ with built-in/custom presets, per-song and Bluetooth-route assignments, smooth changes, automatic headroom, and output trim.
- Background/lock-screen playback, notification controls, audio focus and headphone-unplug pause.
- Seeking, previous/next, shuffle, repeat, queue, play-next and queue removal.
- Collection play buttons pause/resume only for the album or playlist that started the queue, preserving position. Shared songs do not activate other collections; playing those starts their own queue.
- Active album/playlist rows offer play/pause and favorites. Their mini player hides when row controls are visible and returns with an expressive spring when scrolled offscreen. The mini player also includes a favorite control.
- Queue/position restoration after reopening; playback resumes paused.
- System theme by default, with light/dark overrides. Cover colors default on and apply only to Now playing and album pages. Home, Search, Library and Settings stay neutral; wallpaper colors remain optional. The mini player always follows the current cover.
- Song properties from a long-press: a cover-themed sheet with title, artist, album, cover, duration, quality, bitrate, codec/format, file size, location and playlists, plus a Hide from app action. Hidden audio can be reviewed and restored from Settings → Library.
- Radial sleep timer in the Now playing options menu; drag the ring for any length, with quick presets and a live countdown. Cancelled if the app process closes.
- Adaptive navigation and a supporting player pane for wide windows.

Additional providers/plugins are planned; LRCLIB is the first bundled provider. See [plugin API](docs/PLUGINS.md), [roadmap](docs/ROADMAP.md) and [design notes](docs/DESIGN.md).

## Build on this Ubuntu ARM64 phone

The installed toolchain is Java 17, Gradle 9.9.1, Android SDK platform 37.2/build-tools 37, and a static ARM64 aapt2 from lzhiyong/android-sdk-tools 35.0.2. Google supplies x86 Linux native build tools, so use the phone build helper:

```sh
./scripts/build-phone.sh assembleRelease
./scripts/build-phone.sh lintDebug testDebugUnitTest
```

The helper sets Java 17 and overrides aapt2 only on aarch64. On the phone it also sets `android.enableResourceOptimizations=false`: the ARM64 aapt2 build cannot perform AGP 9’s optional resource optimization. Resource shrinking alone does not disable that task. The helper verifies manifest/resources, ZIP integrity, the LRCLIB manifest, alignment and APK signatures after assembly. It does not modify Google's SDK binaries. Resource packaging uses /opt/android-sdk-tools-arm64/build-tools/aapt2; D8 and APK signing run in the JVM. Gradle has two workers, a 1.5GB heap and in-process Kotlin compilation to suit the phone. `JAVA_HOME` is not set globally; other projects can keep their own Java version.

The generated APK is `app/build/outputs/apk/release/app-release.apk`. The debug signing key is local at `~/.android/debug.keystore` and is excluded from Git. Keep it to install later debug builds over this one.

For another machine, install JDK 17 and Android SDK 37.2 with build-tools 37, put its SDK path in `local.properties`, then run `./gradlew assembleDebug`. A GitHub Actions build is included. The wrapper distribution has SHA-256 verification.

`./scripts/setup-phone.sh` documents and reproduces the ARM64 prerequisites.

## Validation

See [validation notes](docs/VALIDATION.md) for the first APK's checks and the remaining on-device trial. There is no emulator/device automatically attached to this proot environment.

Compile SDK: 37.2 (latest available platform). Minimum/target Android: Android 16, API 36. Android Gradle Plugin 9.5.1 uses built-in Kotlin 2.2.10. Compose Material 3 1.5.0-rc01 exposes the Expressive APIs.
