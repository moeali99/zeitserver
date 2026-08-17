#!/usr/bin/env bash
# Läuft weiter, bis Deploy klappt (Pi kommt oft spät ins Hotspot).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PI_HOST="${PI_HOST:-mohamed@172.20.10.2}"

echo "Warte auf Pi und deploye automatisch (Strg+C zum Abbrechen)…"
echo "Tipp: Pi-Strom kurz aus/an, dann Hotspot „$(networksetup -getairportnetwork en0 2>/dev/null | sed 's/^Current Wi-Fi Network: //' || echo WLAN)“"
echo ""

while true; do
  if bash "$ROOT/scripts/pi-force-update.sh"; then
    exit 0
  fi
  echo ""
  echo "Deploy fehlgeschlagen — in 10 s erneut…"
  sleep 10
done
