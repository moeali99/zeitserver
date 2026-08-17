#!/usr/bin/env bash
# GPS UART-Rechte dauerhaft + Zeitserver neu starten.
#   ssh -t -i ~/.ssh/id_ed25519_zeitserver mohamed@172.20.10.2 'bash ~/zeitserver/pi-fix-gps-perms.sh'
set -euo pipefail
DIR="${HOME}/zeitserver"

echo "==> udev-Regel (hart)"
sudo tee /etc/udev/rules.d/99-gps-uart.rules >/dev/null <<'EOF'
# GPS an miniUART /dev/ttyS0 (Alias /dev/serial0)
KERNEL=="ttyS0", SUBSYSTEM=="tty", GROUP="dialout", MODE="0660"
EOF
sudo udevadm control --reload-rules
sudo udevadm trigger --action=add --subsystem-match=tty --name-match=ttyS0 2>/dev/null || true

echo "==> Sofort-Rechte"
sudo chown root:dialout /dev/ttyS0
sudo chmod 660 /dev/ttyS0
# Auch nach jedem Boot (falls udev überschreibt)
echo '@reboot root sleep 8; chown root:dialout /dev/ttyS0; chmod 660 /dev/ttyS0' | sudo tee /etc/cron.d/zeitserver-gps-uart >/dev/null
sudo chmod 644 /etc/cron.d/zeitserver-gps-uart
ls -l /dev/ttyS0 /dev/serial0

echo "==> NMEA-Test (5 s)"
timeout 5 cat /dev/serial0 2>/dev/null | head -8 || echo "(keine Daten — Kabel/GPS prüfen)"

echo "==> Zeitserver neu starten"
bash "$DIR/pi-stop-old-zeitserver.sh" 2>/dev/null || true
sleep 1
bash "$DIR/pi-on-pi-start-web.sh"

echo ""
echo "RTC ist bereits OK (sysfs). Browser: http://$(hostname -I | awk '{print $1}'):8080/  (Cmd+Shift+R)"
