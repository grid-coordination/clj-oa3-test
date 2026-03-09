# clj-oa3-test

Integration test suite for the OpenADR 3 Clojure libraries, running against any OpenADR 3 compliant VTN.

Tests exercise the full stack: client construction via [clj-oa3-client](https://github.com/grid-coordination/clj-oa3-client) Component lifecycle, HTTP requests via [clj-oa3](https://github.com/grid-coordination/clj-oa3) Martian client, and responses from a live VTN server.

## Architecture

```
┌──────────────────────────────────────────────┐
│  clj-oa3-test                                │
│                                              │
│  common_test.clj                             │
│    ven1      = OA3Client :ven (ven_token)     │
│    ven2      = OA3Client :ven (ven_token2)    │
│    bl        = OA3Client :bl  (bl_token)      │
│    bad-token = OA3Client :bl  (bad_token)     │
│                                              │
│  Test suites use client/ wrappers            │
├──────────────────────────────────────────────┤
│  clj-oa3-client (Component lifecycle)        │
├──────────────────────────────────────────────┤
│  clj-oa3 (Martian HTTP + entity coercion)    │
├──────────────────────────────────────────────┤
│  OpenADR 3 VTN                               │
│  http://localhost:8080/openadr3/3.1.0        │
│  MQTT broker: tcp://127.0.0.1:1883          │
└──────────────────────────────────────────────┘
```

## Prerequisites

1. **Sibling repos** — both must be checked out alongside this repo:
   - [clj-oa3](../clj-oa3) — pure client library
   - [clj-oa3-client](../clj-oa3-client) — Component lifecycle wrapper

2. **OpenADR 3 specification** — symlinked into clj-oa3's resources (see clj-oa3 README)

3. **Running VTN** — the tests expect an OpenADR 3 VTN at `http://localhost:8080/openadr3/3.1.0`

4. **MQTT broker** — the MQTT and topics tests expect a broker at `tcp://127.0.0.1:1883`

Expected layout:

```
repo/
  clj-oa3/           # pure client library
  clj-oa3-client/    # Component wrapper
  clj-oa3-test/      # this repo
  specification/     # OpenADR 3 OpenAPI specs
```

### Starting a VTN

Any OpenADR 3 compliant VTN will work. For development, the [VTN Reference Implementation](https://github.com/OpenADRAlliance/oadr-ri-vtn) is convenient:

```bash
# See https://github.com/OpenADRAlliance/oadr-ri-vtn for full setup
docker compose up -d
```

The VTN-RI uses a toy auth implementation with pre-configured tokens: `ven_token`, `ven_token2`, `bl_token`. Other VTNs will require different credentials.

## Test Suites

The suite runs **156 tests** across 9 ordered suites. Suites run in a fixed order because later suites depend on entities created by earlier ones (e.g., events depend on programs, reports depend on events).

| Suite | File | Tests | Description |
|-------|------|------:|-------------|
| **Notifiers** | `notifiers_test.clj` | 1 | Verifies notifier discovery (WEBHOOK, MQTT support) |
| **Programs** | `programs_test.clj` | 21 | Program CRUD, auth, conflict, bad-token, bad-ID, pagination |
| **VENs** | `vens_test.clj` | 21 | VEN registration, CRUD, clientID conflict, bad-token, bad-ID, pagination |
| **Events** | `events_test.clj` | 21 | Event CRUD, auth (BL-only create/update/delete), bad-token, bad-ID, pagination |
| **Resources** | `resources_test.clj` | 20 | Resource CRUD (VEN + BL), conflict, bad-token, bad-ID, pagination |
| **Reports** | `reports_test.clj` | 19 | Report CRUD (VEN-only create/update/delete), bad-token, bad-ID, pagination |
| **Subscriptions** | `subscriptions_test.clj` | 21 | Subscription CRUD (BL + VEN), bad-token, bad-ID, pagination, search by programID/clientName |
| **Topics** | `topics_test.clj` | 15 | MQTT topic discovery for ven1/ven2/bl + 12 bad-token tests |
| **MQTT** | `mqtt_test.clj` | 17 | MQTT notification reception for all entity types + targeted delivery |

### What Each CRUD Suite Covers

Every entity suite (programs, VENs, events, resources, reports, subscriptions) follows a consistent pattern:

- **Create** — happy path for authorized roles, 403 for unauthorized roles, conflict detection (409)
- **Search** — list all, get by ID, for both BL and VEN clients
- **Update** — happy path + forbidden role check
- **Delete** — happy path + forbidden role check
- **Bad token** — 5 tests per suite (create, search-all, search-by-id, update, delete) all expect 403
- **Bad ID** — 3 tests per suite (search, update, delete) expect 404 (or 400 for some VTNs)
- **Pagination** — skip/limit combinations including empty result sets

### MQTT Topic Tests

The topics suite tests all 12 MQTT topic endpoints for three clients (ven1, ven2, bl). Each client's test verifies:
- Topic strings are returned for each operation (ALL, CREATE, UPDATE, DELETE)
- Scope-aware authorization — endpoints like `/events/topics` and `/vens/topics` return 403 for VEN clients
- Per-entity topics (program, VEN) omit CREATE since entities already exist

Bad-token tests verify that all 12 topic endpoint categories reject invalid credentials with 403.

### MQTT Notification Tests

The MQTT suite connects ven1 and bl to the MQTT broker, then tests notification delivery:

- **Programs** — CREATE, UPDATE, DELETE notifications received by VEN
- **Events** — CREATE, UPDATE, DELETE on program-scoped event topics
- **VENs** — UPDATE notification on VEN-scoped topics
- **Resources** — CREATE, UPDATE, DELETE on VEN-scoped resource topics
- **Reports** — CREATE, UPDATE, DELETE notifications received by BL (reports are VEN-created)
- **Subscriptions** — CREATE, DELETE notifications received by BL
- **Targeted delivery** — program and event notifications on VEN-scoped topics when the entity targets a specific VEN

The CREATE notification test also verifies full coercion (entity keywords, object-type, operation) and channel metadata.

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

### Test Clients

All clients are constructed in `common_test.clj` using the Component lifecycle:

```clojure
(def ven1      (component/start (client/oa3-client {:type :ven :url VTN-url :token "ven_token"})))
(def ven2      (component/start (client/oa3-client {:type :ven :url VTN-url :token "ven_token2"})))
(def bl        (component/start (client/oa3-client {:type :bl  :url VTN-url :token "bl_token"})))
(def bad-token (component/start (client/oa3-client {:type :bl  :url VTN-url :token "bad_token"})))
```

### VTN Compatibility

Some VTNs (notably the VTN-RI) drop HTTP/1.1 connections under sustained load. The `inter-suite-delay-ms` setting in `common_test.clj` adds a configurable pause between suites (default 5000ms). Set to 0 for VTNs that handle connection reuse well.

Tests also accommodate VTN-specific behavior:
- Update with a nonexistent ID may return 400 or 404 (tests accept either)
- VEN registration uses `clientID` for conflict detection, not `venName`

## Known Gaps

Coverage is broadly comparable to other OpenADR 3 conformance test suites, with these remaining gaps:

- **Webhook notifications** — subscription callback delivery is not tested; we test subscription CRUD via REST and MQTT notifications, but cannot receive webhook callbacks
- **MQTT notifications** — missing VEN DELETE, per-program-scoped UPDATE/DELETE (subscribing to a single program's topic), subscription UPDATE, and the ALL wildcard topic test
- **Notifiers** — only tests that WEBHOOK and MQTT are advertised; no bad-token test

## Running Tests

### Via Kaocha (command line)

```bash
# Run all suites in order
clojure -M:test

# Run a single suite
clojure -M:test --focus :programs
clojure -M:test --focus :mqtt
```

### Via nREPL

```bash
clojure -M:nrepl
# nREPL running on port 7891
```

```clojure
;; In the REPL
(require '[kaocha.repl :as k])

;; Run all suites
(k/run-all)

;; Run a single suite
(k/run :programs)
(k/run :mqtt)
```

### Running Against VTN-RI

The [VTN Reference Implementation](https://github.com/OpenADRAlliance/oadr-ri-vtn) drops HTTP/1.1 connections under sustained load (~200+ requests in a single process). This manifests as connection-refused or broken-pipe errors in the later suites (typically topics and MQTT).

Workarounds:

1. **Inter-suite delay** — `inter-suite-delay-ms` in `common_test.clj` (default 5000ms) pauses between suites to let the VTN recover. Increase if you still see connection errors.

2. **Run in batches** — if the full suite still has connection issues, split into two runs:
   ```bash
   # REST suites
   clojure -M:test --focus :notifiers --focus :programs --focus :vens --focus :events --focus :resources --focus :reports

   # MQTT suites (after a pause)
   clojure -M:test --focus :topics --focus :mqtt
   ```

3. **For well-behaved VTNs** — set `inter-suite-delay-ms` to 0 to skip the pauses entirely.

### Test Configuration

Tests are configured in `tests.edn`. Suite order is fixed (`randomize? false`) because later suites depend on entities created by earlier ones:

```
notifiers → programs → vens → events → resources → reports → subscriptions → topics → mqtt
```

## Dependency Chain

```
clj-oa3-test
  └── clj-oa3-client  (Component lifecycle, API delegation, MQTT)
        └── clj-oa3   (Martian HTTP, entity coercion, Malli schemas)
```

All dependencies are `:local/root` references to sibling directories.

## Related Repos

| Repo | Description |
|------|-------------|
| [clj-oa3](https://github.com/grid-coordination/clj-oa3) | Pure client library |
| [clj-oa3-client](https://github.com/grid-coordination/clj-oa3-client) | Component lifecycle wrapper |

## License

Copyright (c) 2026. All rights reserved.
