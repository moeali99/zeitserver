#!/usr/bin/env bash
# GPS-Port freigeben + Zeitserver neu starten.
#   ssh -t -i ~/.ssh/id_ed25519_zeitserver mohamed@172.20.10.2 'bash ~/zeitserver/pi-repair-now.sh'
set -euo pipefail
DIR="${HOME}/zeitserver"

echo "==> serial-getty auf ttyS0 stoppen (blockiert GPS!)"
sudo systemctl stop serial-getty@ttyS0.service 2>/dev/null || true
sudo systemctl disable serial-getty@ttyS0.service 2>/dev/null || true
sudo systemctl mask serial-getty@ttyS0.service 2>/dev/null || true

echo "==> GPS UART Rechte"
sudo chown root:dialout /dev/ttyS0 2>/dev/null || true
sudo chmod 660 /dev/ttyS0 2>/dev/null || true
echo 'KERNEL=="ttyS0", SUBSYSTEM=="tty", GROUP="dialout", MODE="0660"' | sudo tee /etc/udev/rules.d/99-gps-uart.rules >/dev/null
echo '@reboot root sleep 8; chown root:dialout /dev/ttyS0; chmod 660 /dev/ttyS0; systemctl stop serial-getty@ttyS0.service 2>/dev/null || true' \
  | sudo tee /etc/cron.d/zeitserver-gps-uart >/dev/null
sudo udevadm control --reload-rules 2>/dev/null || true
ls -l /dev/ttyS0
systemctl is-active serial-getty@ttyS0.service 2>/dev/null || echo "serial-getty: aus (gut)"

echo "==> cmdline ohne console=serial0 (falls vorhanden)"
for CMD in /boot/firmware/cmdline.txt /boot/cmdline.txt; do
  if [[ -f "$CMD" ]] && grep -q 'console=serial0' "$CMD" 2>/dev/null; then
    sudo cp -a "$CMD" "${CMD}.bak.gps"
    sudo sed -i -E 's/ ?console=serial0,[0-9]+//g; s/ ?console=ttyAMA0,[0-9]+//g' "$CMD"
    echo "  bereinigt: $CMD (Reboot empfohlen)"
  fi
done

echo "==> Zeitserver neu"
bash "$DIR/pi-stop-old-zeitserver.sh" 2>/dev/null || true
sleep 1
# hängende Port-Locks lösen
fuser -k /dev/ttyS0 2>/dev/null || true
sleep 1
bash "$DIR/pi-on-pi-start-web.sh"

sleep 3
echo ""
curl -s http://127.0.0.1:8080/api/status | python3 -c '
import sys,json
d=json.load(sys.stdin)
print("build", d.get("buildId"))
for s in d.get("sources",[]):
    print(s.get("id"), "|", (s.get("detail") or "")[:80], "|", s.get("utc"))
'
echo ""
echo "Browser: http://$(hostname -I | awk '{print $1}'):8080/  (Cmd+Shift+R)"
