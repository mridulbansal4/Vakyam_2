#!/usr/bin/env bash
# ==============================================================================
# iQOO Telemetry & Hardware Maximizer Launcher (macOS / Linux)
# ==============================================================================
ACTION="${1:-start}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$ACTION" in
    start|stop|status)
        python3 "$SCRIPT_DIR/iqoo_telemetry_maximizer.py" "$ACTION"
        ;;
    *)
        echo "Usage: ./run_maximizer.sh [start|stop|status]"
        exit 1
        ;;
esac
