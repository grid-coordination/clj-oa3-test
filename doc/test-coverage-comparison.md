# Test Coverage Comparison

Comparison of clj-oa3-test against an existing Python-based OpenADR 3 conformance test suite.

## Summary

| Category | Python | Clojure | Coverage |
|----------|-------:|--------:|----------|
| **Programs** | 40 | 21 | ~53% |
| **Events** | 43 | 21 | ~49% |
| **VENs** | 42 | 21 | ~50% |
| **Resources** | 35 | 20 | ~57% |
| **Reports** | 35 | 19 | ~54% |
| **Subscriptions** | 37 | 21 | ~57% |
| **MQTT topics** | 30 | 15 | 50% |
| **MQTT notifications** | 27 | 17 | ~63% |
| **Notifiers** | 3 | 1 | 33% |
| **Total** | **292** | **156** | **~53%** |

Both suites cover all entity types and operation categories. The gap is mainly in negative/edge-case testing rather than missing functional areas.

## What We Cover

Every entity suite (programs, VENs, events, resources, reports, subscriptions) tests:

- CRUD happy paths for authorized roles
- Role-based access control (403 for unauthorized roles)
- Conflict detection (409 for duplicates)
- Bad-token rejection (5 tests per entity: create, search-all, search-by-id, update, delete)
- Bad-ID handling (3 tests per entity: search, update, delete)
- Pagination (skip/limit combinations)

MQTT tests cover:

- Topic discovery for all 12 endpoints across BL, VEN1, VEN2 clients
- Bad-token rejection for all 12 topic endpoint categories
- Notification reception for program, event, VEN, resource, report, and subscription CRUD
- Targeted delivery (VEN-scoped program and event notifications)
- Notification coercion verification (entity keywords, object-type, operation, channel metadata)

## What Accounts for the Gap

### HTTP method tests (~20 tests)

The Python suite tests unsupported HTTP methods like `POST /programs/{id}` (expect 405), `DELETE /programs` (405), `PUT /programs` (405). We don't test method-not-allowed responses. This is ~3-4 tests per entity across 6 entities.

### Bad body / validation tests (~8 tests)

Python tests malformed request bodies and expects 400 Bad Request. We don't test request body validation. This is ~1-2 per entity.

### Per-role test granularity (~30-40 tests)

Python often creates separate `def test_` methods for the same operation with BL, VEN, and bad-token. We sometimes combine role checks or test fewer permutations per operation, which accounts for a significant portion of the count difference without a functional coverage gap.

### Search filter tests (~15-20 tests)

Python has more filter/query parameter tests: `targetType` and `targetValues` for events, `objectType` for subscriptions, `clientID` matching for VENs, and negative filter tests (expect 0 results). We test pagination and a few search filters (programID, clientName for subscriptions) but not the full matrix.

### MQTT topics — structural difference

Python tests each topic endpoint with BL, VEN, and bad-token as separate `def test_` methods (30 total). We use a parameterized `run-topics-for-client` helper for BL/VEN1/VEN2 (3 deftests covering 36 endpoint calls each) plus 12 individual bad-token tests. The functional coverage is equivalent — the deftest count difference is structural.

### MQTT notification gaps (~10 tests)

Missing notification tests:
- VEN DELETE notification
- Per-program-scoped UPDATE/DELETE (subscribing to a single program's topic vs. the global programs topic)
- Subscription UPDATE notification
- ALL wildcard topic test (receive notifications on the ALL topic)
- Targeted no-notification test (verify a non-targeted VEN does NOT receive a notification)

### Notifiers (~2 tests)

Python tests the notifiers endpoint with BL, VEN, and bad-token (3 tests). We only test with VEN (1 test). Missing BL and bad-token notifier tests.

### Webhook subscription callbacks (2 tests, intentionally skipped)

Python has 2 tests that create subscriptions and verify webhook callback delivery. We intentionally skip these because webhook notification reception is not implemented.
