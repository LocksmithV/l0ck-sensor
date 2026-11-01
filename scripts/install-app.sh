#!/usr/bin/env bash
# Install the built sensor APK onto the phone over ADB.
set -euo pipefail
cd "$(dirname "$0")/.."

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
[ -f "$APK" ] || { echo "No APK at $APK — run scripts/build-app.sh first." >&2; exit 1; }

SERIAL="$(adb devices | awk '/\tdevice$/{print $1; exit}')"
[ -n "$SERIAL" ] || { echo "No authorized device. Check 'adb devices'." >&2; exit 1; }

echo "Installing $APK onto $SERIAL ..."
adb -s "$SERIAL" install -r -g "$APK"   # -g pre-grants runtime permissions
echo "Installed cz.l0ck.sensor. Open 'L0ck-Sensor' on the phone."
echo "Set Location to 'Allow all the time' for screen-off scanning."
