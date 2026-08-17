#!/usr/bin/env bash
# Raspberry Pi: UART0 für GPS (NEO-6M), optional PPS per GPIO18, Tests.
# Auf dem Pi ausführen:  bash pi-gps-pps.sh [apply|test|all]
set -euo pipefail

PPS_GPIO="${PPS_GPIO:-18}"
BOOT_DIR="/boot/firmware"
[[ -d "$BOOT_DIR" ]] || BOOT_DIR="/boot"
CONFIG="$BOOT_DIR/config.txt"
CMDLINE="$BOOT_DIR/cmdline.txt"

mode="${1:-all}"

log() { printf '\n==> %s\n' "$*"; }
ok()  { printf '[OK] %s\n' "$*"; }
warn(){ printf '[WARN] %s\n' "$*"; }
fail(){ printf '[FAIL] %s\n' "$*"; exit 1; }

need_root() {
  [[ "$(id -u)" -eq 0 ]] || fail "Bitte mit sudo ausführen: sudo bash $0 $mode"
}

ensure_line() {
  local file="$1" line="$2"
  grep -qxF "$line" "$file" 2>/dev/null && return 0
  echo "$line" >> "$file"
  ok "hinzugefügt: $line"
}

remove_matching() {
  local file="$1" pattern="$2"
  if grep -qE "$pattern" "$file" 2>/dev/null; then
    sed -i.bak -E "/$pattern/d" "$file"
    ok "entfernt (Muster $pattern): $file"
  fi
}

apply_firmware() {
  need_root
  log "Firmware-Konfiguration ($CONFIG)"
  cp -a "$CONFIG" "${CONFIG}.bak.$(date +%Y%m%d%H%M%S)"

  # UART0 (GPIO14/15) für GPS; Bluetooth vom PL011 weg
  ensure_line "$CONFIG" "enable_uart=1"
  ensure_line "$CONFIG" "dtoverlay=disable-bt"
  # PPS vom GPS-Modul (Pin PPS/1PPS) -> GPIO18 (phys. Pin 12)
  remove_matching "$CONFIG" '^dtoverlay=pps-gpio'
  ensure_line "$CONFIG" "dtoverlay=pps-gpio,gpiopin=${PPS_GPIO}"

  # Alte ldattach-/serielle PPS-Versuche nicht per Overlay
  remove_matching "$CONFIG" '^dtoverlay=pps-gpio.*tty'

  log "cmdline ($CMDLINE)"
  if [[ -f "$CMDLINE" ]]; then
    cp -a "$CMDLINE" "${CMDLINE}.bak.$(date +%Y%m%d%H%M%S)"
    # Konsole nur auf tty1 — serielle GPS-UART nicht als Konsole blockieren
    local line
    line="$(tr -d '\n' < "$CMDLINE")"
    line="$(echo "$line" | sed -E \
      -e 's/[[:space:]]*console=serial0,[0-9]+//g' \
      -e 's/[[:space:]]*console=ttyAMA0,[0-9]+//g')"
    if ! grep -q 'console=tty1' <<<"$line"; then
      line="${line} console=tty1"
    fi
    echo "$line" > "$CMDLINE"
    ok "cmdline bereinigt (kein console=serial0/ttyAMA0)"
  fi

  log "Pakete"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq pps-tools gpsd-clients 2>/dev/null \
    || apt-get install -y -qq pps-tools

  # ldattach auf falschem ttyS0 beenden (PPS läuft über pps-gpio)
  killall ldattach 2>/dev/null || true

  ok "apply fertig — Reboot nötig: sudo reboot"
}

apply_rtc() {
  need_root
  log "I2C + DS3231 RTC ($CONFIG)"
  cp -a "$CONFIG" "${CONFIG}.bak.$(date +%Y%m%d%H%M%S)" 2>/dev/null || true
  ensure_line "$CONFIG" "dtparam=i2c_arm=on"
  remove_matching "$CONFIG" '^dtoverlay=i2c-rtc'
  ensure_line "$CONFIG" "dtoverlay=i2c-rtc,ds3231"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq i2c-tools util-linux 2>/dev/null \
    || apt-get install -y -qq i2c-tools
  ok "RTC apply fertig — Reboot: sudo reboot"
}

free_serial() {
  systemctl stop hciuart 2>/dev/null || true
  systemctl stop serial-getty@ttyAMA0.service 2>/dev/null || true
  systemctl stop dev-serial0.device 2>/dev/null || true
}

serial_dev() {
  if [[ -e /dev/serial0 ]]; then
    readlink -f /dev/serial0
  elif [[ -e /dev/ttyAMA0 ]]; then
    echo /dev/ttyAMA0
  else
    echo /dev/ttyS0
  fi
}

