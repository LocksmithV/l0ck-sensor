#!/usr/bin/env bash
# Build and install the passive sensor app via the laptop's local toolchain.
# The toolchain (JDK, Android SDK, Gradle) lives under ./toolchain and is git-ignored.
# Nothing here is downloaded: the download step is run once, separately, after approval.
set -euo pipefail
cd "$(dirname "$0")/.."

# The toolchain lives on the ext4 home drive, NOT on this exFAT project volume:
# exFAT has no executable bit or symlinks, which breaks aapt2/d8 and the Android SDK.
TC="${L0CK_TOOLCHAIN:-$HOME/.l0ck-toolchain}"
export JAVA_HOME="${JAVA_HOME:-$TC/jdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$TC/sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
GRADLE="${GRADLE:-$TC/gradle/bin/gradle}"
# Build on ext4 too, so AGP's intermediate outputs avoid exFAT permission quirks.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TC/gradle-home}"

[ -x "$JAVA_HOME/bin/javac" ] || { echo "No JDK at $JAVA_HOME — run the toolchain setup first." >&2; exit 1; }
[ -x "$GRADLE" ] || { echo "No Gradle at $GRADLE — run the toolchain setup first." >&2; exit 1; }
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

# Debug build: Gradle signs it with the local debug keystore, so it installs directly.
"$GRADLE" --no-daemon :app:assembleDebug

APK=app/build/outputs/apk/debug/app-debug.apk
echo "Built: $APK"
echo "Install it with scripts/install-app.sh"
