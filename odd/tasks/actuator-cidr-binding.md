# Actuator allow-list: the declared key never bound

Issue **#979**. Branch `fix/actuator-cidr-binding`.

**Status**: implemented — the property that the file declares is renamed to the one relaxed binding
fills, a test loads the real properties file and proves the operator variable reaches the field, and
`AGENTS.md` names the key that works. Nothing is deferred.
**Created**: 2026-11-19 · **Risk**: high — item 2 (this is the actuator's authorization gate) and item 6
(no test on the tree proved the key reached the field, which is why it stayed dead).

## Specs

Quoted verbatim from issue #979.

**S1** — "A test fails before the fix and passes after it, proving that setting `ACTUATOR_ALLOWED_CIDRS`
changes `SecurityAccessProperties.getActuatorAllowedCidrs()`."

**S2** — "With the dev stack up and `ACTUATOR_ALLOWED_CIDRS=172.16.0.0/12`, `GET /actuator/prometheus` on
`lifecontrol-api:8082` answers 200 from a container on the same Docker network, and Prometheus reports
the `lifecontrol-api` target `up`."

**S3** — "With the range removed, the same request answers 401 (the gate is still a gate)."

**S4** — "The existing actuator, security-config and rate-limit suites pass."

## Decisions

**D1 — rename the key, do not reshape the class.** `app.security.actuator.allowed-cidrs` becomes
`app.security.actuator-allowed-cidrs`, the spelling that binds to `actuatorAllowedCidrs`. The nested
shape would need an `Actuator` holder inside `SecurityAccessProperties` for a single knob, and the flat
spelling is what `app.security.trusted-proxies` and `app.rate-limit.trusted-proxies` already use in this
tree. The old key never bound, so no deployment can have depended on it — a claim the renaming test
makes checkable rather than asserted.

**D2 — the test loads the real `application.properties`.** Restating the key in the test would pass
against the broken file, which is the defect itself. `ConfigDataApplicationContextInitializer` reads the
shipped file and the runner supplies only the operator variable, so the assertion is exactly "the key the
file declares is the key the binder fills".

## Tasks

| ID | Specs | Task | Evidence |
| --- | --- | --- | --- |
| T1 | S1 | `SecurityAccessPropertiesBindingTest` fails before the fix on the actuator allow-list case and passes after | RED: `3 tests completed, 1 failed` — `Expecting actual: ["127.0.0.1/32", "::1/128"] to contain exactly ["172.16.0.0/12"]`; GREEN: `BUILD SUCCESSFUL`. Independently reproduced by a verifier in an isolated scratch copy against the baseline file (`3 found, 1 failed`) and the candidate (`3 found, 0 failed`) |
| T2 | S1, S4 | Rename the key in `application.properties` and name the working key in `AGENTS.md` | `./gradlew test` on the security package plus the full suite |
| T3 | S2, S3 | Re-validate the dev stack: `/actuator/prometheus` 200 from inside the network, target `up`, and 401 once the range is removed | API image rebuilt from this branch's jar and recreated: `HTTP/1.1 200` from the `redis` container, XFF `172.18.0.5` → 200 and XFF `127.0.0.1` → 401, Prometheus `lifecontrol-api up`, `deploy.sh dev health` → `All services healthy!` |

## Log

**L1** — the human's request, verbatim: *"si crea el issue y has el arreglo y arma el PR"*, answering a
question that described the defect and asked for the issue's approval so the pull request could be
opened.

**L2** — 2026-11-19, measured on the running dev stack before any edit: `lifecontrol-api` healthy on
`127.0.0.1:8082`, container env `ACTUATOR_ALLOWED_CIDRS=172.16.0.0/12`, Docker subnet `172.18.0.0/16`,
and `/actuator/prometheus` answering 401 with no header and with `X-Forwarded-For: 172.18.0.5`, 200 with
`X-Forwarded-For: 127.0.0.1`. The inversion is the evidence that the effective list was the field
default, not the declared range.

**L3** — 2026-11-19, why it stayed invisible: until issue #892 the field default and the file carried the
same RFC1918 list, so a property that never bound produced the same behaviour as one that did. Narrowing
the default turned the dead knob into a refusal, and `deploy.sh dev health` went red on `up == 0`. This
unit is the fix-forward of that regression.

**L4** — 2026-11-19, independent verification of the risk item 2: the verifier compiled the shipped class
and the new test with the project's own jars and ran them against two copies of the real
`application.properties` — baseline `56339efd` (nested key) failed the allow-list case, the candidate
(flat key) passed all three. It also confirmed the test loads the shipped file rather than restating the
key (instrumented run shows the `class path resource [application.properties]` source and the placeholder
resolving from it, and `withPropertyValues` lands as a non-prefixed source that can only feed the
placeholder), read the Boot 4.1.2 initializer source to rule out a short circuit, and swept all eleven
`@ConfigurationProperties` holders in the tree for the same nested-vs-flat mismatch: no second instance.
Blast radius clean — `ACTUATOR_ALLOWED_CIDRS` is still passed by the template and the compose line and
consumed by the same placeholder, and the old property spelling has no live reference anywhere.

**L5** — 2026-11-19, considered and declined: the verifier offered a second probe suite asserting that the
nested spelling stays dead and that the shipped file never grows a nested twin. Declined because it
asserts a spelling in a file rather than a behaviour of this code, and the guard that would actually catch
a relapse is S2/S3 measured against the running stack; a dead duplicate key alongside the working one is
the only shape it would catch, and that shape is visible in the same one-line review. The cost is recorded
here rather than paid in review surface.

**L6** — 2026-11-19, validated against the running dev stack without touching the deployment's material:
the jar built from this branch was placed in the anchor's build directory, only the API image was rebuilt
with `docker compose … build lifecontrol-api`, and the container was recreated. Measured after that:
`/actuator/prometheus` answers `HTTP/1.1 200` from a container on the same network; `X-Forwarded-For:
172.18.0.5` (inside the declared range) answers 200 while `X-Forwarded-For: 127.0.0.1` (outside it)
answers 401, so the gate is still a gate and the inversion L2 recorded is gone; Prometheus reports
`lifecontrol-api up`, where it reported `down`; and `./docker/scripts/deploy.sh dev health` ends in `All
services healthy!`. The API suite on this branch is 5171 tests / 0 failures / 0 skipped with
`spotlessCheck` and `spotbugsMain` green.

**L7** — 2026-11-19, why the validation did not run before the branch existed: the dev stack reads the
deployment's materialised environment and secrets, which live in the anchor and are refused to any agent
session, and a fresh worktree has neither. Building the jar here and rebuilding only the image in the
anchor is the flow that measures this fix without copying a secret or editing a tracked deployment file.
