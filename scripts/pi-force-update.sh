#!/usr/bin/env bash
# Ein Befehl: bauen + JAR auf Pi + Prozess killen + neu starten.
# Nutzt Key oder Passwort (Dialog / PI_PASSWORD).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/pi-ssh-lib.sh"
pi_ssh_init

PI_HOST="${PI_HOST:-mohamed@172.20.10.2}"
PI_DIR="${PI_DIR:-~/zeitserver}"
JAR="$ROOT/zeitserver-java/target/zeitserver-1.0-SNAPSHOT-all.jar"
PI_IP="${PI_HOST#*@}"

pi_clear_host_keys() {
  ssh-keygen -R "$PI_IP" 2>/dev/null || true
  ssh-keygen -R "zeit-server.local" 2>/dev/null || true
}

pi_find_ip() {
  local mac_ip base end i ip
  mac_ip="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
  [[ -n "$mac_ip" ]] || return 1
  base="${mac_ip%.*}"
  end=20
  [[ "$mac_ip" == 172.20.10.* ]] || end=254
  for i in $(seq 1 "$end"); do
    ip="${base}.${i}"
    [[ "$ip" == "$mac_ip" ]] && continue
    [[ "$i" == 1 ]] && continue
    nc -z -G 1 "$ip" 22 &>/dev/null || continue
    echo "$ip"
    return 0
  done
  return 1
}

wait_for_pi() {
  local ip="$1" n=0 found=""
  while (( n < 60 )); do
    if nc -z -G 2 "$ip" 22 &>/dev/null; then
      return 0
    fi
    found="$(pi_find_ip 2>/dev/null || true)"
    if [[ -n "$found" && "$found" != "$ip" ]]; then
      echo "Pi unter neuer IP: $found (statt $ip)"
      PI_IP="$found"
      PI_HOST="mohamed@${PI_IP}"
      ip="$PI_IP"
      if nc -z -G 2 "$ip" 22 &>/dev/null; then
        return 0
      fi
    fi
    echo "Warte auf Pi ($ip)… ($((n + 1))/60) — Hotspot + Pi-WLAN prüfen"
    sleep 2
    ((n++)) || true
  done
  echo "Pi nicht erreichbar. Am Monitor: iwgetid -r  und  hostname -I" >&2
  return 1
}

echo "[1/4] JAR bauen (Pi kann offline sein)..."
(cd "$ROOT/zeitserver-java" && mvn -q package -DskipTests)
ls -lh "$JAR"

echo "[2/4] Auf Pi warten..."
pi_clear_host_keys
wait_for_pi "$PI_IP"

echo "[3/4] Login (Key oder Passwort) + Key installieren..."
pi_ensure_password "$PI_HOST"
pi_install_ssh_key "$PI_HOST"

echo "[4/4] Kopieren + Neustart auf $PI_HOST..."
pi_ssh "$PI_HOST" "mkdir -p $PI_DIR"
pi_scp "$JAR" "$PI_HOST:$PI_DIR/zeitserver.jar"
pi_scp "$ROOT/zeitserver-java/src/main/resources/config.pi.properties" "$PI_HOST:$PI_DIR/config.properties"
for f in pi-on-pi-start-web.sh pi-stop-old-zeitserver.sh pi-verify-version.sh pi-fix-gps-perms.sh pi-repair-now.sh pi-test-dcf77-gpio.sh rtc-i2c-read.py; do
  pi_scp "$ROOT/scripts/$f" "$PI_HOST:$PI_DIR/" 2>/dev/null || true
done

# GPS-Rechte ohne Dialog, falls sudo -n geht; sonst Hinweis.
pi_ssh "$PI_HOST" "sudo -n chown root:dialout /dev/ttyS0 2>/dev/null; sudo -n chmod 660 /dev/ttyS0 2>/dev/null; true" || true

pi_ssh "$PI_HOST" "chmod +x $PI_DIR/*.sh 2>/dev/null; bash $PI_DIR/pi-stop-old-zeitserver.sh; sleep 2; bash $PI_DIR/pi-on-pi-start-web.sh"

sleep 3
BUILD="$(pi_ssh "$PI_HOST" "curl -sf http://127.0.0.1:8080/api/status" | python3 -c "import sys,json; print(json.load(sys.stdin).get('buildId','?'))" 2>/dev/null || echo FEHLER)"
echo ""
if [[ "$BUILD" == "2026-08-07-demo-foil-v11" ]]; then
  echo "OK — Neue Version läuft: $BUILD"
  echo "Browser: http://${PI_IP}:8080/  (Cmd+Shift+R)"
else
  echo "WARNUNG — buildId=$BUILD (erwartet: 2026-08-07-demo-foil-v11)"
  echo "Nochmal: cd ~/Desktop/Zeitserver && PI_HOST=$PI_HOST ./scripts/pi-force-update.sh"
fi
