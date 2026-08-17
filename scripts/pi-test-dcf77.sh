#!/usr/bin/env bash
# DCF77-Test auf dem Pi (vom Mac: ./scripts/pi-test-dcf77.sh)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=pi-ssh-lib.sh
source "$ROOT/scripts/pi-ssh-lib.sh" 2>/dev/null || true

PI_HOST="${PI_HOST:-$(pi_find_host 2>/dev/null || echo mohamed@172.20.10.3)}"
pi_ssh_init 2>/dev/null || true

echo "==> DCF77-Test auf $PI_HOST"
pi_ssh "$PI_HOST" 'bash -s' 2>&1 <<'REMOTE'
set -uo pipefail
GPIO=4

echo "=== 1) Java / Dashboard ==="
if curl -sf http://127.0.0.1:8080/api/status >/dev/null 2>&1; then
  curl -s http://127.0.0.1:8080/api/status | python3 -c "
import sys,json
d=json.load(sys.stdin)
x=next(a for a in d['sources'] if a['id']=='DCF77')
print('  utc:', x.get('utc'))
print('  detail:', x.get('detail'))
"
else
  echo "  Web aus —: bash ~/zeitserver/pi-on-pi-start-web.sh"
fi

echo ""
echo "=== 2) GPIO BCM ${GPIO} Impulse (Java kurz stoppen) ==="
pkill -f zeitserver.jar 2>/dev/null || true
sleep 3

if command -v gpioget >/dev/null; then
  active=0 inactive=0
  for i in $(seq 1 100); do
    v=$(gpioget --chip gpiochip0 "$GPIO" 2>/dev/null || gpioget "GPIO$GPIO" 2>/dev/null || echo "?")
    echo "$v" | grep -q inactive && inactive=$((inactive+1)) || active=$((active+1))
    sleep 0.1
  done
  echo "  10 s: active=$active inactive=$inactive (gpiod)"
  if command -v pinctrl >/dev/null; then
    pinctrl get "$GPIO" 2>/dev/null || true
  fi
  if [[ $active -gt 90 || $inactive -gt 90 ]]; then
    echo "  [WARN] Kaum Pegelwechsel — Antenne/Fenster/Kabel prüfen"
  fi
else
  echo "  sudo apt install -y gpiod"
fi

if command -v gpiomon >/dev/null; then
  echo "  gpiomon (max 20 Events, 25 s):"
  timeout 25 gpiomon -l -b pull-up --chip gpiochip0 -n 20 "$GPIO" 2>&1 | head -12 || echo "  keine Events"
fi

echo ""
echo "=== 3) Java starten, 2 Min auf Dekodierung warten ==="
bash ~/zeitserver/pi-on-pi-start-web.sh >/dev/null 2>&1 || true
for s in 30 60 90 120; do
  sleep 30
  curl -sf http://127.0.0.1:8080/api/status >/dev/null 2>&1 || continue
  curl -s http://127.0.0.1:8080/api/status | python3 -c "
import sys,json
d=json.load(sys.stdin)
x=next(a for a in d['sources'] if a['id']=='DCF77')
print('  ${s}s:', x.get('utc') or '-', '|', (x.get('detail') or '')[:65])
" 2>/dev/null
  curl -s http://127.0.0.1:8080/api/status | grep -q 'Dekodiert OK' && echo "  [OK] DCF77 dekodiert!" && exit 0
done
echo "  [Hinweis] Keine Dekodierung — Hardware prüfen (siehe docs/DCF77-DECODIERUNG.md)"
REMOTE

echo ""
echo "Dashboard: http://${PI_HOST#*@}:8080/"
