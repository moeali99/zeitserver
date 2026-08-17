#!/usr/bin/env bash
# DS3231 RTC auf dem Pi aktivieren (I2C + Kernel-Overlay). Auf dem Pi mit sudo ausführen.
set -euo pipefail
DIR="${HOME}/zeitserver"
bash "$DIR/pi-gps-pps.sh" apply-rtc
echo ""
echo "==> Reboot nötig:"
echo "  sudo reboot"
echo ""
echo "Nach Reboot — RTC mit Systemzeit synchronisieren und testen:"
echo "  sudo hwclock -w"
echo "  bash $DIR/pi-check-sources.sh"
echo "  curl -s http://127.0.0.1:8080/api/status | grep -A3 '\"id\": \"RTC\"'"
