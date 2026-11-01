#!/usr/bin/env bash
# Unpack the downloaded archives into the toolchain layout build-app.sh expects.
# Run once, after scripts downloaded $TC/dl/*. SDK platform/build-tools are installed
# separately (sdkmanager, needs license acceptance).
set -euo pipefail
TC="${L0CK_TOOLCHAIN:-$HOME/.l0ck-toolchain}"
DL="$TC/dl"

echo "== JDK =="
rm -rf "$TC/jdk"; mkdir -p "$TC/jdk"
tar -xzf "$DL/jdk.tar.gz" -C "$TC/jdk" --strip-components=1
"$TC/jdk/bin/javac" -version

echo "== Gradle =="
rm -rf "$TC/gradle" "$TC/gradle-tmp"; mkdir -p "$TC/gradle-tmp"
unzip -q "$DL/gradle.zip" -d "$TC/gradle-tmp"
mv "$TC"/gradle-tmp/gradle-* "$TC/gradle"; rmdir "$TC/gradle-tmp"
JAVA_HOME="$TC/jdk" "$TC/gradle/bin/gradle" --version | grep -E 'Gradle|JVM'

echo "== Android cmdline-tools =="
rm -rf "$TC/sdk/cmdline-tools"; mkdir -p "$TC/sdk/cmdline-tools/latest-tmp"
unzip -q "$DL/cmdline-tools.zip" -d "$TC/sdk/cmdline-tools/latest-tmp"
mv "$TC/sdk/cmdline-tools/latest-tmp/cmdline-tools" "$TC/sdk/cmdline-tools/latest"
rmdir "$TC/sdk/cmdline-tools/latest-tmp"
JAVA_HOME="$TC/jdk" "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --version

echo "Toolchain unpacked at $TC"
echo "Next: accept the SDK licence, then install platforms;android-35 + build-tools;35.0.0"
