#!/usr/bin/env bash
# Mac: Raspberry Pi automatisch mit Handy-Hotspot (WLAN) vorbereiten.
# Nutzung: ./scripts/pi-wifi-hotspot.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONF="$ROOT/pi-wifi.conf"
SSH_KEY="$HOME/.ssh/id_ed25519_zeitserver"
PI_USER="${PI_USER:-mohamed}"
PI_HOST="${PI_HOST:-zeit-server.local}"

log() { printf '==> %s\n' "$*"; }
ok()  { printf '[OK] %s\n' "$*"; }
warn(){ printf '[Hinweis] %s\n' "$*"; }

# Alten SSH-Hostkey entfernen (nach Neuinstallation / Hotspot)
ssh-keygen -R "$PI_HOST" 2>/dev/null || true
ssh-keygen -R "zeit-server.local" 2>/dev/null || true

load_wifi_conf() {
  if [[ -f "$CONF" ]]; then
    # shellcheck source=/dev/null
    source "$CONF"
  fi
  if [[ -z "${WIFI_SSID:-}" || -z "${WIFI_PSK:-}" ]]; then
    log "Hotspot-Daten (einmalig) — Dialog auf dem Mac:"
    WIFI_SSID="$(osascript -e 'text returned of (display dialog "WLAN-Name (SSID) des Handy-Hotspots:" default answer "" with title "Pi WLAN")' 2>/dev/null || true)"
    WIFI_PSK="$(osascript -e 'text returned of (display dialog "WLAN-Passwort des Hotspots:" default answer "" with hidden answer with title "Pi WLAN")' 2>/dev/null || true)"
  fi
  WIFI_COUNTRY="${WIFI_COUNTRY:-DE}"
  if [[ -z "${WIFI_SSID:-}" || -z "${WIFI_PSK:-}" ]]; then
    echo "Abbruch: SSID/Passwort fehlen. Lege $CONF an (siehe pi-wifi.conf.example)." >&2
    exit 1
  fi
  if [[ ! -f "$CONF" ]]; then
    cat > "$CONF" <<EOF
WIFI_SSID="$WIFI_SSID"
WIFI_PSK="$WIFI_PSK"
WIFI_COUNTRY=$WIFI_COUNTRY
EOF
    chmod 600 "$CONF"
    ok "Gespeichert in $CONF (nur für dich, nicht ins Git committen)"
  fi
}

find_boot_mount() {
  local v base
  for v in /Volumes/*; do
    [[ -d "$v" ]] || continue
    if [[ -f "$v/config.txt" ]]; then
      echo "$v"
      return 0
    fi
    if [[ -f "$v/firmware/config.txt" ]]; then
      echo "$v"
      return 0
    fi
  done
  return 1
}

write_wifi_to_boot() {
  local boot="$1"
  local wpa_dir="$boot"
  [[ -d "$boot/firmware" ]] && wpa_dir="$boot/firmware"

  log "Schreibe WLAN-Config nach $wpa_dir"

  cat > "$wpa_dir/wpa_supplicant.conf" <<EOF
country=${WIFI_COUNTRY}
ctrl_interface=DIR=/var/run/wpa_supplicant GROUP=netdev
update_config=1

network={
    ssid="${WIFI_SSID}"
    psk="${WIFI_PSK}"
    key_mgmt=WPA-PSK
}
EOF

  # Raspberry Pi OS (Bookworm+) — network-config.yaml
  cat > "$wpa_dir/network-config.yaml" <<EOF
---
wpa_supplicant:
  country: ${WIFI_COUNTRY}
  networks:
    - ssid: "${WIFI_SSID}"
      psk: "${WIFI_PSK}"
      key_mgmt: WPA-PSK
EOF

  ok "WLAN-Konfiguration auf SD-Karte geschrieben"
}

try_ssh_wifi() {
  local key_args=()
  [[ -f "$SSH_KEY" ]] && key_args=(-i "$SSH_KEY")
  if ! ping -c 1 -t 2 "$PI_HOST" &>/dev/null; then
    return 1
  fi
  log "Pi erreichbar — setze WLAN per SSH (NetworkManager)…"
  ssh "${key_args[@]}" -o StrictHostKeyChecking=accept-new "${PI_USER}@${PI_HOST}" \
    "WIFI_SSID='$WIFI_SSID' WIFI_PSK='$WIFI_PSK' WIFI_COUNTRY='$WIFI_COUNTRY' bash -s" <<'REMOTE'
set -e
if command -v nmcli >/dev/null; then
  sudo nmcli device wifi connect "$WIFI_SSID" password "$WIFI_PSK" 2>/dev/null || \
    sudo nmcli connection add type wifi con-name hotspot ifname wlan0 ssid "$WIFI_SSID" wifi-sec.key-mgmt wpa-psk wifi-sec.psk "$WIFI_PSK" 2>/dev/null || true
  sudo nmcli connection up hotspot 2>/dev/null || sudo nmcli device wifi connect "$WIFI_SSID" password "$WIFI_PSK" || true
elif command -v raspi-config >/dev/null; then
  echo "Bitte am Pi: sudo raspi-config → Wireless LAN"
else
  echo "nmcli/raspi-config nicht gefunden"
fi
sleep 3
hostname -I || true
REMOTE
  ok "SSH-WLAN-Befehl gesendet"
  return 0
}

wait_for_pi() {
  log "Warte auf Pi im Netz (max. 90 s) — Mac muss im gleichen Hotspot sein…"
  local i
  for i in $(seq 1 18); do
    if ping -c 1 -t 2 "$PI_HOST" &>/dev/null; then
      ok "Pi erreichbar: $PI_HOST"
      return 0
    fi
    sleep 5
  done
  return 1
}

copy_ssh_key() {
  local key_args=()
  [[ -f "$SSH_KEY" ]] || return 0
  if ssh -o BatchMode=yes -i "$SSH_KEY" "${PI_USER}@${PI_HOST}" true 2>/dev/null; then
    ok "SSH-Schlüssel funktioniert bereits"
    return 0
  fi
  warn "Einmal Passwort für SSH (Pi-Login) im Terminal eingeben:"
  ssh-copy-id -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "${PI_USER}@${PI_HOST}" || true
}

# --- main ---
load_wifi_conf

if boot="$(find_boot_mount 2>/dev/null)"; then
  write_wifi_to_boot "$boot"
  warn "SD-Karte sicher auswerfen, in den Pi stecken, einschalten."
  warn "Handy-Hotspot AN · Mac auch in denselben Hotspot verbinden."
else
  warn "Keine Pi-SD-Karte (boot) am Mac gefunden."
  warn "→ Pi aus, SD-Karte in den Mac, Skript erneut starten."
  try_ssh_wifi || true
fi

echo ""
if wait_for_pi; then
  copy_ssh_key
  ip="$(ssh -o BatchMode=yes -i "$SSH_KEY" "${PI_USER}@${PI_HOST}" 'hostname -I' 2>/dev/null | awk '{print $1}' || true)"
  echo ""
  ok "Fertig."
  echo "  SSH:   ssh ${PI_USER}@${PI_HOST}"
  [[ -n "$ip" ]] && echo "  IP:    $ip"
  echo "  Web:   http://${PI_HOST}:8080/"
else
  echo ""
  warn "Pi noch nicht erreichbar."
  echo "  1. Hotspot am Handy AN"
  echo "  2. Mac mit Hotspot verbinden"
  echo "  3. Pi neu starten (nach SD-Schreiben)"
  echo "  4. Erneut: ./scripts/pi-wifi-hotspot.sh"
fi
