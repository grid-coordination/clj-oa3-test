#!/usr/bin/env bash
# Shared functions for test-stack scripts.
# Source this file — do not execute directly.

# ---------------------------------------------------------------------------
# Directory resolution
# ---------------------------------------------------------------------------

: "${VTN_RI_DIR:=/Users/dcj/projects/OpenADR/repo/openadr3-vtn-reference-implementation}"
: "${CBS_DIR:=/Users/dcj/projects/OpenADR/repo/test-callback-service}"

BL_TOKEN="YmxfY2xpZW50OjEwMDE="   # base64(bl_client:1001)
VTN_URL="http://localhost:8080/openadr3/3.1.0"

resolve_dirs() {
    if [ ! -d "$VTN_RI_DIR" ]; then
        echo "Error: VTN_RI_DIR not found: $VTN_RI_DIR" >&2
        echo "Set VTN_RI_DIR to the VTN-RI repo path." >&2
        exit 1
    fi
}

# ---------------------------------------------------------------------------
# Kill functions
# ---------------------------------------------------------------------------

kill_mosquitto_all() {
    tmux kill-session -t mosquitto-dynsec 2>/dev/null && echo "  Stopped mosquitto-dynsec tmux session"
    brew services stop mosquitto 2>/dev/null && echo "  Stopped mosquitto brew service"
    pkill -x mosquitto 2>/dev/null && echo "  Killed mosquitto process(es)"
    return 0
}

kill_vtn() {
    tmux kill-session -t vtn-ri 2>/dev/null && echo "  Stopped vtn-ri tmux session"
    return 0
}

kill_callback() {
    tmux kill-session -t vtn-callbk-svc 2>/dev/null && echo "  Stopped vtn-callbk-svc tmux session"
    return 0
}

# ---------------------------------------------------------------------------
# Wait / verify helpers
# ---------------------------------------------------------------------------

wait_port_free() {
    local port=$1 timeout=${2:-5}
    for i in $(seq 1 "$timeout"); do
        if ! lsof -ti :"$port" &>/dev/null; then
            return 0
        fi
        sleep 1
    done
    echo "Warning: port $port still in use after ${timeout}s" >&2
    return 1
}

wait_http() {
    local url=$1 timeout=${2:-15}
    for i in $(seq 1 "$timeout"); do
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $BL_TOKEN" "$url" 2>/dev/null)
        if echo "$code" | grep -q "200\|401\|403"; then
            return 0
        fi
        sleep 1
    done
    return 1
}

wait_mqtt_anon() {
    local timeout=${1:-5}
    for i in $(seq 1 "$timeout"); do
        if mosquitto_pub -h 127.0.0.1 -p 1883 -t "test/ping" -m "ping" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

wait_mqtt_dynsec() {
    local timeout=${1:-5}
    for i in $(seq 1 "$timeout"); do
        if mosquitto_pub -h 127.0.0.1 -p 1883 -u vtn_admin -P vtn_secret -t "test/ping" -m "ping" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# ---------------------------------------------------------------------------
# VTN storage
# ---------------------------------------------------------------------------

delete_vtn_storage() {
    rm -f "$VTN_RI_DIR/tmp/fileStorage.json"
}

# ---------------------------------------------------------------------------
# Config modification (uses VTN-RI's Python venv for PyYAML)
# ---------------------------------------------------------------------------

set_config() {
    local auth=$1 username=$2 password=$3
    "$VTN_RI_DIR/venv/bin/python3" - "$VTN_RI_DIR/config.yaml" "$auth" "$username" "$password" <<'PYEOF'
import sys, yaml

config_path, auth, username, password = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

with open(config_path) as f:
    cfg = yaml.safe_load(f)

cfg['mqtt']['broker']['auth'] = auth
cfg['mqtt']['broker']['username'] = None if username == 'null' else username
cfg['mqtt']['broker']['password'] = None if password == 'null' else password

with open(config_path, 'w') as f:
    yaml.dump(cfg, f, default_flow_style=False, sort_keys=False)
PYEOF
}

set_config_anonymous() {
    set_config "ANONYMOUS" "null" "null"
}

set_config_dynsec() {
    set_config "OAUTH2_BEARER_TOKEN" "vtn_admin" "vtn_secret"
}

# ---------------------------------------------------------------------------
# Service start helpers
# ---------------------------------------------------------------------------

start_vtn() {
    echo "Starting VTN-RI..."
    "$VTN_RI_DIR/bin/vtn-start.sh"
}

start_callback() {
    if [ ! -d "$CBS_DIR" ]; then
        echo "Warning: CBS_DIR not found: $CBS_DIR — skipping callback service" >&2
        return 1
    fi

    echo "Starting callback service..."

    # Check/create venv
    if ! "$CBS_DIR/venv/bin/python3" --version &>/dev/null; then
        echo "  Creating callback service venv..."
        if ! command -v python3.10 &>/dev/null; then
            echo "Error: python3.10 is required." >&2
            return 1
        fi
        rm -rf "$CBS_DIR/venv"
        python3.10 -m venv "$CBS_DIR/venv"
        "$CBS_DIR/venv/bin/pip" install -q -r "$CBS_DIR/requirements.txt"
    fi

    tmux new-session -d -s vtn-callbk-svc \
        "cd '$CBS_DIR' && source venv/bin/activate && python3 run.py 2>&1 | tee /tmp/vtn-callbk-svc.log"

    # Wait for startup
    for i in $(seq 1 10); do
        if curl -s -o /dev/null -w "%{http_code}" http://localhost:5000 2>/dev/null | grep -q "200\|404"; then
            echo "  Callback service is up — http://localhost:5000"
            return 0
        fi
        sleep 1
    done
    echo "Warning: callback service started but not responding after 10s" >&2
    return 1
}

# ---------------------------------------------------------------------------
# Stop everything
# ---------------------------------------------------------------------------

stop_all() {
    echo "Stopping all services..."
    kill_callback
    kill_vtn
    kill_mosquitto_all
    sleep 1

    local ok=true
    wait_port_free 8080 5 || ok=false
    wait_port_free 1883 5 || ok=false

    if $ok; then
        echo "All services stopped, ports free."
    else
        echo "Warning: some ports still in use." >&2
    fi
    return 0
}
