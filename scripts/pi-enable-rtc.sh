#!/usr/bin/env bash
# DS3231 RTC auf dem Pi aktivieren — EINMAL mit sudo ausführen, danach Reboot.
#   sudo bash ~/zeitserver/pi-enable-rtc.sh
set -euo pipefail

[[ "$(id -u)" -eq 0 ]] || { echo "Bitte mit sudo ausführen: sudo bash $0" >&2; exit 1; }

BOOT_DIR="/boot/firmware"
[[ -d "$BOOT_DIR" ]] || BOOT_DIR="/boot"
CFG="$BOOT_DIR/config.txt"

echo "==> I2C + DS3231 in $CFG"
cp -a "$CFG" "${CFG}.bak.$(date +%Y%m%d%H%M%S)"

grep -q '^dtparam=i2c_arm=on' "$CFG" 2>/dev/null || {
  sed -i '/^#dtparam=i2c_arm=on/s/^#//' "$CFG" 2>/dev/null || true
  grep -q '^dtparam=i2c_arm=on' "$CFG" || echo "dtparam=i2c_arm=on" >> "$CFG"
}

sed -i '/^dtoverlay=i2c-rtc/d' "$CFG"
echo "dtoverlay=i2c-rtc,ds3231" >> "$CFG"

echo "==> Pakete"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y i2c-tools util-linux

echo "==> Prüfung (nach Reboot vollständig)"
if command -v i2cdetect >/dev/null; then
  for bus in 1 0 2; do
    echo "--- i2cdetect -y $bus ---"
    i2cdetect -y "$bus" 2>/dev/null | grep -E '68|UU|10:' || true
  done
fi

echo ""
echo "[OK] Fertig. Jetzt: sudo reboot"
echo "Nach Reboot: sudo hwclock -w && bash ~/zeitserver/pi-check-sources.sh"
