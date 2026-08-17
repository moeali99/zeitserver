#!/usr/bin/env bash
# Projektroot — Build, Deploy und Test auf dem Raspberry Pi.
#   ./setup-pi.sh       Setup + Deploy + Browser
#   ./setup-pi.sh test  Volltest inkl. GPS-UART (Reboot wenn nötig)
ROOT="$(cd "$(dirname "$0")" && pwd)/scripts"
case "${1:-}" in
  test) shift; exec "$ROOT/pi-test-all.sh" "$@" ;;
  *) exec "$ROOT/pi-all.sh" "$@" ;;
esac