test_nmea() {
  log "NMEA-Test (UART GPS)"
  free_serial
  local dev
  dev="$(serial_dev)"
  log "Seriellgerät: $dev (Alias serial0 -> $(readlink -f /dev/serial0 2>/dev/null || echo '?'))"

  if [[ ! -e "$dev" ]]; then
    fail "Kein $dev — enable_uart=1 und Reboot?"
  fi

  stty -F "$dev" 9600 cs8 -cstopb -parenb raw -echo 2>/dev/null || true

  local tmp
  tmp="$(mktemp)"
  timeout 20 cat "$dev" > "$tmp" 2>/dev/null || true

  if grep -qE '\$G[PN]RMC' "$tmp"; then
    ok "NMEA empfangen ($dev)"
    grep -m 2 -E '\$G[PN]RMC' "$tmp" | sed 's/^/    /'
    if grep -qE ',A,' "$tmp"; then
      ok "GPS-Fix (RMC Status A)"
    else
      warn "Noch kein Fix (RMC Status V) — Antenne nach draußen, 2–5 Min warten"
    fi
  else
    warn "Keine RMC-Zeilen in 20s auf $dev"
    warn "Prüfen: TX(GPS)->RX(Pi Pin10), RX(GPS)->TX(Pi Pin8), 3.3V, GND, Baud 9600"
    head -5 "$tmp" | sed 's/^/    /' || true
    return 1
  fi
  rm -f "$tmp"
}

test_pps() {
  log "PPS-Test (GPIO${PPS_GPIO} /dev/pps0)"
  if [[ ! -e /dev/pps0 ]]; then
    warn "/dev/pps0 fehlt — dtoverlay=pps-gpio und Reboot? journalctl -k | grep -i pps"
    return 1
  fi

  if ! command -v ppstest >/dev/null; then
    apt-get install -y -qq pps-tools
  fi

  local out rc=0
  out="$(timeout 8 ppstest /dev/pps0 2>&1)" || rc=$?
  if grep -qE 'assert|clear|source' <<<"$out" && ! grep -q 'Connection timed out' <<<"$out"; then
    ok "PPS-Impulse sichtbar"
    grep -m 3 -E 'assert|clear' <<<"$out" | sed 's/^/    /' || head -3 <<<"$out"
    return 0
  fi

  warn "Keine PPS-Impulse (ppstest timeout)"
  warn "Kabel: GPS PPS/1PPS -> Pi GPIO${PPS_GPIO} (Pin 12), GND gemeinsam"
  warn "GPS braucht oft Satelliten-Fix für 1 Hz PPS"
  journalctl -k -b 0 2>/dev/null | grep -i pps | tail -5 | sed 's/^/    /' || true
  return 1
}

test_rtc() {
  log "RTC (DS3231)"
  if command -v hwclock >/dev/null; then
    hwclock -r 2>/dev/null && ok "hwclock lesbar" || warn "hwclock fehlgeschlagen"
    if timedatectl show -p NTPSynchronized --value 2>/dev/null | grep -q yes; then
      hwclock -w 2>/dev/null && ok "Systemzeit -> RTC geschrieben (hwclock -w)" || true
    fi
  fi
  i2cdetect -y 1 2>/dev/null | grep -E '68|UU' && ok "I2C 0x68 (RTC) sichtbar" || warn "i2cdetect: RTC nicht an 0x68"
}

test_i2c() {
  log "I2C-Scan"
  i2cdetect -y 1 2>/dev/null || warn "i2cdetect nicht verfügbar"
}

summary_config() {
  log "Aktuelle Boot-Zeilen"
  grep -E '^(enable_uart|dtoverlay=disable-bt|dtoverlay=pps-gpio)' "$CONFIG" 2>/dev/null | sed 's/^/    /' || true
  echo -n "    cmdline: "; tr '\n' ' ' < "$CMDLINE" 2>/dev/null; echo
}

run_tests() {
  summary_config
  test_i2c
  test_rtc || true
  local nmea=0 pps=0
  test_nmea && nmea=1 || true
  test_pps && pps=1 || true

  log "Ergebnis"
  [[ "$nmea" -eq 1 ]] && ok "GPS NMEA" || fail "GPS NMEA — Verdrahtung/UART prüfen"
  if [[ "$pps" -eq 1 ]]; then
    ok "GPS PPS"
  else
    warn "PPS optional für Java-Zeitserver; NMEA reicht für GpsSerialTimeSource"
  fi
}

case "$mode" in
  apply) apply_firmware ;;
  apply-rtc) apply_rtc ;;
  test)  run_tests ;;
  all)
    apply_firmware
    apply_rtc
    log "Nach Reboot erneut: sudo bash $0 test"
    ;;
  *)
    echo "Usage: sudo bash $0 {apply|apply-rtc|test|all}"
    echo "  PPS_GPIO=18 (default) — GPIO für GPS-PPS-Pin"
    exit 2
    ;;
esac
