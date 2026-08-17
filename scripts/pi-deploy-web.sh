#!/usr/bin/env bash
# Projekt auf den Pi kopieren und Web-Dashboard starten (vom Mac aus).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=pi-ssh-lib.sh
source "$ROOT/scripts/pi-ssh-lib.sh"
pi_ssh_init
PI_HOST="${PI_HOST:-mohamed@zeit-server.local}"
pi_ensure_password "$PI_HOST" 2>/dev/null || true
pi_install_ssh_key "$PI_HOST" 2>/dev/null || true
PI_IP="${PI_HOST#*@}"
PI_DIR="${PI_DIR:-~/zeitserver}"
JAVA_DIR="$ROOT/zeitserver-java"
JAR="$JAVA_DIR/target/zeitserver-1.0-SNAPSHOT-all.jar"

echo "[1/3] JAR bauen..."
(cd "$JAVA_DIR" && mvn -q package -DskipTests)

echo "[2/3] Nach $PI_HOST:$PI_DIR kopieren..."
pi_ssh "$PI_HOST" "mkdir -p $PI_DIR"
pi_scp "$JAR" "$PI_HOST:$PI_DIR/zeitserver.jar"
pi_scp "$JAVA_DIR/src/main/resources/config.pi.properties" "$PI_HOST:$PI_DIR/config.properties"
pi_scp "$ROOT/scripts/pi-gps-pps.sh" "$PI_HOST:$PI_DIR/" 2>/dev/null || true
for f in pi-on-pi-start-web.sh pi-stop-old-zeitserver.sh pi-systemd-install.sh pi-crontab-install.sh pi-enable-rtc.sh pi-check-sources.sh pi-verify-version.sh rtc-i2c-read.py; do
  pi_scp "$ROOT/scripts/$f" "$PI_HOST:$PI_DIR/" 2>/dev/null || true
done
pi_ssh "$PI_HOST" "chmod +x $PI_DIR/*.sh 2>/dev/null || true"

echo "[3/3] Webserver neu starten…"
pi_ssh "$PI_HOST" "PI_DIR=$PI_DIR bash -s" <<'REMOTE'
set -uo pipefail
PI_DIR="${PI_DIR/#\~/$HOME}"
cd "$PI_DIR" || exit 1
bash ./pi-stop-old-zeitserver.sh 2>/dev/null || true
sleep 2
bash ./pi-on-pi-start-web.sh
REMOTE

echo ""
echo "Fertig. Im Browser: http://${PI_IP}:8080/"
echo "Log auf dem Pi: ssh $PI_HOST 'tail -f ~/zeitserver/zeitserver-web.log'"
