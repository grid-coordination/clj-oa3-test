# clj-oa3-test

Integration test suite for [clj-oa3](https://github.com/grid-coordination/clj-oa3), running against the OpenADR 3 VTN Reference Implementation.

## Prerequisites

- [clj-oa3](../clj-oa3) must be checked out as a sibling directory
- The [OpenADR 3 specification](../../specification) must be available (symlinked into `resources/`)
- A running VTN-RI instance at `http://localhost:8080/openadr3/3.1.0`

## Test Suites

| Suite | Description |
|-------|-------------|
| `notifiers-test` | Verifies notifier discovery (WEBHOOK, MQTT) |
| `programs-test` | Program CRUD lifecycle |
| `vens-test` | VEN CRUD lifecycle |
| `topics-test` | MQTT topic discovery across VEN/BL clients with scope-aware authorization checks |

Tests are orchestrated via `test-ns-hook` functions that control execution order (cleanup, create prerequisites, then test).

## Running Tests

### Via Kaocha

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
```

## Related Repos

| Repo | Description |
|------|-------------|
| [clj-oa3](https://github.com/grid-coordination/clj-oa3) | Pure client library (dependency) |
| [clj-oa3-client](https://github.com/grid-coordination/clj-oa3-client) | Application layer with mDNS, service management |

## License

Copyright (c) 2026. All rights reserved.
