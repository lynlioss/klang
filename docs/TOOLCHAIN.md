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
