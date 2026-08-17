#!/usr/bin/env bash
# Sucht den Pi im gleichen WLAN wie der Mac (Router oder Handy — egal).
set -euo pipefail

mac_ip="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
if [[ -z "$mac_ip" ]]; then
  echo "Kein WLAN aktiv. Mac bitte mit demselben WLAN verbinden wie der Pi." >&2
  exit 1
fi

base="${mac_ip%.*}"
last="${mac_ip##*.}"

# Typische Größe: /24 (1–254) oder iPhone-Hotspot /28 (1–14)
if [[ "$mac_ip" == 172.20.10.* ]]; then
  range_end=20
  net_hint="iPhone-Netz (Persönlicher Hotspot / Internet teilen)"
else
  range_end=254
  net_hint="Heim-WLAN / Router"
fi

echo "Mac: $mac_ip ($net_hint)"
echo "Scanne ${base}.1–${range_end} nach Geräten mit SSH (Port 22)…"
echo ""

pi_ip=""
for i in $(seq 1 "$range_end"); do
  ip="${base}.${i}"
  [[ "$ip" == "$mac_ip" ]] && continue
  if ! ping -c 1 -t 1 "$ip" &>/dev/null; then
    continue
  fi
  ssh_mark=""
  if nc -z -G 1 "$ip" 22 &>/dev/null; then
    ssh_mark="  ← SSH (vermutlich Pi)"
    pi_ip="$ip"
  fi
  echo "  $ip$ssh_mark"
done

echo ""
if [[ -n "$pi_ip" ]]; then
  echo "Pi gefunden. Verbinden:"
  echo "  ssh -i ~/.ssh/id_ed25519_zeitserver mohamed@${pi_ip}"
  echo "  Web: http://${pi_ip}:8080/"
  echo ""
  echo "Oder per Name (falls mDNS geht):"
  echo "  ssh -i ~/.ssh/id_ed25519_zeitserver mohamed@zeit-server.local"
elif nc -z -G 2 zeit-server.local 22 &>/dev/null; then
  echo "zeit-server.local antwortet (SSH) — direkt:"
  echo "  ssh -i ~/.ssh/id_ed25519_zeitserver mohamed@zeit-server.local"
else
  echo "Kein SSH-Gerät gefunden."
  echo ""
  echo "Mac und Pi müssen im GLEICHEN WLAN sein (gleicher Name/SSID)."
  echo "  • Heimrouter: Mac + Pi beide mit Router-WLAN verbinden"
  echo "  • Nur Handy: Persönlicher Hotspot AN, Mac + Pi verbinden"
  echo ""
  echo "Am Pi prüfen (Monitor):  iwgetid -r   und   hostname -I"
fi
