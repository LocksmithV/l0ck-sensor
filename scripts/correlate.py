#!/usr/bin/env python3
"""PREPARED, NOT WIRED LIVE. Cross-check the phone's BLE/Wi-Fi NDJSON against a laptop
monitor-mode capture, so an AP or device seen by both radios lines up in one view.

Read-only: reads the phone logs (pull them first with scripts/pull-logs.sh) and, optionally,
a CSV exported from a laptop monitor-mode pcap. Prints a short correlation report. It does not
listen, transmit, or touch any device.

Usage:
    python3 scripts/correlate.py logs/phone/<run>/<date>  [--laptop laptop_aps.csv]

laptop_aps.csv columns (from `tshark -r cap.pcap -T fields -e wlan.bssid -e wlan.ssid ...`):
    bssid,ssid,rssi
"""
import argparse
import csv
import glob
import json
import os
import sys
from collections import defaultdict


def load_ndjson(run_dir, stream):
    rows = []
    for path in glob.glob(os.path.join(run_dir, "**", f"{stream}.ndjson"), recursive=True):
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line:
                    try:
                        rows.append(json.loads(line))
                    except json.JSONDecodeError:
                        pass
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir", help="pulled phone log dir")
    ap.add_argument("--laptop", help="CSV of APs from a laptop monitor capture")
    args = ap.parse_args()

    wifi = load_ndjson(args.run_dir, "wifi")
    ble = load_ndjson(args.run_dir, "ble")
    print(f"phone: {len(wifi)} wifi rows, {len(ble)} ble rows")

    # phone APs keyed by BSSID, with their strongest RSSI and fingerprint
    phone_aps = {}
    for r in wifi:
        b = r.get("bssid", "")
        cur = phone_aps.get(b)
        if cur is None or r.get("rssi", -127) > cur["rssi"]:
            phone_aps[b] = {"ssid": r.get("ssid", ""), "rssi": r.get("rssi", -127), "fp": r.get("fp", "")}

    # trackers the phone flagged
    trackers = defaultdict(int)
    for r in ble:
        if r.get("tracker"):
            trackers[(r.get("cls"), r.get("key"))] += 1
    if trackers:
        print("\ntrackers flagged by phone:")
        for (cls, key), n in sorted(trackers.items(), key=lambda x: -x[1]):
            print(f"  {cls:28} seen {n:4}x  {key}")

    if not args.laptop:
        print("\n(no laptop capture given — phone-only view)")
        return

    seen_both, laptop_only = [], []
    lap = defaultdict(lambda: -127)
    with open(args.laptop, encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            b = (row.get("bssid") or "").lower()
            try:
                lap[b] = max(lap[b], int(float(row.get("rssi") or -127)))
            except ValueError:
                lap[b] = max(lap[b], -127)
    for b, rssi in lap.items():
        if b in phone_aps:
            seen_both.append((b, phone_aps[b]["ssid"], phone_aps[b]["rssi"], rssi))
        else:
            laptop_only.append((b, rssi))

    print(f"\nAPs seen by BOTH phone and laptop: {len(seen_both)}")
    for b, ssid, pr, lr in sorted(seen_both, key=lambda x: -x[2]):
        print(f"  {b}  {ssid[:24]:24}  phone {pr:4} dBm / laptop {lr:4} dBm")
    print(f"APs only the laptop saw: {len(laptop_only)}")


if __name__ == "__main__":
    sys.exit(main())
