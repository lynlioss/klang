# Ubuntu proot build environment

`neofetch --stdout`:

```
OS: Ubuntu 26.04.1 LTS aarch64
Host: POCO 2412DPC0AG
Kernel: 6.17.0-PRoot-Distro
CPU: (8) @ 2.100GHz
Memory: 7308MiB total
```

JDK 17 is installed at `/usr/lib/jvm/java-17-openjdk-arm64`. System Java remains version 25; the build helper selects Java 17 locally. Google command-line tools are at `/opt/android-sdk/cmdline-tools/latest`, with platform 37.2 and build-tools 37 in that SDK. Gradle 9.9.1 is installed at `/opt/gradle/gradle-9.9.1` and available through the committed wrapper.

Ubuntu's `/usr/bin/aapt2` cannot parse SDK 36's resource table. The phone uses the static ARM64 build from [lzhiyong/android-sdk-tools 35.0.2](https://github.com/lzhiyong/android-sdk-tools/releases/tag/35.0.2) at `/opt/android-sdk-tools-arm64/build-tools/aapt2` via the Gradle override property. The setup script verifies the downloaded archive's SHA-256. SDK files are unmodified. Compilation, dexing and signing use Java. No x86 emulation or Android Studio is required.

`adb devices` currently has no attached device. Running proot on a phone does not automatically grant ADB access to the host Android system. APK signing/package validation can run here, while interactive playback, notification, permission and screen tests require installing the APK on Android.

`neofetch` was installed from dylanaraps/neofetch, since Ubuntu's current repositories contain fastfetch instead.

The first AGP 8/SDK 36 build compiled successfully. At Lynli's request, the final toolchain uses the latest available compile SDK (37.2), AGP 9.5.1 and Gradle 9.9.1. The app minimum and target are API 36 (Android 16).

## ARM64 release resource packaging

The static ARM64 aapt2 supports resource linking but its optional `optimize` command did not produce AGP 9’s requested output. A task could report success while the APK omitted its manifest and resource table. Setting `isShrinkResources=false` alone did not stop that optimization task.

The phone helper now passes `-Pandroid.enableResourceOptimizations=false`, in addition to the aapt2 override. AGP 9.5.1 accepts this legacy option with a deprecation warning. R8 code shrinking stays enabled. The helper runs `scripts/verify-apk.py` after assembly, rejecting missing manifest/resources, corrupt or duplicate ZIP entries, mismatched starter assets, misalignment and invalid signatures. Builds on x86 Linux use Google’s own aapt2 and do not need the phone-only option. If a later AGP removes the legacy option, this workaround will need an updated ARM64 tool or packaging path.
