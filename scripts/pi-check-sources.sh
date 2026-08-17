#!/usr/bin/env bash
# Schnelltest GPS und DCF77 auf dem Pi.
set -euo pipefail

BCM="${DCF77_GPIO:-4}"

echo "=== UART / GPS ==="
ls -l /dev/serial0 /dev/ttyAMA0 /dev/ttyS0 2>/dev/null || true
if systemctl is-active hciuart >/dev/null 2>&1; then
  echo "hciuart aktiv — für GPS: sudo systemctl stop hciuart"
fi

if [[ -e /dev/serial0 ]]; then
  echo "NMEA (10 s):"
  timeout 10 stdbuf -oL cat /dev/serial0 2>/dev/null \
    | grep -m 5 -E 'GPGGA|GNGGA|GPRMC|GNRMC|GPGLL|GNGLL' \
    || echo "Keine NMEA-Zeilen — Kabel/Baud prüfen"
  echo "GPS abgedeckt? In GGA: Feld 7=Satelliten (0=offline), GLL/RMC Status V=kein Fix"
fi

echo ""
echo "=== DCF77 GPIO BCM ${BCM} ==="
if command -v gpioget >/dev/null; then
  v="$(gpioget --chip gpiochip0 "$BCM" 2>/dev/null || echo '?')"
  echo "Pegel: $v (Impulse: gpiomon oder Dashboard-Detail)"
else
  echo "gpiod fehlt: sudo apt install -y gpiod"
fi
echo "Erste Dekodierung oft nach 1–2 Minuten."

echo ""
echo "=== RTC DS3231 (I2C 0x68) ==="
if grep -q '^#dtparam=i2c_arm=on' /boot/firmware/config.txt 2>/dev/null \
   || ! grep -q '^dtparam=i2c_arm=on' /boot/firmware/config.txt 2>/dev/null; then
  if ! grep -q '^dtparam=i2c_arm=on' /boot/firmware/config.txt 2>/dev/null; then
    echo "[FEHLER] I2C aus — einmalig: sudo bash ~/zeitserver/pi-enable-rtc.sh && sudo reboot"
  fi
fi
if [[ ! -e /dev/i2c-1 ]]; then
  echo "/dev/i2c-1 fehlt (GPIO-I2C) — nach pi-enable-rtc.sh + Reboot erwartet"
fi
I2CDETECT="$(command -v i2cdetect 2>/dev/null || echo /usr/sbin/i2cdetect)"
if [[ -x "$I2CDETECT" ]]; then
  for bus in 1 2 0; do
    [[ -e "/dev/i2c-$bus" ]] || continue
    echo "--- i2cdetect -y $bus ---"
    "$I2CDETECT" -y "$bus" 2>/dev/null | grep -E '68|UU|10:' || echo "  kein 0x68 auf Bus $bus"
  done
else
  echo "i2c-tools fehlen — sudo bash ~/zeitserver/pi-enable-rtc.sh installiert sie"
fi
if [[ -x ~/zeitserver/rtc-i2c-read.py ]]; then
  for bus in 1 2 0; do
    [[ -e "/dev/i2c-$bus" ]] || continue
    if out="$(python3 ~/zeitserver/rtc-i2c-read.py --bus "$bus" 2>/dev/null)"; then
      echo "[OK] Python I2C Bus $bus: $out"
      break
    fi
  done
fi
if [[ -r /sys/class/rtc/rtc0/since_epoch ]]; then
  echo "sysfs: $(cat /sys/class/rtc/rtc0/since_epoch) s"
elif command -v hwclock >/dev/null; then
  sudo hwclock -r 2>/dev/null || hwclock -r 2>/dev/null || true
fi

echo ""
echo "=== Web-API ==="
if curl -sf http://127.0.0.1:8080/api/status >/dev/null 2>&1; then
  curl -s http://127.0.0.1:8080/api/status | python3 -c "
import sys, json
d=json.load(sys.stdin)
for s in d.get('sources', []):
    print(f\"  {s['id']}: utc={s.get('utc')} — {(s.get('detail') or '')[:70]}\")
rtc=next((s for s in d.get('sources',[]) if s['id']=='RTC'), None)
if rtc and rtc.get('utc'):
    print('  [OK] RTC im Dashboard')
elif rtc:
    print('  [WARN] RTC ohne Zeit — I2C/Overlay prüfen')
"
else
  echo "Nicht erreichbar — bash ~/zeitserver/pi-on-pi-start-web.sh"
fi
