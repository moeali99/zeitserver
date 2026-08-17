#!/usr/bin/env bash
# Volltest + GPS-UART-Reparatur (disable-bt) + Reboot + Quellen-Check.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=pi-ssh-lib.sh
source "$ROOT/scripts/pi-ssh-lib.sh"

pi_ssh_init

PI_HOST="${PI_HOST:-$(pi_find_host 2>/dev/null || echo mohamed@172.20.10.3)}"
PI_IP="${PI_HOST#*@}"

log() { printf '\n==> %s\n' "$*"; }
ok()  { printf '[OK] %s\n' "$*"; }
fail(){ printf '[FEHLER] %s\n' "$*"; exit 1; }
warn(){ printf '[WARN] %s\n' "$*"; }

pi_install_ssh_key "$PI_HOST"
PI_PASSWORD="${PI_PASSWORD:-}"
if ! pi_ssh_test "$PI_HOST"; then
  pi_ensure_password "$PI_HOST" || fail "Kein Pi-Passwort / SSH"
fi
if ! pi_ssh "$PI_HOST" 'sudo -n true' 2>/dev/null; then
  [[ -n "$PI_PASSWORD" ]] || pi_ensure_password "$PI_HOST" || fail "Pi-Passwort für sudo nötig"
fi
PW_B64=""
[[ -n "$PI_PASSWORD" ]] && PW_B64=$(printf '%s' "$PI_PASSWORD" | base64 | tr -d '\n')

log "Deploy + Web (setup-pi)"
PI_NO_BROWSER=1 "$ROOT/setup-pi.sh" || true

log "GPS-UART: disable-bt setzen (falls nötig)"
FIX_OUT=$(pi_ssh "$PI_HOST" "PW_B64='$PW_B64' bash -s" <<'REMOTE'
set -uo pipefail
CFG="/boot/firmware/config.txt"
[[ -f "$CFG" ]] || CFG="/boot/config.txt"
sudo_cmd() {
  if [[ -n "${PW_B64:-}" ]]; then echo "$(echo "$PW_B64" | base64 -d)" | sudo -S "$@" 2>/dev/null
  else sudo "$@"; fi
}
if grep -q 'dtoverlay=disable-bt' "$CFG" 2>/dev/null; then
  echo "ALREADY_OK"
else
  sudo_cmd cp -a "$CFG" "${CFG}.bak.testall"
  grep -q '^enable_uart=1' "$CFG" || echo 'enable_uart=1' | sudo_cmd tee -a "$CFG" >/dev/null
  echo 'dtoverlay=disable-bt' | sudo_cmd tee -a "$CFG" >/dev/null
  echo "APPLIED_REBOOT"
fi
sudo_cmd systemctl stop hciuart 2>/dev/null || true
sudo_cmd systemctl disable hciuart 2>/dev/null || true
REMOTE
)
echo "$FIX_OUT"

if echo "$FIX_OUT" | grep -q 'APPLIED_REBOOT'; then
  log "Reboot (UART0 für GPS)…"
  if [[ -n "$PW_B64" ]]; then
    pi_ssh "$PI_HOST" "echo \"\$(echo '$PW_B64' | base64 -d)\" | sudo -S reboot" 2>/dev/null || true
  else
    pi_ssh "$PI_HOST" "sudo reboot" 2>/dev/null || true
  fi
  log "Warte 50 s auf Pi…"
  sleep 50
  for _ in $(seq 1 24); do
    nc -z -G 2 "$PI_IP" 22 &>/dev/null && break
    sleep 5
  done
  nc -z -G 2 "$PI_IP" 22 &>/dev/null || fail "Pi nach Reboot nicht erreichbar"
  ok "Pi wieder online"
  PI_NO_BROWSER=1 "$ROOT/setup-pi.sh"
fi

log "Serielltest (Java gestoppt)"
NMEA_OUT=$(pi_ssh "$PI_HOST" "PW_B64='$PW_B64' bash -s" <<'REMOTE'
set -uo pipefail
sudo_cmd() {
  if [[ -n "${PW_B64:-}" ]]; then echo "$(echo "$PW_B64" | base64 -d)" | sudo -S "$@" 2>/dev/null
  else sudo "$@"; fi
}
pkill -f 'zeitserver.jar web' 2>/dev/null || true
sleep 2
sudo_cmd systemctl stop hciuart 2>/dev/null || true
dev=$(readlink -f /dev/serial0 2>/dev/null || echo '?')
echo "serial0 -> $dev"
stty -F /dev/serial0 9600 cs8 -cstopb -parenb raw -echo 2>/dev/null || true
tmp=$(mktemp)
timeout 15 cat /dev/serial0 >"$tmp" 2>/dev/null || true
if grep -qE '\$G[PN]RMC' "$tmp"; then
  echo "NMEA_OK"
  grep -m 1 -E '\$G[PN]RMC' "$tmp"
else
  echo "NMEA_FAIL"
  wc -c <"$tmp" | xargs echo "bytes:"
  head -3 "$tmp" | od -c | head -2 || true
fi
rm -f "$tmp"
REMOTE
) || NMEA_OUT="SSH_FAIL"
echo "$NMEA_OUT"

log "Web neu starten"
pi_ssh "$PI_HOST" 'bash ~/zeitserver/pi-on-pi-start-web.sh' || true

log "Warte auf DCF77-Dekodierung (bis 120 s)…"
DCF_OK=0
GPS_API=0
NTP_OK=0
for s in $(seq 5 5 120); do
  sleep 5
  JSON=$(curl -sf --connect-timeout 3 "http://${PI_IP}:8080/api/status" 2>/dev/null || true)
  [[ -n "$JSON" ]] || continue
  echo "--- ${s}s ---"
  echo "$JSON" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for x in d.get('sources',[]):
    print(x['id'], x.get('utc') or '-', (x.get('detail') or '')[:70])
" 2>/dev/null || true
  echo "$JSON" | python3 -c "
import sys,json
d=json.load(sys.stdin)
ids={x['id']:x.get('utc') for x in d.get('sources',[])}
import os
sys.exit(0 if ids.get('NTP') else 1)
" 2>/dev/null && NTP_OK=1
  echo "$JSON" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for x in d.get('sources',[]):
    if x['id']=='GPS' and x.get('utc'): sys.exit(0)
sys.exit(1)
" 2>/dev/null && GPS_API=1
  echo "$JSON" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for x in d.get('sources',[]):
    if x['id']=='DCF77' and x.get('utc'): sys.exit(0)
sys.exit(1)
" 2>/dev/null && DCF_OK=1
  [[ "$NTP_OK" == 1 && "$GPS_API" == 1 && "$DCF_OK" == 1 ]] && break
done

echo ""
echo "========== TESTERGEBNIS =========="
[[ "$NTP_OK" == 1 ]] && ok "NTP" || fail "NTP fehlgeschlagen"
if echo "$NMEA_OUT" | grep -q NMEA_OK; then ok "GPS Hardware (NMEA)"; else warn "GPS Hardware: keine NMEA — Kabel TX/RX prüfen"
fi
[[ "$GPS_API" == 1 ]] && ok "GPS im Dashboard" || warn "GPS im Dashboard: noch keine Zeit"
[[ "$DCF_OK" == 1 ]] && ok "DCF77 im Dashboard" || warn "DCF77: nach 2 Min. noch keine Zeit — Antenne / GPIO4 (BCM)"
ok "Web: http://${PI_IP}:8080/"
echo "=================================="
