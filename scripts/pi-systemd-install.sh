#!/usr/bin/env bash
# Pi: Autostart für das Web-Dashboard (systemd oder Crontab-Fallback).
# Auf dem Pi: bash ~/zeitserver/pi-systemd-install.sh --on-pi
# Vom Mac: PI_HOST=mohamed@<IP> ./scripts/pi-systemd-install.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=pi-ssh-lib.sh
source "$ROOT/scripts/pi-ssh-lib.sh" 2>/dev/null || true

if [[ "${1:-}" != "--on-pi" ]]; then
  pi_ssh_init 2>/dev/null || true
  PI_HOST="${PI_HOST:-$(pi_find_host 2>/dev/null || echo mohamed@172.20.10.3)}"
  PI_DIR="${PI_DIR:-~/zeitserver}"
  echo "==> systemd auf $PI_HOST installieren"
  for f in pi-stop-old-zeitserver.sh pi-systemd-install.sh pi-crontab-install.sh pi-on-pi-start-web.sh; do
    pi_scp "$ROOT/scripts/$f" "$PI_HOST:$PI_DIR/" 2>/dev/null || true
  done
  pi_ssh "$PI_HOST" "chmod +x $PI_DIR/pi-stop-old-zeitserver.sh $PI_DIR/pi-systemd-install.sh 2>/dev/null; bash $PI_DIR/pi-systemd-install.sh --on-pi"
  exit 0
fi

DIR="${HOME}/zeitserver"
USER_NAME="$(id -un)"
JAVA_BIN="$(command -v java 2>/dev/null || true)"
[[ -x "$JAVA_BIN" ]] || JAVA_BIN="/usr/bin/java"

if [[ ! -f "$DIR/zeitserver.jar" ]]; then
  echo "Fehlt: $DIR/zeitserver.jar" >&2
  exit 1
fi

chmod +x "$DIR/pi-stop-old-zeitserver.sh" 2>/dev/null || true

sudo_cmd() {
  if [[ -n "${SUDO_PASS:-}" ]]; then
    printf '%s\n' "$SUDO_PASS" | sudo -S "$@" 2>/dev/null
  else
    sudo "$@"
  fi
}

AUTOSTART_MODE=crontab
UNIT=/etc/systemd/system/zeitserver-web.service
if sudo_cmd tee "$UNIT" >/dev/null <<UNIT
[Unit]
Description=Zeitserver Web Dashboard (NTP, GPS, DCF77)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${USER_NAME}
WorkingDirectory=${DIR}
ExecStartPre=${DIR}/pi-stop-old-zeitserver.sh
ExecStart=${JAVA_BIN} -Dzeitserver.config=${DIR}/config.properties -jar ${DIR}/zeitserver.jar web
Restart=always
RestartSec=3
TimeoutStopSec=20
StandardOutput=append:${DIR}/zeitserver-web.log
StandardError=append:${DIR}/zeitserver-web.log

[Install]
WantedBy=multi-user.target
UNIT
then
  if sudo_cmd systemctl daemon-reload \
      && sudo_cmd systemctl enable zeitserver-web.service \
      && sudo_cmd systemctl restart zeitserver-web.service; then
    AUTOSTART_MODE=systemd
  fi
fi

if [[ "$AUTOSTART_MODE" != systemd ]]; then
  echo "(Hinweis) systemd nicht verfügbar — Crontab-Autostart (Boot + Watchdog)"
  bash "$DIR/pi-crontab-install.sh"
  bash "$DIR/pi-on-pi-start-web.sh"
fi

for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  sleep 1
  curl -sf http://127.0.0.1:8080/api/status >/dev/null 2>&1 && break
done

if curl -sf http://127.0.0.1:8080/api/status >/dev/null; then
  ip="$(hostname -I | awk '{print $1}')"
  echo "OK — Autostart (${AUTOSTART_MODE:-?}) — http://${ip}:8080/"
  [[ "${AUTOSTART_MODE:-}" == systemd ]] && sudo_cmd systemctl is-active zeitserver-web.service
else
  echo "Fehler — Log:" >&2
  tail -25 "$DIR/zeitserver-web.log" 2>/dev/null || true
  exit 1
fi
