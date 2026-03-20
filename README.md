# clj-oa3-test

Integration test suite for OpenADR 3 VTN implementations, using the [clj-oa3-client](https://github.com/grid-coordination/clj-oa3-client) and [clj-oa3](https://github.com/grid-coordination/clj-oa3) Clojure libraries as the test harness.

## Architecture

```
┌─────────────────────────────────────────────────┐
│ clj-oa3-test                                    │
│                                                 │
│ common_test.clj                                 │
│   ven1 = VenClient (ven_client:999)             │
│   ven2 = VenClient (ven_client2:9999)           │
│   bl   = BlClient  (bl_client:1001)             │
│                                                 │
│ Test suites use client/ wrappers                │
├─────────────────────────────────────────────────┤
│ clj-oa3-client (Component lifecycle)            │
├─────────────────────────────────────────────────┤
│ clj-oa3 (Martian HTTP + entity coercion)        │
├─────────────────────────────────────────────────┤
│ OpenADR 3 VTN  (URL from test-config.edn)       │
│ MQTT broker    (discovered via /notifiers)      │
└─────────────────────────────────────────────────┘
```

## Prerequisites

1. **Sibling repos** — both must be checked out alongside this repo:
   - [clj-oa3](../clj-oa3) — pure client library
   - [clj-oa3-client](../clj-oa3-client) — Component lifecycle wrapper

2. **[OpenADR 3 specification](https://github.com/grid-coordination/openadr3-specification)** — the `openadr3-specification` directory (containing `{version}/openadr3.yaml`) must be symlinked into `clj-oa3/resources/`

3. **Running VTN** — the tests expect an OpenADR 3 VTN (URL configured in `test-config.edn`)

4. **MQTT broker** — the MQTT broker URL is discovered automatically from the VTN's `GET /notifiers` endpoint. A fallback can be configured in `test-config.edn` if needed.

Expected layout:

```
repo/
  clj-oa3/           # pure client library
  clj-oa3-client/    # Component wrapper
  clj-oa3-test/      # this repo
  specification/     # OpenADR 3 OpenAPI specs
```

### Starting the Test Stack

Scripts in `bin/` manage the full test infrastructure (VTN-RI, mosquitto, callback service). These currently require macOS with Homebrew for mosquitto service management.

```bash
bin/test-stack-start-anon          # anonymous MQTT mode
bin/test-stack-start-dynsec        # dynsec (authenticated) MQTT mode
bin/test-stack-stop                # stop everything
bin/test-stack-status              # show what's running
```

Both start scripts stop all existing services first, configure mosquitto and the VTN's `config.yaml` programmatically, clear VTN storage for a clean state, start services, and verify connectivity before returning.

Add `--with-callback` to include the test-callback-service (needed for webhook tests).

Override default paths via environment variables:
- `VTN_RI_DIR` — VTN Reference Implementation repo
- `CBS_DIR` — test-callback-service repo

For other VTNs, start them manually and configure `test-config.edn`.

### Configuration

Copy the example config and adjust for your VTN:

```bash
cp test-config.example.edn test-config.edn
```

The config file (`test-config.edn`) is gitignored. It controls:

```edn
{:vtn-url "http://localhost:8080/openadr3/3.1.0"
 :tokens {:ven1 "dmVuX2NsaWVudDo5OTk="       ;; base64(client_id:secret)
          :ven2 "dmVuX2NsaWVudDI6OTk5OQ=="
          :bl   "YmxfY2xpZW50OjEwMDE="
          :bad  "bad_token"}
 :inter-suite-delay-ms 1000}                   ;; pause between suites (ms)
```

The VTN-RI uses BasicAuthProvider with base64-encoded `client_id:secret` tokens. Other VTNs will require different credential formats.

MQTT broker URLs are discovered automatically from the VTN's `GET /notifiers` endpoint, which returns the `MQTT.URIS` array per the OpenADR 3 spec. If the VTN doesn't advertise MQTT, you can set a `:mqtt-brokers` fallback in the config.

## Running Tests

```bash
# Run all suites in order
clojure -M:test

# Run a single suite (prerequisites auto-included)
clojure -M:test --focus :mqtt

# Run multiple suites
clojure -M:test --focus :mqtt --focus :mqtt-auth
```

### Via nREPL

```bash
clojure -M:nrepl
# nREPL running on port 7891
```

```clojure
(require '[kaocha.repl :as k])
(k/run-all)
(k/run :programs)
```

## Suite Dependencies

Suites have ordering dependencies — later suites depend on entities created by earlier ones. These are declared in `tests.edn` via `:suite-deps/requires`:

```clojure
{:id :mqtt
 :suite-deps/requires [:programs :vens]
 ...}
```

The `kaocha.plugin/suite-deps` plugin automatically includes prerequisite suites when using `--focus`. Dependencies are transitive — if A requires B and B requires C, focusing A runs C → B → A.

When adding a new suite that depends on data created by another suite, add `:suite-deps/requires [:dep-suite-id]` to its entry in `tests.edn`.

## Test Suites

The suite runs **192 tests** across 13 suites:

| Suite | File | Tests | Requires | Description |
|-------|------|------:|----------|-------------|
| **Notifiers** | `notifiers_test.clj` | 1 | — | Verifies notifier discovery (WEBHOOK, MQTT support) |
| **Programs** | `programs_test.clj` | 21 | — | Program CRUD, auth, conflict, bad-token, bad-ID, pagination |
| **VENs** | `vens_test.clj` | 21 | — | VEN registration, CRUD, clientID conflict, bad-token, bad-ID, pagination |
| **Events** | `events_test.clj` | 21 | programs | Event CRUD, auth (BL-only create/update/delete), bad-token, bad-ID, pagination |
| **Resources** | `resources_test.clj` | 20 | vens | Resource CRUD (VEN + BL), conflict, bad-token, bad-ID, pagination |
| **Reports** | `reports_test.clj` | 19 | programs | Report CRUD (VEN-only create/update/delete), bad-token, bad-ID, pagination |
| **Subscriptions** | `subscriptions_test.clj` | 21 | programs | Subscription CRUD, bad-token, bad-ID, pagination, search by programID/clientName |
| **Topics** | `topics_test.clj` | 15 | vens | MQTT topic discovery for ven1/ven2/bl + 12 bad-token tests |
| **Channel** | `channel_test.clj` | 9 | programs, vens | MQTT/webhook channel lifecycle and VenClient integration |
| **VEN Client** | `ven_client_test.clj` | 13 | programs, vens | VEN registration, program resolution, notifier discovery, event polling |
| **MQTT** | `mqtt_test.clj` | 17 | programs, vens | MQTT notification reception for all entity types + targeted delivery |
| **MQTT Auth** | `mqtt_auth_test.clj` | 11 | programs, vens | Dynsec broker auth: credentials, ACLs, connection rejection, deletion cleanup |
| **Webhook** | `webhook_test.clj` | 3 | programs | Webhook notification delivery for event CREATE, program CREATE/DELETE |

### What Each CRUD Suite Covers

Every entity suite (programs, VENs, events, resources, reports, subscriptions) follows a consistent pattern:

- **Create** — happy path for authorized roles, 403 for unauthorized roles, conflict detection (409)
- **Search** — list all, get by ID, for both BL and VEN clients
- **Update** — happy path + forbidden role check
- **Delete** — happy path + forbidden role check
- **Bad token** — 5 tests per suite (create, search-all, search-by-id, update, delete) all expect 403
- **Bad ID** — 3 tests per suite (search, update, delete) expect 404 (or 400 for some VTNs)
- **Pagination** — skip/limit combinations including empty result sets

### Auth Model

OpenADR 3 has role-based access:

| Entity | Create | Update | Delete | Search |
|--------|--------|--------|--------|--------|
| Programs | BL only | BL only | BL only | BL + VEN |
| Events | BL only | BL only | BL only | BL + VEN |
| VENs | BL + VEN | BL + VEN | BL only | BL + VEN |
| Resources | BL + VEN | BL + VEN | BL + VEN | BL + VEN |
| Reports | VEN only | VEN only | VEN only | BL + VEN |
| Subscriptions | BL + VEN | BL + VEN | BL + VEN | BL + VEN |

### MQTT Notification Tests

The MQTT suite connects ven1 and bl to the MQTT broker (using credentials from `GET /notifiers` when the broker requires authentication), then tests notification delivery:

- **Programs** — CREATE, UPDATE, DELETE notifications received by VEN
- **Events** — CREATE, UPDATE, DELETE on program-scoped event topics
- **VENs** — UPDATE notification on VEN-scoped topics
- **Resources** — CREATE, UPDATE, DELETE on VEN-scoped resource topics
- **Reports** — CREATE, UPDATE, DELETE notifications received by BL (reports are VEN-created)
- **Subscriptions** — CREATE, DELETE notifications received by BL
- **Targeted delivery** — program and event notifications on VEN-scoped topics when the entity targets a specific VEN

The CREATE notification test also verifies full coercion (entity keywords, object-type, operation) and channel metadata.

### MQTT Authentication Tests

The `:mqtt-auth` suite tests MQTT broker authentication via Mosquitto's dynamic security plugin. Tests auto-detect dynsec mode from the `GET /notifiers` response and skip gracefully when the VTN runs in ANONYMOUS mode.

To run in dynsec mode:
```bash
bin/test-stack-start-dynsec
clojure -M:test --focus :mqtt-auth
```

### Webhook Notification Tests

The webhook suite creates a local HTTP server, registers webhook subscriptions via the VTN API, and verifies that the VTN delivers notifications to the callback URL for event and program CREATE/DELETE operations.

### Test Clients

All clients are constructed in `common_test.clj` using tokens from `test-config.edn`:

```clojure
(def ven1 (component/start (ven/ven-client {:url VTN-url :token (:ven1 tokens)})))
(def ven2 (component/start (ven/ven-client {:url VTN-url :token (:ven2 tokens)})))
(def bl   (component/start (bl/bl-client   {:url VTN-url :token (:bl tokens)})))
```

MQTT broker URLs are discovered at startup via `(base/get-notifiers bl)` and exposed as `MQTT-broker-urls` (all) and `MQTT-broker-url` (primary).

### VTN Compatibility

Tests accommodate VTN-specific behavior:
- Update with a nonexistent ID may return 400 or 404 (tests accept either)
- VEN registration uses `clientID` for conflict detection, not `venName`
- `inter-suite-delay-ms` in `test-config.edn` adds a configurable pause between suites (set to 0 for fast VTNs, 1000-5000 if you see connection errors)

### Test Configuration

Tests are configured in `tests.edn`. Suite order is fixed (`randomize? false`) because later suites depend on entities created by earlier ones. The `suite-deps` plugin handles prerequisite resolution automatically.

## Known Gaps

- **MQTT notifications** — missing VEN DELETE, per-program-scoped UPDATE/DELETE, subscription UPDATE, and the ALL wildcard topic test
- **Notifiers** — only tests that WEBHOOK and MQTT are advertised; no bad-token test

## Dependency Chain

```
clj-oa3-test
  └── clj-oa3-client  (Component lifecycle, API delegation, MQTT)
        └── clj-oa3   (Martian HTTP, entity coercion, Malli schemas)
```

Dependencies are available from [Clojars](https://clojars.org/energy.grid-coordination/clj-oa3-client).

## Related Repos

| Repo | Description |
|------|-------------|
| [clj-oa3](https://github.com/grid-coordination/clj-oa3) | Pure client library |
| [clj-oa3-client](https://github.com/grid-coordination/clj-oa3-client) | Component lifecycle wrapper |

## License

[MIT License](LICENSE) — Copyright (c) 2026 Clark Communications Corporation
