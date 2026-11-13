# Klang

A native Android music player by **Lynli**, written in Kotlin with Jetpack Compose, Material 3 Expressive, and Media3. Android 16 or newer.

## Try it

Install the 0.1.0 debug APK on your phone. On first launch, Klang copies its three bundled NCS tracks to **Music/Klang** using Android MediaStore. The tracks are delivered with the APK, so first-run installation works offline. The files are visible to other music apps and remain in your Music folder after uninstalling Klang. Reinstalling the starter pack skips existing completed files.

Tap **Find my music** to grant music access and scan the rest of your device. You can also use **Add music** to choose audio documents; Klang copies them to Music/Klang, preserving their embedded tags and artwork. The app does not ask for all-files access.

## In this version

- Reference-inspired Your Mix home, oversized typography, sculptural artwork and a morphing play button.
- Local library, title/artist/album search, albums, sorting and persistent favorites.
- MP3, M4A and other audio formats supported by Android/Media3.
- Embedded artwork and metadata, fallback art for untagged tracks.
- Background/lock-screen playback, notification controls, audio focus and headphone-unplug pause.
- Seeking, previous/next, shuffle, repeat, queue, play-next and queue removal.
- Queue/position restoration after reopening; playback resumes paused.
- Dark, light and system themes; optional Material You wallpaper colors .
- Sleep timer (15/30/60 minutes), cancelled if the app process closes.
- Adaptive navigation and a supporting player pane for wide windows.

Future providers/plugins are planned; the first version focuses on local playback. See [roadmap](docs/ROADMAP.md), [design notes](docs/DESIGN.md) and [music credits](docs/MUSIC-CREDITS.md).

## Build on this Ubuntu ARM64 phone

The installed toolchain is Java 17, Gradle 9.9.1, Android SDK platform 37.2/build-tools 37, and a static ARM64 aapt2 from lzhiyong/android-sdk-tools 35.0.2. Google supplies x86 Linux native build tools, so use the phone build helper:

```sh
./scripts/build-phone.sh assembleDebug
./scripts/build-phone.sh lintDebug testDebugUnitTest
```

The helper sets Java 17 and overrides aapt2 only on aarch64. It does not modify Google's SDK binaries. Resource packaging uses /opt/android-sdk-tools-arm64/build-tools/aapt2; D8 and APK signing run in the JVM. Gradle has two workers, a 1.5GB heap and in-process Kotlin compilation to suit the phone. `JAVA_HOME` is not set globally; other projects can keep their own Java version.

The generated APK is `app/build/outputs/apk/debug/app-debug.apk`. The debug signing key is local at `~/.android/debug.keystore` and is excluded from Git. Keep it to install later debug builds over this one.

For another machine, install JDK 17 and Android SDK 37.2 with build-tools 37, put its SDK path in `local.properties`, then run `./gradlew assembleDebug`. A GitHub Actions build is included. The wrapper distribution has SHA-256 verification.

`./scripts/setup-phone.sh` documents and reproduces the ARM64 prerequisites. `scripts/prepare-starter.py` is a developer utility for the originally attached files; it is not needed to build from the repo because the prepared pack is committed.

## Validation

See [validation notes](docs/VALIDATION.md) for the first APK's checks and the remaining on-device trial. There is no emulator/device automatically attached to this proot environment.

Compile SDK: 37.2 (latest available platform). Minimum/target Android: Android 16, API 36. Android Gradle Plugin 9.5.1 uses built-in Kotlin 2.2.10. Compose Material 3 1.5.0-rc01 exposes the Expressive APIs.
