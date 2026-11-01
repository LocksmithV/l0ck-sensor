#!/usr/bin/env bash
# Passive receive-only test of a USB Wi-Fi adapter in monitor mode.
# Nothing is transmitted: no injection, no deauth, no association.
# Usage: sudo scripts/test-monitor-adapter.sh [iface] [seconds-per-channel]
set -euo pipefail

DWELL="${2:-5}"
CHANNELS=(1 6 11 36 44 149)
OUT="$(cd "$(dirname "$0")/.." && pwd)/logs/monitor-test-$(date +%Y%m%dT%H%M%S)"

# Interface: first arg, else auto-detect a wireless iface that is NOT carrying the default
# route (i.e. the spare monitor-capable adapter, not the one you are online through).
default_iface="$(ip route show default 2>/dev/null | awk '{print $5; exit}')"
pick_iface() {
    for p in /sys/class/net/*/phy80211; do
        n="$(basename "$(dirname "$p")")"
        [ "$n" = "$default_iface" ] && continue
        echo "$n"; return
    done
}
IFACE="${1:-$(pick_iface)}"

[ "$(id -u)" -eq 0 ] || { echo "Run with sudo." >&2; exit 1; }
[ -n "$IFACE" ] || { echo "No spare wireless interface found; pass one explicitly." >&2; exit 1; }
iw dev "$IFACE" info >/dev/null || { echo "No interface $IFACE" >&2; exit 1; }
mkdir -p "$OUT"

CAP_PID=
restore() {
  [ -n "$CAP_PID" ] && kill -9 "$CAP_PID" 2>/dev/null
  echo "== restoring $IFACE to managed =="
  ip link set "$IFACE" down || true
  iw dev "$IFACE" set type managed || true
  ip link set "$IFACE" up || true
  nmcli device set "$IFACE" managed yes 2>/dev/null || true
}
trap restore EXIT

# Leftover captures from earlier interrupted runs would keep the interface busy
if pkill -9 -f "tcpdump -i $IFACE " 2>/dev/null; then echo "== killed stale tcpdump on $IFACE =="; fi

echo "== taking $IFACE away from NetworkManager =="
nmcli device set "$IFACE" managed no 2>/dev/null || true
ip link set "$IFACE" down
iw dev "$IFACE" set type monitor
ip link set "$IFACE" up
iw dev "$IFACE" info | grep -E 'type|channel'

printf '\n%-5s %-8s %-8s %-8s %-8s\n' chan frames beacons probereq dataframes
for ch in "${CHANNELS[@]}"; do
  if ! iw dev "$IFACE" set channel "$ch" 2>/dev/null; then
    printf '%-5s %s\n' "$ch" "cannot tune"; continue
  fi
  pcap="$OUT/ch$ch.pcap"
  t0=$SECONDS
  # -Z root: Ubuntu's tcpdump otherwise drops to user tcpdump, which may not write here.
  # Stopped with sleep+kill: `timeout` proved unreliable here and left tcpdump orphans behind.
  tcpdump -i "$IFACE" -y IEEE802_11_RADIO -Z root -w "$pcap" -U -q 2>"$OUT/ch$ch.err" &
  CAP_PID=$!
  sleep "$DWELL"
  kill "$CAP_PID" 2>/dev/null || true
  sleep 0.5
  kill -9 "$CAP_PID" 2>/dev/null || true
  rc=0; wait "$CAP_PID" 2>/dev/null || rc=$?
  CAP_PID=
  total=$(tcpdump -r "$pcap" 2>/dev/null | wc -l)
  bcn=$(tcpdump -r "$pcap" 'type mgt subtype beacon' 2>/dev/null | wc -l)
  prq=$(tcpdump -r "$pcap" 'type mgt subtype probe-req' 2>/dev/null | wc -l)
  dat=$(tcpdump -r "$pcap" 'type data' 2>/dev/null | wc -l)
  printf '%-5s %-8s %-8s %-8s %-8s %ss rc=%s\n' "$ch" "$total" "$bcn" "$prq" "$dat" "$((SECONDS - t0))" "$rc"
  # rc 143/137 = stopped by our kill as intended; anything else means tcpdump died early
  if [ "$rc" -ne 143 ] && [ "$rc" -ne 137 ]; then sed 's/^/      tcpdump: /' "$OUT/ch$ch.err"; fi
done

echo -e "\n== kernel messages during test =="
dmesg 2>/dev/null | grep -E "rtw|$IFACE" | tail -8 || true

chown -R "${SUDO_UID:-0}:${SUDO_GID:-0}" "$OUT" 2>/dev/null || true
echo -e "\nCaptures in $OUT (gitignored)."
