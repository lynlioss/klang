#!/usr/bin/env bash
set -euo pipefail
# Run as root inside Ubuntu proot. SDK licenses are accepted as part of setup.
# The toolchain is installed into a git-ignored .android-tools/ folder.
cd "$(dirname "$0")/.."
root=$PWD
apt-get update
apt-get install -y openjdk-17-jdk-headless curl unzip aapt2 adb apksigner zipalign git gh python3-mutagen python3-pil
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
klang_sdk=${KLANG_ANDROID_SDK:-$root/.android-tools/sdk}
arm_tools=${KLANG_ARM64_TOOLS:-$root/.android-tools/arm64}
mkdir -p "$klang_sdk/cmdline-tools"
if [[ ! -x "$klang_sdk/cmdline-tools/latest/bin/sdkmanager" ]]; then
  curl -fL --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o /tmp/klang-commandlinetools.zip
  unzip -q /tmp/klang-commandlinetools.zip -d "$klang_sdk/cmdline-tools"
  mv "$klang_sdk/cmdline-tools/cmdline-tools" "$klang_sdk/cmdline-tools/latest"
fi
set +o pipefail
yes | "$klang_sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$klang_sdk" 'platforms;android-37.2' 'build-tools;37.0.0'
result=${PIPESTATUS[1]}
set -o pipefail
if [[ $result -ne 0 ]]; then exit "$result"; fi
if [[ ! -x "$arm_tools/build-tools/aapt2" ]]; then
  mkdir -p "$arm_tools"
  curl -fL --retry 2 https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip -o /tmp/klang-sdk-arm64.zip
  printf '%s  %s\n' 'db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2' '/tmp/klang-sdk-arm64.zip' | sha256sum -c -
  unzip -q /tmp/klang-sdk-arm64.zip -d "$arm_tools"
fi
printf 'sdk.dir=%s\n' "$klang_sdk" > local.properties
./scripts/build-phone.sh assembleDebug
