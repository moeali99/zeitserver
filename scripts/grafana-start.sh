#!/usr/bin/env bash
# Startet Prometheus + Grafana (Docker). Pi-IP in grafana/prometheus.yml anpassen.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/grafana"
echo "Grafana:  http://localhost:3000  (admin / zeitserver)"
echo "Prometheus: http://localhost:9090"
docker compose up -d
