# VTN test campaign workflow

This document describes how to run the `clj-oa3-test` integration suite against a VTN under test, file the resulting report, and (optionally) run a series of these against the same VTN over time as a campaign.

If you're new to the suite, read [README.md](README.md) first for the test inventory and configuration knobs. This document focuses on the *process* of producing a useful report.

---

## Conceptual primer

| Concept | What it is |
|---|---|
| **VTN implementation** | A codebase — `clj-oa3-vtn`, `openadr3-vtn-reference-implementation`, future gateway implementations, etc. |
| **VTN deployment** | A *running* instance of an implementation, configured a particular way: which ports, what auth, which `:ven-routes`, what MQTT mode. |
| **Effective capability** | What this deployment actually exposes, given its configuration. Probed + declared. The harness operates here. |
| **Test campaign** | A series of test runs against successive states of the same deployment, recorded over time. The deliverable. |

The harness focuses on **conformance testing of dev/test deployments you control** — running write-side tests against a live hosted service is out of scope (different problem with different shape). |

---

## One-shot run

The shortest path: pick a profile, point it at your VTN, run.

```bash
# 1. Pick a profile from the library that matches your VTN flavor
cp test-config.clj-oa3-vtn.edn test-config.edn
# (or test-config.vtn-ri.edn, test-config.vtn-ri-fastapi-anon-mqtt.edn, …)

# 2. Adjust :tokens (and any deployment-specific overrides) in test-config.edn

# 3. Start the VTN-under-test in the matching configuration
#    (see the comment block at the top of the profile file)

# 4. Run the suite
clojure -M:test
```

Two artifacts land in `report/`:

- `report/test-report.edn` — machine-readable, single source of truth
- `report/test-report.txt` — terminal-friendly tabular summary

---

## Filing a campaign report

The convention is `<campaigns-dir>/<deployment>/<YYYY-MM-DD>[-<sha>].md` — auto-organized by deployment name. `<campaigns-dir>` defaults to `reports/campaigns` in the repo, overridable via `:campaigns-dir` in `test-config.edn`. The deployment name comes from `:vtn :deployment` in the same config.

`bin/format-report` produces a markdown skeleton from the EDN report — header (VTN identity, capability profile with sources, headline numbers), per-suite tables, failure detail, and placeholders for narrative (Findings, Setup notes). You expand the placeholders with prose.

The easy path: set `:vtn :deployment` in `test-config.edn`, then:

```bash
bin/format-report --file
# Writes <campaigns-dir>/<deployment>/<YYYY-MM-DD>.md and .edn for you
# Then edit the .md to fill in Headline, Findings, Setup notes
```

Or pipe explicitly if you'd rather route by hand:

```bash
bin/format-report > reports/campaigns/clj-oa3-vtn/2026-05-08.md
cp report/test-report.edn reports/campaigns/clj-oa3-vtn/2026-05-08.edn
```

Inside `reports/campaigns/`, `.md` and `.edn` files are kept under git; `.txt` summaries and `.log` files are gitignored (redundant / large noise).

### Where do reports go long-term?

A few options, depending on your relationship to the project:

- **External storage** (a private repo, an S3 bucket, a personal "shadow repo" that mirrors this layout, etc.). Set `:campaigns-dir` in `test-config.edn` to point there. The maintainer of this project uses a local shadow-repo at `~/projects/grid/shadow-repo/clj-oa3-test/reports/` — that's a personal pattern, not a community convention.
- **Fork and commit to your fork.** Useful if you're tracking a private VTN that shouldn't be public.
- **Ephemeral** — skip the filing step; just look at the markdown skeleton on-the-fly with `bin/format-report` (no `--file`).
- **Contribute back to `clj-oa3-test`** via PR — for canonical examples that are useful to the wider community. New VTN flavors are especially valuable as community resources.

For canonical worked examples, see [`reports/campaigns/clj-oa3-vtn/2026-05-08.md`](reports/campaigns/clj-oa3-vtn/2026-05-08.md) and the [`vtn-ri-fastapi/`](reports/campaigns/vtn-ri-fastapi/) series (three runs over 2026-05-07/08/09 against successive states of the same VTN — the campaign concept in action).

---

## What a good campaign report contains

The skeleton lays this out, but spelling it out:

1. **VTN identity** — implementation, version, commit, deployment name, URL. Mostly auto-populated from the `:vtn` map in `test-config.edn` if you set it.
2. **Capability profile** — what the VTN claims to support (advertised + declared) vs. what was probed. Source of each fact (`declared`/`advertised`/`auto-detected`/`defaulted`) is rendered.
3. **Headline** — one-line summary of the run. Numbers (X passed, Y failed, Z N/A) plus what's new vs. last run if this is a follow-up.
4. **Per-suite tables** — every test, status (PASS / FAIL / ERROR / N/A / SKIP), and a notes column.
5. **Failures** — for each FAIL/ERROR, the assertion details and a `file:line` cite.
6. **Findings** — *narrative* analysis: each finding numbered, severity-tagged, with a reproducer, root-cause hypothesis, and "what would unblock it." This is the part a reader cares most about — distill failures into themes.
7. **Setup notes** — anything non-obvious about how the run was configured: docker-compose vs. host stack, branch under test, override knobs, environment caveats.

A good campaign report is one that someone working on the VTN under test can read and immediately know what to fix next.

---

## Capability gating in the report

Tests that the VTN doesn't advertise capability for show as **N/A** rather than **FAIL** — driven by the `:requires` metadata on each test/suite and the merged capability profile. This is the difference between "VTN is broken" and "VTN doesn't claim to support this."

| Marker | Meaning |
|---|---|
| `PASS` | Test ran and passed |
| `FAIL` | Test ran and failed (real bug) |
| `ERROR` | Test threw an uncaught exception |
| `SKIP` | Test was elided by `auth-gate` (auth not enforced ⇒ auth-tests are meaningless) or other meta-skip |
| `N/A` | Test was elided by `capability-gate` — VTN profile doesn't satisfy the test's `:requires` |
| `PEND` | `clojure.test` pending |

A report with many `FAIL`s is a sign of bugs in the VTN. A report with many `N/A`s isn't — it's a sign that the deployment's capability profile is narrower than the full suite, which is fine and expected for partial-coverage VTNs (clj-price-server, oa3-gateway, etc.).

---

## Repeating against new VTN flavors

To add a new VTN deployment to your campaign matrix:

1. Copy `test-config.example.edn` → `test-config.<deployment-name>.edn`.
2. Fill in `:vtn-url`, `:tokens`, and the `:capabilities` map. Run the suite once and let auto-detection populate what it can — anything that comes back as `:auto-detected` you don't need to declare.
3. Document the deployment-specific setup in a comment block at the top of the file (how to start the VTN to match this profile).
4. Add a row to the README's "Configuration files" table.
5. (Optional) seed `reports/campaigns/<deployment>/` with a baseline report.

---

## Re-running a campaign

When the VTN under test has new commits / a new release:

1. Update the VTN, restart it.
2. (If you set `:vtn :commit` in `test-config.edn`, refresh that to the new SHA.)
3. `clojure -M:test`
4. `bin/format-report > <campaign-dir>/<new-date>.md`
5. Compare to the previous report — what changed in the headline numbers, what new failures appeared, what got fixed.

The 2026-05-07 → 2026-05-08 VTN-RI fastapi campaign is a worked example of this pattern (`+30 tests passing, no regressions`).
