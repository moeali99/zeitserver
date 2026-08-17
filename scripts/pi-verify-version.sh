#!/usr/bin/env bash
# Auf dem Pi: prüfen ob neue JAR läuft (nach Deploy vom Mac).
set -euo pipefail

JAR="${HOME}/zeitserver/zeitserver.jar"
echo "=== Zeitserver auf dem Pi ==="
if [[ ! -f "$JAR" ]]; then
  echo "[FEHLER] $JAR fehlt — vom Mac: PI_HOST=mohamed@<IP> ./scripts/pi-deploy-web.sh"
  exit 1
fi

ls -lh "$JAR"
echo ""
echo "JAR-Größe: neu ≈ 950–975 KB (alt ≈ 951 KB ohne Fixes)"
echo ""

if strings "$JAR" 2>/dev/null | grep -q "demo-offline-v3"; then
  echo "[OK] Neue Version (demo-offline-v3) in der JAR"
elif strings "$JAR" 2>/dev/null | grep -q "Antenne abgedeckt"; then
  echo "[OK] Neue Version (GPS/DCF77 Offline-Fix) in der JAR"
else
  echo "[WARN] Alte JAR — vom Mac deployen:"
  echo "  cd ~/Desktop/Zeitserver && PI_HOST=mohamed@172.20.10.2 ./scripts/pi-deploy-web.sh"
fi

echo ""
echo "=== Laufender Prozess ==="
pgrep -af 'zeitserver.jar' || echo "Nicht gestartet — bash ~/zeitserver/pi-on-pi-start-web.sh"

echo ""
echo "=== GPS Rohdaten (5 s) ==="
if [[ -e /dev/serial0 ]]; then
  timeout 5 stdbuf -oL cat /dev/serial0 2>/dev/null \
    | grep -m 2 -E 'GPGGA|GNGGA|GPGLL|GPRMC' \
    || echo "Keine NMEA — Kabel/Antenne prüfen"
  echo ""
  echo "Abgedeckt? GGA sollte Sats=0 oder Qualität=0 zeigen, GLL/RMC Status V"
else
  echo "/dev/serial0 fehlt"
fi

echo ""
echo "=== API Kurzstatus ==="
curl -sf http://127.0.0.1:8080/api/status 2>/dev/null | python3 -c "
import sys, json
try:
    d=json.load(sys.stdin)
    for s in d.get('sources', []):
        print(f\"  {s['id']}: {s.get('accuracy','?')} — {(s.get('detail') or '')[:65]}\")
except Exception as e:
    print('API nicht erreichbar:', e)
" || echo "Web nicht erreichbar"
