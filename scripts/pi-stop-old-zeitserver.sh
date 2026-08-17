#!/usr/bin/env bash
# Beendet alte Zeitserver- und GPIO-Helferprozesse vor einem Neustart.
set -euo pipefail

pkill -f 'zeitserver-1.0-SNAPSHOT-all.jar' 2>/dev/null || true
pkill -f 'zeitserver.jar' 2>/dev/null || true
pkill -f 'gpiomon.*gpiochip0' 2>/dev/null || true
sleep 1
