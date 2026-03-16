# Running the Integration Tests

This document explains how to set up and run the full integration test stack.

## Prerequisites

### Required software

- **Java** (11+) and **Clojure CLI** — [install guide](https://clojure.org/guides/install_clojure)
- **Python 3.10** — for the VTN-RI (newer versions break gevent/connexion)
- **tmux** — the stack scripts manage long-running processes in tmux sessions
- **Mosquitto MQTT broker** — `brew install mosquitto` on macOS

### Required sibling repositories

All repos must be cloned as siblings in the same parent directory:

```
parent/
  clj-oa3/                                       # pure client library
  clj-oa3-client/                                # Component wrapper
  clj-oa3-test/                                  # this repo
  specification/                                 # OpenADR 3 OpenAPI specs
```

The VTN-RI and test-callback-service repos are also needed but can live
anywhere — their locations are configured via environment variables.

## The Test Stack

Three services must be running before the tests will pass:

```
┌──────────────────────────────────────────────────┐
│  Mosquitto MQTT Broker          localhost:1883   │
├──────────────────────────────────────────────────┤
│  VTN Reference Implementation   localhost:8080   │
│  python -m swagger_server                        │
├──────────────────────────────────────────────────┤
│  Test Callback Service          localhost:5000   │
│  python run.py           (optional — webhooks)   │
└──────────────────────────────────────────────────┘
```

| Service | Port | Required? | Purpose |
|---------|------|-----------|---------|
| Mosquitto | 1883 | Yes | MQTT broker (anonymous or dynsec) |
| VTN-RI | 8080 | Yes | OpenADR 3 VTN server |
| Test Callback Service | 5000 | Optional | Receives webhook notifications |

## Starting the Stack

### Using the stack scripts (recommended)

The scripts in `bin/` handle everything: stopping existing services, configuring
mosquitto and the VTN's `config.yaml`, clearing VTN storage, starting services,
and verifying connectivity.

```bash
# Anonymous MQTT mode
bin/test-stack-start-anon

# Dynsec (authenticated) MQTT mode
bin/test-stack-start-dynsec

# Include the test-callback-service for webhook tests
bin/test-stack-start-anon --with-callback

# Check status
bin/test-stack-status

# Stop everything
bin/test-stack-stop
```

The scripts look for the VTN-RI and callback service repos at default paths.
Override with environment variables:

```bash
export VTN_RI_DIR=/path/to/openadr3-vtn-reference-implementation
export CBS_DIR=/path/to/test-callback-service
```

**Note:** These scripts require macOS with Homebrew for mosquitto service
management. On Linux, the mosquitto management would need to use `systemctl`.

### Manual startup

If you prefer to manage services manually:

**Mosquitto (anonymous):**

```bash
brew services start mosquitto
```

**VTN-RI:**

```bash
cd /path/to/openadr3-vtn-reference-implementation
source venv/bin/activate
python3 -m swagger_server
```

**Callback service (optional):**

```bash
cd /path/to/test-callback-service
source venv/bin/activate
python3 run.py
```

### Verifying the VTN is up

```bash
# The BL token is base64(bl_client:1001)
curl -s -H "Authorization: Bearer YmxfY2xpZW50OjEwMDE=" \
  http://localhost:8080/openadr3/3.1.0/programs
```

Expected response: `[]` (empty list — no programs created yet).

## VTN-RI Configuration

The VTN-RI is configured via `config.yaml` in its repo root. The stack scripts
modify this file automatically. Key settings:

| Setting | Default | Notes |
|---------|---------|-------|
| `server.port` | 8080 | HTTP only |
| `storage.implementation` | IN_FILE | `./tmp/fileStorage.json` — deleted on each stack start |
| `notifications.bindings` | WEBHOOK, MQTT | Both enabled |
| `mqtt.broker.auth` | ANONYMOUS | Or OAUTH2_BEARER_TOKEN for dynsec |
| `mqtt.broker.host` | 127.0.0.1 | Localhost |
| `mqtt.broker.port` | 1883 | Standard MQTT port |

## Authentication

The VTN-RI uses a BasicAuthProvider with base64-encoded `client_id:secret` tokens:

| Base64 Token | Decoded | Role |
|-------------|---------|------|
| `dmVuX2NsaWVudDo5OTk=` | `ven_client:999` | VEN |
| `dmVuX2NsaWVudDI6OTk5OQ==` | `ven_client2:9999` | VEN |
| `YmxfY2xpZW50OjEwMDE=` | `bl_client:1001` | BL |

These are configured in `test-config.edn` (gitignored). Copy from the example:

```bash
cp test-config.example.edn test-config.edn
```

## Running the Tests

### Two test modes

The test suite should be run in **both** modes to verify full coverage:

```bash
# 1. Anonymous MQTT mode
bin/test-stack-start-anon
clojure -M:test

# 2. Dynsec MQTT mode
bin/test-stack-start-dynsec
clojure -M:test
```

The `:mqtt-auth` suite auto-detects the MQTT auth mode and skips gracefully
in anonymous mode.

### Command line (via Kaocha)

```bash
clojure -M:test                    # all suites
clojure -M:test --focus :mqtt      # single suite (prerequisites auto-included)
clojure -M:test --focus :mqtt-auth # dynsec auth tests
```

### From the REPL

```bash
clojure -M:nrepl
# nREPL running on port 7891
```

```clojure
(require '[kaocha.repl :as k])
(k/run-all)              ; run all suites
(k/run :programs)        ; run a single suite
(k/run :vens :topics)    ; run specific suites
```

### Expected output

192 tests across 13 suites. In anonymous mode:

```
192 tests, 593 assertions, 0 failures.
```

(Webhook subscription tests currently fail with 500 due to a VTN-RI bug.)

## Troubleshooting

**VTN not responding:** Check `bin/test-stack-status`. Attach to the tmux
session: `tmux attach-session -t vtn-ri`. Check the log: `/tmp/vtn-ri.log`.

**MQTT "Not authorized to connect":** Likely a stale mosquitto process from a
different mode. Run `bin/test-stack-stop` and restart with the correct mode.

**Port already in use:** `bin/test-stack-stop` kills all service processes and
waits for ports to be free. If it reports ports still in use, check with
`lsof -i :1883` or `lsof -i :8080`.

**Stale test data:** The stack start scripts delete `fileStorage.json`
automatically. If running manually, delete it yourself:
`rm -f /path/to/vtn-ri/tmp/fileStorage.json`.

**VTN-RI venv broken:** The VTN-RI's `bin/vtn-start.sh` auto-recreates the
venv if needed. Requires `python3.10` on PATH. Also needs `setuptools<81`
(installed automatically by the script).
