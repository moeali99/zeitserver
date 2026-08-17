#!/usr/bin/env bash
# DCF77 GPIO-Test (Java kurz stoppen). Zeigt, ob Impulse am BCM 4 ankommen.
#   ssh -t -i ~/.ssh/id_ed25519_zeitserver mohamed@172.20.10.2 'bash ~/zeitserver/pi-test-dcf77-gpio.sh'
set -euo pipefail
GPIO="${DCF77_GPIO:-4}"
DIR="${HOME}/zeitserver"

echo "==> Zeitserver kurz stoppen (sonst GPIO busy)"
bash "$DIR/pi-stop-old-zeitserver.sh" 2>/dev/null || true
sleep 2

echo "==> gpiomon 12 s auf BCM $GPIO (active-low + pull-up)"
timeout 12 gpiomon -l -b pull-up -c gpiochip0 -e both -F "%S %E " "$GPIO" 2>&1 | tee /tmp/dcf77-edges.txt | head -40
COUNT="$(grep -cE 'rising|falling' /tmp/dcf77-edges.txt 2>/dev/null || echo 0)"
echo ""
echo "Edges gezählt: $COUNT (gut: >= 8 in 12 s ≈ 1 Bit/s)"

echo ""
echo "==> ohne active-low (Vergleich)"
timeout 6 gpiomon -b pull-up -c gpiochip0 -e both -F "%S %E " "$GPIO" 2>&1 | head -20 || true

echo ""
echo "==> Zeitserver wieder starten"
bash "$DIR/pi-on-pi-start-web.sh"
