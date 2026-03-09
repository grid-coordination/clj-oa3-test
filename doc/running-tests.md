# Running the Integration Tests

This document explains how to set up and run the full integration test stack.

## Prerequisites

### Required software

- **Java** (11+) and **Clojure CLI** — [install guide](https://clojure.org/guides/install_clojure)
- **Python 3** (3.9+ recommended) — for the VTN-RI
- **tmux** — the dev helpers manage long-running processes in tmux sessions
- **Mosquitto MQTT broker** — `brew install mosquitto` on macOS

### Required sibling repositories

All repos must be cloned as siblings in the same parent directory:

```
parent/
  clj-oa3/                                       # pure client library
  clj-oa3-client/                                # Component wrapper
  clj-oa3-test/                                  # this repo
  specification/                                 # OpenADR 3 OpenAPI specs
  openadr3-vtn-reference-implementation/         # VTN-RI (Python)
  test-callback-service/                         # webhook callback receiver (Python)
```

The VTN-RI and test-callback-service each need a Python virtual environment. If not already set up:

```bash
cd ../openadr3-vtn-reference-implementation
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
deactivate

cd ../test-callback-service
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
deactivate
```

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
| Mosquitto | 1883 | Yes (for MQTT topic tests) | MQTT broker, anonymous auth |
| VTN-RI | 8080 | Yes | OpenADR 3 VTN server |
| Test Callback Service | 5000 | Optional | Receives webhook notifications for subscription tests |

## Starting the Stack

### Option A: From the REPL (recommended)

Start an nREPL and use the dev helpers in `dev/user.clj`:

```bash
cd clj-oa3-test
clojure -M:dev:nrepl
```

```clojure
;; Start everything at once
(start-stack!)

;; Or individually
(restart-mqtt)       ; restart Mosquitto via brew services
(run-vtn-ri)         ; start VTN-RI in tmux session "vtn-ri"
(run-callback-svc)   ; start callback service in tmux session "vtn-callbk-svc"

;; Attach to see output
;; tmux attach-session -t vtn-ri
;; tmux attach-session -t vtn-callbk-svc

;; Stop
(stop-stack!)
;; or
(kill-vtn-ri)
(kill-callback-svc)
```

### Option B: Manual (separate terminals)

**Terminal 1 — Mosquitto:**

```bash
brew services start mosquitto
```

**Terminal 2 — VTN-RI:**

```bash
cd ../openadr3-vtn-reference-implementation
git checkout dcj/issue-164
source venv/bin/activate
python -m swagger_server
```

**Terminal 3 — Callback service (optional):**

```bash
cd ../test-callback-service
git checkout main
source venv/bin/activate
python run.py
```

### Option C: Docker Compose (VTN + Mosquitto only)

```bash
cd ../openadr3-vtn-reference-implementation
docker compose up
```

This starts both the VTN-RI and Mosquitto using the `compose.yaml` in that repo. Note: the pre-built Docker image SHA may be stale; rebuild with `docker build -t swagger_server .` if needed.

### Verifying the VTN is up

```bash
curl -s -H "Authorization: Bearer bl_token" http://localhost:8080/openadr3/3.1.0/programs
```

Expected response: `[]` (empty list — no programs created yet).

## VTN-RI Configuration

The VTN-RI is configured via `config.py` in its repo root. Key settings:

| Setting | Value | Notes |
|---------|-------|-------|
| `SERVER_PORT` | 8080 | HTTP only, no HTTPS |
| `NOTIFIER_BINDINGS` | `['MQTT', 'WEBHOOK']` | Use `config-mqtt.py` for this; `config-no-mqtt.py` disables MQTT |
| `MQTT_CLIENT_BROKER_FQDN` | `127.0.0.1` | Assumes Mosquitto on localhost |
| `MQTT_VTN_BROKER_PORT` | 1883 | Standard MQTT port |
| `STORAGE_IMPLEMENTATION` | `IN_MEMORY` | All data lost on restart (good for testing) |

The default `config.py` has MQTT enabled. If you need to toggle, copy one of the variant configs:

```bash
cp config-mqtt.py config.py      # MQTT + WEBHOOK
cp config-no-mqtt.py config.py   # WEBHOOK only
```

## Authentication

The VTN-RI uses a mock auth provider with pre-configured tokens — no OAuth2 token exchange needed:

| Token | Client ID | Role | Scopes |
|-------|-----------|------|--------|
| `ven_token` | `ven_client` | VEN | `read_all`, `read_targets`, `read_ven_objects`, `write_reports`, `write_subscriptions`, `write_vens` |
| `ven_token2` | `ven_client2` | VEN | (same as above) |
| `bl_token` | `bl_client` | BL | `read_all`, `read_bl`, `write_programs`, `write_events`, `write_subscriptions`, `write_vens` |
| `admin_token` | `admin_client` | VEN+BL | All scopes |

These tokens are hardcoded in the VTN-RI's `swagger_server/services/auth/mock_auth_provider.py`.

## Running the Tests

### Command line (via Kaocha)

```bash
cd clj-oa3-test
clojure -M:test
```

### From the REPL

```bash
clojure -M:dev:nrepl
# or connect to an already-running nREPL on port 7891
```

```clojure
(require '[kaocha.repl :as k])
(k/run :integration)

;; Run a single suite
(k/run {:tests [{:id :integration
                 :ns-patterns ["programs-test$"]}]})
```

### Expected output

```
Testing openadr3.notifiers-test
Testing openadr3.programs-test
Testing openadr3.vens-test
Testing openadr3.topics-test

54 tests, 148 assertions, 0 failures.
```

## Test Execution Order

The tests have dependencies and run in a specific order controlled by `test-ns-hook` in each namespace:

1. **notifiers-test** — Verifies the VTN advertises WEBHOOK (and optionally MQTT) notifier support
2. **programs-test** — Deletes any leftover test programs, then creates Program1 and Program2 via the BL client
3. **vens-test** — Deletes any leftover test VENs, then creates ven1 and ven2 via VEN clients. Stores VEN IDs in var metadata for use by topics tests
4. **topics-test** — Runs 12 MQTT topic endpoint tests for each of ven1, ven2, and bl (36 test runs). Checks both successful responses and expected 403 Forbidden based on OAuth2 scopes

## Troubleshooting

**VTN not responding:** Check that `python -m swagger_server` is running. Attach to the tmux session: `tmux attach-session -t vtn-ri`

**MQTT topic tests failing with connection errors:** Mosquitto must be running on port 1883. Check with `brew services list` or `mosquitto -v` in a terminal.

**Stale data from previous test runs:** The VTN-RI uses in-memory storage. Restart it to clear all data. Or use the test cleanup: each test suite's `test-ns-hook` deletes its test objects before creating new ones.

**Clearing MQTT topics:** Restart Mosquitto to flush all retained messages: `brew services restart mosquitto` or `(restart-mqtt)` from the REPL.

**VTN-RI branch:** The tests were developed against the `dcj/issue-164` branch. If you're on a different branch, behavior may vary.
