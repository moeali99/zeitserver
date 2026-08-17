#!/usr/bin/env bash
# Auf dem Pi: Web-Dashboard starten (~/zeitserver/zeitserver.jar).
set -euo pipefail
DIR="${HOME}/zeitserver"
mkdir -p "$DIR"
cd "$DIR"

if [[ ! -f zeitserver.jar ]]; then
  echo "Fehlt: $DIR/zeitserver.jar — erst vom Mac: ./setup-pi.sh" >&2
  exit 1
fi

JAVA_BIN="$(command -v java 2>/dev/null || true)"
[[ -x "$JAVA_BIN" ]] || JAVA_BIN="/usr/bin/java"
if ! "$JAVA_BIN" -version >/dev/null 2>&1; then
  echo "Java fehlt — installiere: sudo apt install -y openjdk-21-jre-headless" >&2
  exit 1
fi

chmod +x "$DIR/pi-stop-old-zeitserver.sh" 2>/dev/null || true

# GPS UART: nach Reboot oft wieder root:root 600 — vor Start freigeben.
if [[ -e /dev/ttyS0 ]]; then
  if [[ ! -r /dev/ttyS0 ]]; then
    sudo -n chown root:dialout /dev/ttyS0 2>/dev/null || true
    sudo -n chmod 660 /dev/ttyS0 2>/dev/null || true
  fi
  if [[ ! -r /dev/ttyS0 ]]; then
    echo "Hinweis: /dev/ttyS0 nicht lesbar — einmal: bash $DIR/pi-fix-gps-perms.sh" >&2
  fi
fi

if command -v systemctl >/dev/null \
    && systemctl list-unit-files zeitserver-web.service >/dev/null 2>&1 \
    && systemctl is-enabled --quiet zeitserver-web.service 2>/dev/null; then
  sudo systemctl restart zeitserver-web.service
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    sleep 1
    curl -sf http://127.0.0.1:8080/api/status >/dev/null 2>&1 && break
  done
else
  bash "$DIR/pi-stop-old-zeitserver.sh" 2>/dev/null \
    || pkill -f 'zeitserver.jar web' 2>/dev/null || true
  pkill -f 'gpiomon.*gpiochip0' 2>/dev/null || true
  sleep 1
  : > "$DIR/zeitserver-web.log"
  nohup "$JAVA_BIN" -Dzeitserver.config="$DIR/config.properties" -jar "$DIR/zeitserver.jar" web \
    >> "$DIR/zeitserver-web.log" 2>&1 &
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    curl -sf http://127.0.0.1:8080/api/status >/dev/null 2>&1 && break
  done
fi

if curl -sf http://127.0.0.1:8080/api/status >/dev/null; then
  ip="$(hostname -I | awk '{print $1}')"
  echo "OK — http://${ip}:8080/"
  curl -s http://127.0.0.1:8080/api/status | head -c 300
  echo ""
else
  echo "Fehler — Log:"
  tail -30 "$DIR/zeitserver-web.log"
  exit 1
fi
