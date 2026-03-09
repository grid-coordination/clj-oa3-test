# clj-oa3-test

Integration test suite for the OpenADR 3 Clojure libraries, running against any OpenADR 3 compliant VTN.

Tests exercise the full stack: client construction via [clj-oa3-client](https://github.com/grid-coordination/clj-oa3-client) Component lifecycle, HTTP requests via [clj-oa3](https://github.com/grid-coordination/clj-oa3) Martian client, and responses from a live VTN server.

## Architecture

```
┌──────────────────────────────────────────────┐
│  clj-oa3-test                                │
│                                              │
│  common_test.clj                             │
│    ven1 = OA3Client :ven (ven_token)         │
│    ven2 = OA3Client :ven (ven_token2)        │
│    bl   = OA3Client :bl  (bl_token)          │
│                                              │
│  Test suites use client/ wrappers            │
├──────────────────────────────────────────────┤
│  clj-oa3-client (Component lifecycle)        │
├──────────────────────────────────────────────┤
│  clj-oa3 (Martian HTTP + entity coercion)    │
├──────────────────────────────────────────────┤
│  OpenADR 3 VTN                               │
│  http://localhost:8080/openadr3/3.1.0        │
└──────────────────────────────────────────────┘
```

## Prerequisites

1. **Sibling repos** — both must be checked out alongside this repo:
   - [clj-oa3](../clj-oa3) — pure client library
   - [clj-oa3-client](../clj-oa3-client) — Component lifecycle wrapper

2. **OpenADR 3 specification** — symlinked into clj-oa3's resources (see clj-oa3 README)

3. **Running VTN** — the tests expect an OpenADR 3 VTN at `http://localhost:8080/openadr3/3.1.0`

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

| Suite | File | Description |
|-------|------|-------------|
| **Notifiers** | `notifiers_test.clj` | Verifies notifier discovery (WEBHOOK, MQTT support) |
| **Programs** | `programs_test.clj` | Program CRUD lifecycle — creates Program1 and Program2 via BL client |
| **VENs** | `vens_test.clj` | VEN CRUD lifecycle — creates ven1 and ven2 via VEN clients |
| **Topics** | `topics_test.clj` | MQTT topic discovery across all three clients (ven1, ven2, bl) with scope-aware authorization checks |

### Test Orchestration

Each test namespace defines a `test-ns-hook` function that controls execution order:

1. **Cleanup** — delete any leftover test data (programs, VENs) from previous runs
2. **Create prerequisites** — programs and VENs needed for subsequent tests
3. **Run tests** — exercise the API and assert responses

The `topics_test` suite is the most comprehensive — it runs 12 MQTT topic endpoint tests for each of the three clients (ven1, ven2, bl), checking both successful responses and expected 403 Forbidden responses based on OAuth2 scopes.

### Test Clients

All three clients are constructed in `common_test.clj` using the Component lifecycle:

```clojure
(def ven1 (component/start (client/oa3-client {:type :ven :url VTN-url :token "ven_token"})))
(def ven2 (component/start (client/oa3-client {:type :ven :url VTN-url :token "ven_token2"})))
(def bl   (component/start (client/oa3-client {:type :bl  :url VTN-url :token "bl_token"})))
```

Tests use `with-redefs` to bind dynamic vars to specific clients, allowing the same test logic to run against different client types and credentials.

## Running Tests

### Via Kaocha (command line)

```bash
clojure -M:test
```

### Via nREPL

```bash
clojure -M:nrepl
# nREPL running on port 7891
```

```clojure
;; In the REPL
(require '[kaocha.repl :as k])
(k/run :integration)

;; Run a single suite
(k/run {:tests [{:id :integration
                 :ns-patterns ["programs-test$"]}]})
```

### Test Configuration

Tests are configured in `tests.edn`:

```clojure
#kaocha/v1
{:tests [{:id :integration
          :type :kaocha.type/clojure.test
          :source-paths []
          :test-paths ["test"]
          :ns-patterns [".*-test$"]}]
 :color? true
 :fail-fast? false}
```

## Dependency Chain

```
clj-oa3-test
  └── clj-oa3-client  (Component lifecycle, API delegation)
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
