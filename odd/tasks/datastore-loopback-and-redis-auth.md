# ODD feature: datastore-loopback-and-redis-auth

**Repository**: LifeControl — `docker/docker-compose*.yml`, `docker/entrypoints/`, `docker/scripts/tests/`, `docker/.env.template`, `life-control-api/src/main/resources/application.properties`, `AGENTS.md`, `odd/tasks/`
**Branch**: `fix/datastore-loopback-and-redis-auth` · **Worktree**: `~/workspace/LifeControl-worktrees/fix-datastore-loopback-and-redis-auth`
**Issue**: #890 — `fix: redis, postgres and mailpit are published on every host interface, and redis authenticates nobody` (`status:approved`, approved by the maintainer on 2026-11-19)
**Status**: implemented — the loopback half (S1, S2) and the credential half (S5) are in the tree, wired through compose and the two wrappers, and pinned by `docker/scripts/tests/datastore-port-binding.test.sh`. What is left is not implementation: **T2**, `docker/secrets/redis_password.template`, which this harness's safety policy blocks and the maintainer writes (`L4`); the **live probes** of S3 and S4, which need the dev stack running and are out of scope for an offline session (`L7`); and one **suite outside this unit's edit surface**, `docker/scripts/tests/fiscal-wiring.test.sh`, whose sandbox materializes three credentials and now needs a fourth, so its 27 wrapper assertions are red until two lines are added there (`L8`).
**Created**: 2026-11-19
**Risk**: items **2** and **5**. Item 2 because the unit introduces a credential channel — a Redis password read from a mounted file, exported to the API and to the exporter — and a wrong wiring either leaves Redis authenticated in name only or leaks the password into an environment value, which is the exact shape `app-credential-split.test.sh` exists to prevent. Item 5 because it changes the published port surface of every environment and the start path that `deploy.sh <env> start` walks, and a mistake here is invisible on a laptop and reaches a deploy. Not item 1 (no stored data is rewritten), not item 3 (no API contract, CLI flag or config format consumed by a client changes: the host side of each mapping keeps its variable and the container side keeps its port), not item 4 (no concurrency), not item 6 (a suite pins each mapping and each half of the credential wiring).

## Specs

1. **S1** — `docker compose config` reports `host_ip: 127.0.0.1` for redis, both Postgres instances (base and prod override), mailpit and the application API port.
2. **S2** — A suite under `docker/scripts/tests/` fails if any of those mappings loses its host prefix, and passes on the fixed tree.
3. **S3** — A `redis-cli` PING against the host's LAN address is refused, while the loopback address answers `NOAUTH` without the password and `PONG` with it.
4. **S4** — `./docker/scripts/deploy.sh dev start` still reaches healthy, including `db-roles.sh`, the preflight reports and every healthcheck.
5. **S5** — In prod the Redis password arrives through a mounted secret file and appears in no compose file and no env file.

Scope boundary, taken from the issue rather than invented: the set S1 names is the set that moves. Keycloak, the web app and the management ports are **not** in it — the first two are the browser-facing entry points of every environment, and changing them would move an operator-facing URL that this issue does not name. Any further narrowing or widening is a maintainer decision recorded as a new `L` entry, not a silent edit here.

## Tasks

