# Contributing to clj-oa3-test

Thanks for your interest in contributing! This repo is an independent, third-party Clojure integration test suite for OpenADR 3 VTN implementations. It uses [Kaocha](https://github.com/lambdaisland/kaocha) as the runner and the [clj-oa3-client](https://github.com/grid-coordination/clj-oa3-client) / [clj-oa3](https://github.com/grid-coordination/clj-oa3) libraries as the test harness against any VTN that speaks OpenADR 3.1.0 over HTTP, MQTT, and webhooks.

This suite is not the VTN itself, not a client library, and not certification — see the [Important notice](#important-notice) at the bottom.

## How to contribute

### Discussions

Use [Discussions](https://github.com/grid-coordination/clj-oa3-test/discussions) for:

- Questions about running the suite — `bin/test-stack-*` scripts, mosquitto setup, dynsec vs anonymous MQTT, dual-port vs single-port VTN configs, `--focus` and `--exclude-meta` usage
- Suite-design judgment calls — when a new test belongs in this integration suite vs a unit test in the underlying lib, how to scope shared state across suites, how to model VTN-specific quirks
- Adding a new VTN target — `test-config.<vtn>.edn` for a different implementation, what `:capabilities` profile (notifiers, http-auth, ven-routes, handlers) the new target needs
- Suite-dependency questions — when a new suite depends on data created by another, how to wire `:suite-deps/requires` correctly
- Cross-implementation parity discoveries — "VTN A returns 200 here, VTN B returns 400, what does the spec say?"
- Coordination with sibling repos: [clj-oa3](https://github.com/grid-coordination/clj-oa3) (client lib, shared schemas), [clj-oa3-client](https://github.com/grid-coordination/clj-oa3-client) (Component lifecycle), [clj-oa3-vtn](https://github.com/grid-coordination/clj-oa3-vtn) (Clojure VTN), [openadr3-specification](https://github.com/grid-coordination/openadr3-specification) (upstream OpenAPI specs), and the [OpenADR Alliance VTN-RI](https://github.com/oadr3-org/openadr3-vtn-reference-implementation)

Discussions are open-ended — a good place to think out loud or scope something before it becomes a concrete change. Aligned outcomes from a Discussion often turn into one or more Issues.

### Issues

Use [Issues](https://github.com/grid-coordination/clj-oa3-test/issues) for actionable changes:

- Test false positives / false negatives — an assertion that disagrees with the OpenADR 3 spec
- Suite ordering bugs — race conditions or shared-state leakage between suites that show up under particular `--focus` combinations
- Missing coverage — a CRUD path, role-enforcement case, notification topic, or pagination edge case that the suite doesn't currently exercise
- VTN-incompatibility regressions — a test that worked against VTN X stops passing after an upstream lib bump (clj-oa3, clj-oa3-client) or a kaocha plugin change
- Test report bugs — `report/test-report.edn` or the tabular `test-report.txt` showing the wrong shape, count, or formatting
- `bin/test-stack-*` script bugs — config not being applied, mosquitto/VTN-RI not starting cleanly, dynsec setup gaps, port checks
- Documentation errors, unclear explanations, stale prose in `README.md`, `CLAUDE.md`, or test docstrings

If you're not sure whether something is an Issue or a Discussion, start with a Discussion — we can convert it later.

### Pull requests

Pull requests are welcome.

- For small fixes (typos, broken links, single-test corrections, single-script bug fixes), open a PR directly.
- For substantive changes (a new test suite, a new VTN target config, a major fixture refactor, changes to `tests.edn` or the report plugin), open a Discussion or Issue first so we can align on scope before you invest the effort.
- All changes pass `clojure -M:test` (Kaocha) and `clj-kondo --lint test` cleanly against at least one VTN target.
- Match the existing tone and structure. Each suite is a Clojure namespace under `test/openadr3/` that uses `client/` wrappers and the shared fixtures from `common_test.clj`; patches that fit cleanly into one suite without leaking state into others are the easiest to land.
- One commit per logical change is fine; no squash or branch-naming convention is required.

## Development

```bash
clojure -M:test                                    # run the full Kaocha suite
clojure -M:test --focus :programs                  # one suite + its prerequisites
clojure -M:test --focus :mqtt --focus :webhook     # multiple suites
clojure -M:test --exclude-meta :auth               # skip auth tests (no-auth VTNs; or set :capabilities {:http-auth {:enforced? false}} in test-config.edn)
clojure -M:nrepl                                   # nREPL — port written to .nrepl-port
clj-kondo --lint test                              # lint
```

Test stack scripts (macOS + Homebrew, manage VTN-RI + mosquitto + optional callback service):

```bash
bin/test-stack-start-anon                          # anonymous MQTT
bin/test-stack-start-dynsec                        # dynsec (authenticated) MQTT
bin/test-stack-start-anon --with-callback          # also start the webhook callback service
bin/test-stack-stop
bin/test-stack-status
```

For VTNs other than the VTN-RI, start them yourself and point `test-config.edn` at the right URLs. Example configs for common setups are in `test-config.*.edn`.

The shared OpenADR 3 OpenAPI spec is bundled in the `clj-oa3` JAR; this repo doesn't need its own copy. Wire-format entity schemas and coercion live in [clj-oa3](https://github.com/grid-coordination/clj-oa3) — bugs in coercion or schema typically belong upstream there, not in test corrections here.

The Kaocha report plugins under `test/kaocha/plugin/` (`test_report.clj`, `suite_deps.clj`) produce the structured EDN report and resolve suite-prerequisite ordering — see the README for the report format.

## Code of conduct

Be respectful and constructive. We're a small project and appreciate everyone who takes the time to file an issue or send a PR.

## Important notice

**This project is not affiliated with, endorsed by, or reviewed by the [OpenADR Alliance](https://www.openadr.org/).** The tests in this repository are best-effort integration checks against the OpenADR 3 specification as published by the Alliance and vendored in the [openadr3-specification](https://github.com/grid-coordination/openadr3-specification) repo. They **do not constitute, and are not a substitute for, official OpenADR Alliance certification or conformance testing**. Passing this suite against a VTN is not a claim of compliance with OpenADR 3 — independent verification through the Alliance's certification program is required for any formal compliance claim.

This suite is provided on an "as-is" basis. Updates and maintenance, including responses to issues filed on GitHub, will take place on an "as time and resources permit" basis. "OpenADR" is a registered trademark of the OpenADR Alliance; its use in this project is descriptive (referring to the protocol the suite tests against) and does not imply Alliance endorsement.
