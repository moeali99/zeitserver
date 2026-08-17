#!/usr/bin/env bash
# Einmal auf dem Pi (oder per ssh -t): GPS-Rechte + RTC/I2C + Zeitserver neu starten.
#   ssh -t -i ~/.ssh/id_ed25519_zeitserver mohamed@172.20.10.2 'bash ~/zeitserver/pi-fix-gps-rtc.sh'
set -euo pipefail
DIR="${HOME}/zeitserver"

echo "==> GPS UART Rechte"
sudo chown root:dialout /dev/ttyS0 2>/dev/null || true
sudo chmod 660 /dev/ttyS0 2>/dev/null || true
echo 'KERNEL=="ttyS0", GROUP="dialout", MODE="0660"' | sudo tee /etc/udev/rules.d/99-gps-uart.rules >/dev/null
sudo udevadm control --reload-rules 2>/dev/null || true
ls -l /dev/ttyS0 /dev/serial0 || true

echo "==> RTC / I2C (config.txt)"
if [[ -f "$DIR/pi-enable-rtc.sh" ]]; then
  sudo bash "$DIR/pi-enable-rtc.sh"
else
  echo "pi-enable-rtc.sh fehlt — bitte zuerst deployen" >&2
fi

echo "==> Zeitserver neu starten"
bash "$DIR/pi-stop-old-zeitserver.sh" 2>/dev/null || true
sleep 1
bash "$DIR/pi-on-pi-start-web.sh"

echo ""
echo "Nächster Schritt: sudo reboot  (für RTC dauerhaft)"
echo "Danach Dashboard: http://$(hostname -I | awk '{print $1}'):8080/"
