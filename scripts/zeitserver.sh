#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_DIR="$ROOT_DIR/zeitserver-java"

CMD="${1:-once}"
shift || true

JAR_PATH="$JAVA_DIR/target/zeitserver-1.0-SNAPSHOT-all.jar"

build_if_needed() {
  if [[ ! -f "$JAR_PATH" ]]; then
    echo "[zeitserver] Building fat-jar (first run)..."
    (cd "$JAVA_DIR" && mvn -q package -DskipTests)
  fi
}

case "$CMD" in
  pi|deploy|setup-pi)
    exec "$ROOT_DIR/scripts/pi-all.sh" "$@"
    ;;
  once|monitor|web|sntp|daytime)
    build_if_needed
    (cd "$JAVA_DIR" && java -jar "$JAR_PATH" "$CMD" "$@")
    ;;
  build)
    echo "[zeitserver] Building fat-jar..."
    (cd "$JAVA_DIR" && mvn -q package -DskipTests)
    ;;
  *)
    echo "Usage: $0 {build|once|monitor|web|sntp|daytime|pi} [args...]"
    echo "  $0 pi   — Pi finden, deployen, Web starten"
    echo "Examples:"
    echo "  $0 once"
    echo "  $0 monitor"
    echo "  $0 web"
    echo "  $0 sntp pool.ntp.org"
    echo "  $0 daytime time.nist.gov"
    exit 2
    ;;
esac

