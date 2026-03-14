# VTN Reference Implementation: Issues Observed During Integration Testing

This document catalogs issues we encountered while building and running a comprehensive integration test suite against the [OpenADR 3 VTN Reference Implementation](https://github.com/OpenADR/openadr3-vtn-reference-implementation). The test suite covers all OpenADR 3.1.0 endpoints (programs, VENs, events, resources, reports, subscriptions, topics, MQTT notifications) with a total of ~156 tests and ~485 assertions.

We are filing this in the spirit of constructive feedback. The VTN-RI has been invaluable for developing and validating our client library against a working server. These observations are offered to help improve the reference implementation for all users.

**Test environment:**
- VTN-RI branch: `dcj/issue-164` (issues verified to also exist on `main`)
- Mosquitto MQTT broker 2.x (Homebrew, macOS)
- Client: Clojure + Martian (spec-driven HTTP) + Paho MQTT
- Test runner: Kaocha (10 ordered test suites)

## 1. HTTP Connection Exhaustion Under Sustained Load

**Severity:** High — prevents running the full test suite in a single process

When running all 9 test suites sequentially in a single JVM process (~200+ HTTP requests), the VTN-RI stops responding to connections. Later test suites fail with HTTP/1.1 header parser errors, connection resets, or empty responses.

**Observed behavior:**
- Suites 1-6 (notifiers, programs, VENs, events, resources, reports) run cleanly
- Suites 7-9 (subscriptions, topics, MQTT) begin failing with connection errors
- The failures are not test logic errors — the same suites pass when run individually or in a separate process

**Workaround:**
- Introduced a configurable `inter-suite-delay-ms` (default: 5000ms) between test suites to allow the VTN to recover
- Split the full suite into two batches when delays alone are insufficient
- Both halves pass cleanly when run separately (84 + 67 tests, 485 total assertions, 0 failures)

**Hypothesis:** The VTN-RI's HTTP server (Connexion/Flask) may not be releasing connections promptly, leading to resource exhaustion under sustained request volume. A connection pool limit or keep-alive timeout may need adjustment.

## 2. MQTT UPDATE Notifications Are Unreliable

**Severity:** Medium — affects notification-dependent workflows

When subscribing to MQTT topics and performing CRUD operations, CREATE and DELETE notifications arrive consistently within a 5-second window. However, UPDATE notifications frequently fail to arrive within the same window.

**Observed behavior:**
- 12 of 17 MQTT notification tests pass consistently (all CREATE and DELETE operations)
- 5 tests that assert on UPDATE notifications are flaky:
  - Program update notification
  - Event update notification
  - VEN update notification
  - Resource update notification
  - Report update notification

**Conditions:**
- The MQTT subscription is active and confirmed
- The message buffer is cleared before the update operation
- A 200ms settle time is given after clearing
- The `await` timeout is 5000ms (same as for CREATE/DELETE, which succeed)

**Notes:** Increasing the timeout to 10-15 seconds does not reliably resolve the issue. The problem appears to be that the VTN-RI sometimes does not publish an MQTT notification for UPDATE operations, rather than a simple timing issue.

## 3. Non-Standard DateTime Format in MQTT Notification Payloads

**Severity:** Medium — requires client-side parsing workaround

MQTT notification payloads contain `createdDateTime` and `modificationDateTime` fields in the format `"2026-03-08 19:22:06"` (space separator, no timezone indicator). The OpenADR 3 specification and the VTN-RI's own REST API responses use RFC 3339 format: `"2026-03-08T19:22:06Z"`.

**Impact:** Clients that parse datetime strings using standard RFC 3339 / ISO 8601 parsers will fail when processing MQTT notification payloads. Our client library had to add a normalization step:

```
Input:      "2026-03-08 19:22:06"
Normalized: "2026-03-08T19:22:06Z"
```

The missing timezone indicator also introduces ambiguity — the client must assume UTC.

**Suggestion:** Use the same RFC 3339 datetime format (`2026-03-08T19:22:06Z`) in MQTT payloads as in REST API responses.

## 4. Operations Enum: "POST" vs "CREATE"

**Severity:** Low — requires awareness when writing subscription/notification code

The `objectOperations` field in subscription requests accepts operation values. The OpenADR 3 specification defines these as `CREATE`, `UPDATE`, `DELETE`. However, there was some ambiguity during development about whether the VTN-RI expected `POST` (the HTTP method) or `CREATE` (the logical operation).

**Current behavior:** The VTN-RI accepts `CREATE` (the spec-correct value). Earlier versions or configurations may have used or expected `POST`. This was resolved by updating our test fixtures to use `CREATE`.

**Suggestion:** If the VTN-RI ever accepted `POST` as an alias, consider logging a deprecation warning to help other client implementors discover the correct value.

## 5. Nil DateTime Fields in MQTT Notification Objects

**Severity:** Low — edge case

MQTT notification payloads sometimes contain `null` values for `createdDateTime` or `modificationDateTime` fields. When the REST API returns the same objects, these fields are always populated.

**Impact:** Clients must handle `null` datetimes defensively when processing MQTT notifications, even if the REST API guarantees non-null values for the same objects.

## 6. Retained MQTT Messages Cause Test Interference

**Severity:** Low — MQTT broker behavior, not strictly a VTN-RI issue

When running tests repeatedly, the Mosquitto broker retains published messages. Notifications from previous test runs (especially DELETE notifications) can be delivered to new subscribers, causing tests to match stale messages instead of fresh ones.

**Workaround:** Clear the client's message buffer before each test action and apply a short settle delay. Restarting Mosquitto (`brew services restart mosquitto`) flushes all retained messages.

**Note:** Documentation could note that retained messages may cause confusion during repeated test runs.

## 7. VEN Name Uniqueness Is Not Enforced

**Severity:** Informational — matches the spec

Creating a VEN with a `venName` that already exists returns `201 Created` rather than `409 Conflict`. This is technically correct per the OpenADR 3 specification (the `id` field is the unique identifier, and `venName` is a free-form label), but it may surprise implementors who expect `venName` to be unique.

The VTN-RI correctly enforces uniqueness on `clientID` — attempting to register a second VEN with the same `clientID` returns `409 Conflict`.

## 8. Targeted Webhook Notifications Not Delivered

**Severity:** Medium — breaks subscription target filtering for webhooks

When a subscription has `targets` and an event is created with matching `targets`, the VTN-RI does not deliver the webhook notification. Non-targeted subscriptions (no `targets` field) correctly receive webhook notifications for all events, including targeted ones.

**Observed behavior:**

| Subscription targets | Event targets | Webhook delivered? |
|---|---|---|
| none | none | Yes |
| none | `["group1"]` | Yes |
| `["group1"]` | none | Yes |
| `["group1"]` | `["group1"]` | **No** |
| `["0"]` (VEN ID) | `["0"]` | **No** |

**Root cause:** The target matching logic in `subscription_callback()` (`subscriptions_controller.py`, lines 266-278) requires the subscription's targets to intersect with the VEN's "allowed targets" (from `getAllowedTargets()`). `getAllowedTargets()` returns the VEN object's `targets` field plus its resource targets. If the VEN was registered without explicit targets (which is the common case for `register!`), the VEN's `targets` is `None`, so the allowed targets list is empty, and the match always fails.

```python
# subscriptions_controller.py line 269-271
allowed_targets = objectUtils.getAllowedTargets(subscription.client_id)
targets = [t for t in allowed_targets if t in subscription.targets]
# If allowed_targets is None or [], targets is always [], match is always False
```

**Workaround:** The Python test suite works around this by creating a VEN with explicit targets (`create_ven_bl_with_targets(targets=['group1'])`) before creating the targeted subscription. This populates `ven.targets` so that `getAllowedTargets()` returns a non-empty list.

**Note:** This same root cause likely explains the MQTT targeted notification flakiness documented in Issue #2 — the `notifiers.dispatch()` function (called on line 251) may use similar target matching logic.

**Affected branches:** Confirmed on both `main` and `dcj/issue-164` — the code is identical.

## 9. Webhook Payloads Are Double-Encoded JSON

**Severity:** Medium — requires client-side workaround

The VTN-RI sends webhook notification payloads as double-encoded JSON strings. The HTTP body is a valid JSON string, but its content is another JSON-encoded string (with escaped quotes), rather than a JSON object.

**Observed payload (HTTP body):**
```
"{\"object_type\": \"EVENT\", \"operation\": \"CREATE\", ...}"
```

**Expected payload (HTTP body):**
```
{"object_type": "EVENT", "operation": "CREATE", ...}
```

**Root cause:** In `subscriptions_controller.py` line 287:
```python
response = requests.post(objOperation.callback_url, json=json.dumps(notification.to_dict()), headers=headers)
```

The `json=` parameter of `requests.post` already JSON-encodes the value. But the value passed is `json.dumps(notification.to_dict())` — which is already a JSON string. So the notification gets encoded twice: once by `json.dumps()` and again by `requests.post(json=...)`.

**Fix:** Either use `json=notification.to_dict()` (let `requests` handle encoding) or use `data=json.dumps(notification.to_dict())` with `Content-Type: application/json`.

**Workaround:** Our client's webhook parser detects when the first JSON parse yields a string and parses again to get the actual notification map.

**Affected branches:** Confirmed on both `main` and `dcj/issue-164` — the code is identical.

## Summary

| # | Issue | Severity | Category |
|---|---|---|---|
| 1 | HTTP connection exhaustion after ~200 requests | High | Infrastructure |
| 2 | UPDATE notifications unreliable via MQTT | Medium | Functionality |
| 3 | Non-standard datetime format in MQTT payloads | Medium | Data format |
| 4 | Operations enum ambiguity (`POST` vs `CREATE`) | Low | API contract |
| 5 | Nil datetime fields in MQTT notification objects | Low | Data format |
| 6 | Retained MQTT messages cause test interference | Low | MQTT broker |
| 7 | VEN name uniqueness not enforced | Informational | Spec compliance |
| 8 | Targeted webhook notifications not delivered | Medium | Functionality |
| 9 | Webhook payloads are double-encoded JSON | Medium | Data format |

Issues 1-3, 8-9 required workarounds in our client library or test infrastructure. Issues 4-7 are minor and primarily affect developer experience.
