#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${KLANG_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export ANDROID_HOME="${KLANG_ANDROID_SDK:-/opt/android-sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
if [[ $(uname -m) == aarch64 ]]; then
  exec ./gradlew -Pandroid.aapt2FromMavenOverride="${KLANG_AAPT2:-/opt/android-sdk-tools-arm64/build-tools/aapt2}" --no-daemon "$@"
fi
exec ./gradlew --no-daemon "$@"
