#!/usr/bin/env bash
# Autostart ohne sudo: @reboot + Watchdog wenn Port 8080 down ist.
set -euo pipefail

DIR="${HOME}/zeitserver"
MARKER="# zeitserver-web-autostart"
REBOOT_LINE="@reboot sleep 10 && ${DIR}/pi-on-pi-start-web.sh >> ${DIR}/cron-autostart.log 2>&1"
WATCH_LINE="*/2 * * * * curl -sf --max-time 2 http://127.0.0.1:8080/api/status >/dev/null 2>&1 || ${DIR}/pi-on-pi-start-web.sh >> ${DIR}/cron-autostart.log 2>&1"

chmod +x "${DIR}/pi-on-pi-start-web.sh" "${DIR}/pi-stop-old-zeitserver.sh" 2>/dev/null || true

(
  crontab -l 2>/dev/null | grep -v "$MARKER" | grep -v 'pi-on-pi-start-web.sh' | grep -v '127.0.0.1:8080/api/status' || true
  echo "$MARKER"
  echo "$REBOOT_LINE $MARKER"
  echo "$WATCH_LINE $MARKER"
) | crontab -

echo "OK — Crontab-Autostart (Boot + alle 2 Min. Prüfung)"
crontab -l | grep zeitserver || true
