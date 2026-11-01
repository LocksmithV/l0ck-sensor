#!/usr/bin/env bash
# PREPARED, NOT WIRED LIVE. Pull the sensor's NDJSON logs off the phone over ADB into ./logs/phone.
# This only reads from the phone; it sends nothing anywhere. Optional push to a remote host is a
# separate, explicit step below and is OFF unless you pass --send.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=cz.l0ck.sensor
REMOTE="/sdcard/Android/data/$PKG/files/logs"
DEST="logs/phone/$(date +%Y%m%dT%H%M%S)"
SEND=0
[ "${1:-}" = "--send" ] && SEND=1

SERIAL="$(adb devices | awk '/\tdevice$/{print $1; exit}')"
[ -n "$SERIAL" ] || { echo "No authorized device." >&2; exit 1; }

mkdir -p "$DEST"
echo "Pulling $REMOTE -> $DEST"
adb -s "$SERIAL" pull "$REMOTE/." "$DEST" >/dev/null
echo "Pulled:"; find "$DEST" -type f -name '*.ndjson' | sed 's|^|  |'

if [ "$SEND" -eq 1 ]; then
    # Explicit opt-in. Set these in your environment; no host, user or key is committed.
    : "${LOG_SINK_HOST:?set LOG_SINK_HOST=user@host}"
    KEY="${LOG_SINK_KEY:-$HOME/.ssh/id_ed25519}"
    RPATH="${LOG_SINK_PATH:-~/sensor-logs}"
    echo "Sending to $LOG_SINK_HOST:$RPATH (key $KEY)"
    rsync -av -e "ssh -o IdentitiesOnly=yes -i $KEY" "$DEST/" "$LOG_SINK_HOST:$RPATH/$(basename "$DEST")/"
else
    echo "(not sent — pass --send to copy to the configured remote host)"
fi