| ID | Spec | Route | Delivered in |
|----|------|-------|--------------|
| T1 | S1, S5 | Feature doc, mirror and the `todo` projection — this file | this file |
| T2 | S1 | `docker/secrets/redis_password.template` — **blocked by the safety policy on `docker/secrets/`**, the maintainer writes it | blocked |
| T3 | S1 | Both Postgres mappings and mailpit: prefix the host side with `127.0.0.1:`, base + prod override | `docker-compose.yml`, `docker-compose.prod.yml`, `docker-compose.override.yml` |
| T4 | S1 | Redis: loopback mapping, `--requirepass` through a wrapper that reads the secret file, `redis-cli` healthcheck authenticated | `docker-compose.yml`, `docker/entrypoints/redis-entrypoint.sh` |
| T5 | S5 | The API reads `REDIS_PASSWORD` from the wrapper-exported secret, `application.properties` consumes it, the exporter uses `--redis.password-file` | `docker/entrypoints/lifecontrol-app-entrypoint.sh`, `life-control-api/src/main/resources/application.properties`, both compose files |
| T6 | S1 | The application API service mappings, loopback | `docker-compose.yml`, `docker-compose.prod.yml` (gateway + management) |
| T7 | S2 | `docker/scripts/tests/datastore-port-binding.test.sh`: the frozen mapping table, the container side and the host-side variable unchanged, and the four credential assertions of S5 | `docker/scripts/tests/datastore-port-binding.test.sh` |
| T8 | S2–S5 | Run the whole `docker/scripts/tests/*.test.sh` glob, then the live probes of S3 and S4 against the dev stack | glob run **done** (`L6`); the live probes of S3/S4 are **deferred**, they need the running dev stack |
| T9 | — | Docs: `AGENTS.md` secret list, port-binding row and the `Service URLs` note | `AGENTS.md` |

## Log

**L1** — user, verbatim: «ok dale a 890, aprueba el issue».

**L2** — #890 approved on that instruction and recorded on the issue ([comment](https://github.com/le03nava/LifeControl/issues/890#issuecomment-6997255884)): `CONTRIBUTING.md` §1 makes `status:approved` a human decision, so the label records the maintainer's, not the author's.

