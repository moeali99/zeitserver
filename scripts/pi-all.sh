#!/usr/bin/env bash
# Ein Befehl: Pi finden → deployen → Hardware vorbereiten → Web → Test → Browser.
#   ./scripts/pi-all.sh
# Optional: PI_HOST=mohamed@IP  PI_PASSWORD=…  PI_SKIP_GPS=1  PI_NO_BROWSER=1
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=pi-ssh-lib.sh
source "$ROOT/scripts/pi-ssh-lib.sh"

pi_ssh_init

log() { printf '\n==> %s\n' "$*"; }
ok()  { printf '[OK] %s\n' "$*"; }
warn(){ printf '[Hinweis] %s\n' "$*"; }

PI_HOST="${PI_HOST:-}"
if [[ -z "$PI_HOST" ]]; then
  log "Suche Raspberry Pi im WLAN…"
  PI_HOST="$(pi_find_host || true)"
fi
PI_HOST="${PI_HOST:-mohamed@172.20.10.3}"
PI_IP="${PI_HOST#*@}"
PI_DIR="${PI_DIR:-~/zeitserver}"
NEED_REBOOT=0

log "Ziel: $PI_HOST"
pi_ensure_password "$PI_HOST"
pi_install_ssh_key "$PI_HOST"

JAVA_DIR="$ROOT/zeitserver-java"
JAR="$JAVA_DIR/target/zeitserver-1.0-SNAPSHOT-all.jar"

log "[1/6] JAR bauen"
(cd "$JAVA_DIR" && mvn -q package -DskipTests)

log "[2/6] Auf Pi kopieren"
pi_ssh "$PI_HOST" "mkdir -p $PI_DIR"
pi_scp "$JAR" "$PI_HOST:$PI_DIR/zeitserver.jar"
pi_scp "$JAVA_DIR/src/main/resources/config.pi.properties" "$PI_HOST:$PI_DIR/config.properties"
for f in pi-gps-pps.sh pi-on-pi-start-web.sh pi-check-sources.sh pi-stop-old-zeitserver.sh pi-systemd-install.sh pi-crontab-install.sh pi-enable-rtc.sh; do
  pi_scp "$ROOT/scripts/$f" "$PI_HOST:$PI_DIR/" 2>/dev/null || true
done
pi_ssh "$PI_HOST" "chmod +x $PI_DIR/*.sh 2>/dev/null || true"

PW_B64=""
[[ -n "${PI_PASSWORD:-}" ]] && PW_B64=$(printf '%s' "$PI_PASSWORD" | base64 | tr -d '\n')

log "[3/6] Java + System-Pakete"
pi_ssh "$PI_HOST" "PW_B64='${PW_B64}' bash -s" <<'REMOTE'
set -uo pipefail
sudo_cmd() {
  if [[ -n "${PW_B64:-}" ]]; then
    echo "$(echo "$PW_B64" | base64 -d)" | sudo -S "$@" 2>/dev/null
  else
    sudo "$@"
  fi
}
if ! command -v java >/dev/null 2>&1; then
  sudo_cmd apt-get update -qq
  sudo_cmd apt-get install -y openjdk-21-jre-headless
fi
java -version 2>&1 | head -1
# GPS-Tests / I2C
sudo_cmd apt-get install -y -qq i2c-tools pps-tools 2>/dev/null || true
REMOTE

if [[ "${PI_SKIP_GPS:-0}" != 1 ]]; then
  log "[4/6] GPS-UART / Bluetooth"
  GPS_OUT="$(pi_ssh "$PI_HOST" "PW_B64='${PW_B64}' PI_DIR='$PI_DIR' bash -s" <<'REMOTE' || true
set -uo pipefail
PI_DIR="${PI_DIR/#\~/$HOME}"
CFG="/boot/firmware/config.txt"
[[ -f "$CFG" ]] || CFG="/boot/config.txt"
sudo_cmd() {
  if [[ -n "${PW_B64:-}" ]]; then
    echo "$(echo "$PW_B64" | base64 -d)" | sudo -S "$@" 2>/dev/null
  else
    sudo "$@"
  fi
}
sudo_cmd systemctl stop hciuart 2>/dev/null || true
sudo_cmd systemctl disable hciuart 2>/dev/null || true
if [[ -f "$CFG" ]] && grep -q 'dtoverlay=disable-bt' "$CFG" && grep -q 'enable_uart=1' "$CFG"; then
  echo "UART bereits konfiguriert."
else
  echo "UART-Konfiguration wird geschrieben…"
  sudo_cmd bash "$PI_DIR/pi-gps-pps.sh" apply
  echo "NEED_REBOOT"
fi
sudo_cmd systemctl stop hciuart 2>/dev/null || true
sudo_cmd bash "$PI_DIR/pi-gps-pps.sh" test 2>&1 || true
REMOTE
)"
  echo "$GPS_OUT"
  echo "$GPS_OUT" | grep -q 'NEED_REBOOT' && NEED_REBOOT=1
else
  warn "GPS-Schritt übersprungen (PI_SKIP_GPS=1)"
fi

log "[5/6] Web-Dashboard + Autostart"
pi_ssh "$PI_HOST" "PW_B64='${PW_B64}' PI_DIR=$PI_DIR bash -s" <<'REMOTE'
set -uo pipefail
PI_DIR="${PI_DIR/#\~/$HOME}"
export SUDO_PASS=""
[[ -n "${PW_B64:-}" ]] && export SUDO_PASS="$(echo "$PW_B64" | base64 -d)"
cd "$PI_DIR"
bash ./pi-systemd-install.sh --on-pi
REMOTE

log "[6/6] Quellen prüfen"
sleep 3
pi_ssh "$PI_HOST" "bash ~/zeitserver/pi-check-sources.sh" 2>&1 || true

echo ""
echo "=============================================="
STATUS_OK=0
if curl -sf --connect-timeout 8 "http://${PI_IP}:8080/api/status" >/dev/null; then
  STATUS_OK=1
  ok "Dashboard: http://${PI_IP}:8080/"
  curl -sf "http://${PI_IP}:8080/api/status" | python3 -c "
import sys, json
d=json.load(sys.stdin)
print('  Referenz:', d.get('referenceLabel',''))
for s in d.get('sources',[]):
    u=s.get('utc') or '—'
    print(f\"  {s['id']}: {u} — {s.get('detail','')[:60]}\")
" 2>/dev/null || true
else
  warn "Dashboard noch nicht erreichbar — http://${PI_IP}:8080/"
fi
echo "  SSH:  ssh -i ~/.ssh/id_ed25519_zeitserver ${PI_HOST}"
echo "  Log:  ssh ${PI_HOST} 'tail -f ~/zeitserver/zeitserver-web.log'"
if [[ "$NEED_REBOOT" == 1 ]]; then
  warn "GPS-UART neu konfiguriert → einmal: ssh ${PI_HOST} 'sudo reboot'"
fi
echo "=============================================="

if [[ "${PI_NO_BROWSER:-0}" != 1 ]] && [[ "$STATUS_OK" == 1 ]] && command -v open >/dev/null; then
  open "http://${PI_IP}:8080/" 2>/dev/null || true
fi