**L3** — measured on this tree before writing anything: the offenders are `docker-compose.yml:20` (`5433:5432`), `:52` (`5435:5432`), `:475` (`${REDIS_PORT:-6379}:6379`), `:517` (the API's own port), `docker-compose.prod.yml:30`, `:373` and `docker-compose.override.yml:71` (mailpit). The seven observability and exporter mappings already carry `127.0.0.1:` and are frozen by `observability-port-binding.test.sh`, which is the pattern this unit copies. Redis's command at `:478-486` passes no password flag, the API declares no `spring.data.redis.password`, and `redis-exporter` carries a comment at `:283` that already anticipates `--redis.password-file=/run/secrets/redis_password`.

**L4** — the blocker, recorded rather than worked around: a read of `docker/secrets/` was refused by the harness with *"Gentle AI safety policy blocked access to sensitive path: docker/secrets/. Ask the user for an explicit safer plan."* The unit needs one **new** file there (the password template, because `setup-env.sh` materializes `*.template` and `validate-env.sh` fails when a template is unmaterialized), so T2 is blocked and S3/S5 cannot be proven without it. No alternative channel was invented: putting the password in a compose file or an env file is exactly what S5 forbids, and reusing another service's secret would cross the credential boundary `app-credential-split.test.sh` protects.

**L5** — the state this half inherited, verified rather than assumed. `git status --short` showed
` M docker/docker-compose.yml`, `?? docker/entrypoints/redis-entrypoint.sh` and this record, so the parent
had already taken the base file: `127.0.0.1:` on both Postgres mappings, on the API's own port, on the
gateway and its management port, and on `redis`; the whole `redis` service block (wrapper entrypoint, the
wrapper mount, the secret file, the authenticated healthcheck); and `redis-exporter` with
`--redis.password-file=/run/secrets/redis_password` plus the secret mount. One thing did not survive the
handoff and was fixed here: **`docker/entrypoints/redis-entrypoint.sh` was not executable** (`0644`), and
it is a wrapper compose mounts and runs — the start would have failed with `permission denied`. The new
suite pins the bit, not just the file's content.

**L6** — the shellcheck surface grew by existence, which is how it is supposed to work: the `ShellCheck`
step of `PR Label Guard` lints `docker/scripts/**` by glob, so a new suite is covered for existing. The
caveat belongs here because it could not be measured: **no `shellcheck` binary is installed in this
worktree**, so both touched suites are reported as *not linted locally*, written against the style of
`observability-port-binding.test.sh`, which does pass that gate in CI.

**L7** — what the offline half could not prove, said out loud instead of implied green. **S3** (a
`redis-cli PING` from the host's LAN address refused, `NOAUTH` on loopback without the password, `PONG` with
it) and **S4** (`./docker/scripts/deploy.sh dev start` reaching healthy, including `db-roles.sh` and every
healthcheck) both need the live stack, and `docker compose up` / `deploy.sh` were out of bounds for this
session. What is proven instead is the static half: the mapping carries the loopback prefix, the wrapper
defaults to the mount path, the wrapper refuses to start without the password, the healthcheck passes `-a`
so `NOAUTH` cannot read as healthy, and the exporter asks for a file rather than a value. The consequence to
carry forward: **a fresh `deploy.sh dev start` is the first thing that exercises the new channel**, and it
will refuse to start — loudly, by design — until the redis password secret exists (`L4`, T2).

**L8** — the glob (`bash docker/scripts/tests/*.test.sh`, 29 suites) came back with **28 green and 1656
assertions passed**, and one suite red: `docker/scripts/tests/fiscal-wiring.test.sh`, 27 failures, all of
them one cause. Its wrapper sandbox materializes `owner`, `app` and `admin` and passes those three `*_FILE`
paths; the app wrapper now requires a fourth credential, so every run in that sandbox stops at
`FATAL: … redis password file is missing or empty` before `java` is started, and the suite reads that as
"the wrapper never ran". It is a genuine consequence of this unit and **not** an environmental or unrelated
failure, so it is not reported as acceptable: the fix is two lines in that file (materialize one more file
under its sandbox and pass `REDIS_PASSWORD_FILE` beside the other three), and that file is outside this
half's allowed edit surfaces. Escalated rather than patched from here, and not silenced by relaxing the
wrapper's requirement, which would undo the boundary S5 exists for.

**L9** — the escalation was resolved in favour of the unit rather than the surface: the parent made the
two-line fixture edit in `docker/scripts/tests/fiscal-wiring.test.sh` (one more file under its sandbox,
`REDIS_PASSWORD_FILE` beside the other three) and that suite went from 27 failures to **97 passed, 0
failed**. The same pass corrected a premise this unit falsified: `docker/scripts/tests/exporters.test.sh`
still said this stack's Redis runs without `--requirepass`, which stopped being true the moment the wrapper
landed; its comment now states the requirement and keeps the branch that accepts a compose file with no
password, because that suite audits every compose file and not only this one (75 passed after the edit).

**L10** — the delivery is three commits, and the slice boundary is declared rather than left to the
reviewer to discover: behaviour and its two sandbox fixtures first, then the new offline suite alone, then
this record. Measured 692 authored lines, over the ~400-line review budget, and the overage is one file —
`datastore-port-binding.test.sh`, 422 lines of frozen table and assertions. Chaining was considered and
rejected: the three slices are one unit, nothing in the second or third can land without the first, and
`references/pr-chains.md` reserves a chain for units that are independent of each other. What that leaves
is a reviewer-facing risk, so it is stated here: the suite is where the review time goes.

**L11** — what this record cannot claim, listed as limits instead of omissions. S1's literal evidence —
`docker compose config` printing `host_ip: 127.0.0.1` — was not produced, because interpolation needs a
materialized `docker/.env.<env>` and that path carries the environment's passwords; the suite pins the same
property textually, so the claim is made by a test rather than by a transcript. S3 and S4 were not run for
the same class of reason: they need the live stack, and starting it is the maintainer's decision, not this
unit's. Shellcheck is not installed in this worktree, so the two touched suites are unlinted locally; CI's
`PR Label Guard` lints `docker/scripts/**` by glob, which is where that check happens.

