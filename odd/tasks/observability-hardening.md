# ODD feature: observability-hardening

**Repository**: LifeControl — `docker/**` (compose, `config/`, `scripts/`, `scripts/tests/`),
`api-gateway/**` and `life-control-api/**` (properties and `logback-spring.xml` only), plus the two
dev-prod documents that describe them. No schema, no migration, no API contract, no Angular feature code.
**Status**: **implemented** — T1 to T5 are written: the deploy gate asserts the signals, Loki's retention and
the loopback bindings are in the repository, the gateway ships a Loki appender, and the API honours
`TRACING_SAMPLING_PROBABILITY` and `LOG_LEVEL`. T6 to T9 remain (Grafana config hygiene, provisioned
dashboards and alerts, exporter and worker metrics, docs and parity suites), and no decision blocks them.
**Created**: 2026-10-24 · **Branch**: `fix/observability-hardening` · **Base**: `main` @ `53725ea` ·
**Worktree**: `~/workspace/LifeControl-worktrees/fix-observability-hardening` · **Risk**: **items 1 and
5** — T1 discards the on-disk trace store and T2 introduces scheduled log deletion (data), while T3, T8
and T9 change what the stack publishes and what the deploy scripts assert (delivery and environment).

## Objective

Turn observability from "the containers are up" into "the signals are readable and something says so".
Today the stack collects three signals and can vouch for none of them end to end: Tempo ingests and
cannot read back what it flushed, Loki keeps logs forever and answers queries for 30 days, the whole
plane is published on `0.0.0.0` without authentication, one of the two services ships no logs at all,
Grafana holds zero dashboards and zero alert rules, and `deploy.sh health` reports green through all of
it. Each task below closes one measured gap; S1–S12 are the acceptance statements and the evidence they
were measured from is in the Log.

## Specs

**S1** — A trace that has been flushed to a block is readable again: for every id returned by
`GET /api/search`, `GET http://localhost:3110/api/traces/<traceID>` returns `200`, and
`docker logs lifecontrol-dev-tempo | grep -c 'vParquet2 is not a valid block version'` is `0`.
Measured on the current tree: `HTTP 500` with `error opening block for reading, blockID: <uuid>:
vParquet2 is not a valid block version`, 71 occurrences, against `tempo v3.1.0` writing `vParquet5`,
with blocks dated 2026-10-13 and 2026-10-16 still on disk.

**S2** — `./docker/scripts/deploy.sh dev health` fails when observability is degraded, not only when a
container is down: it asserts that `GET :9090/api/v1/targets?state=active` reports `health == "up"` for
`lifecontrol-api`, `api-gateway` and `prometheus`, and that a trace read answers `200` as in S1. Pinned
by a new suite under `docker/scripts/tests/` that runs the probe against a fake endpoint and asserts both
the pass and the fail verdict.

**S3** — Loki's retention is declared in this repository instead of inherited from the packaged
config: `GET http://localhost:3100/config` reports `limits_config.retention_period: 30d` (the 720h the
file declares), `compactor.retention_enabled: true` and `compactor.delete_request_store: filesystem`, and
no longer reports `limits_config.retention_period: 0s`. Those values come from
`docker/config/loki/loki.yml`, mounted read-only from the repository rather than copied into
`$VOLUMES_ROOT` the way `prometheus.yml` and `tempo.yml` are — a copy-if-missing install is never
refreshed, and a retention policy belongs in a reviewed commit. A suite under `docker/scripts/tests/`
pins the values, the read-only mount, the `-config.file` target, and the absence of the pre-2.6
`limits_config.enforce_retention_period` key, which this image rejects at parse time. Measured before the
change: `retention_period: 0s`, `enforce_retention_period: false`, `max_query_length: 30d1h`, 189 MB of
logs on the dev host volume.

**S4** — No observability port is reachable from every host interface: no line in `docker-compose*.yml`
publishes `3100`, `9090`, `3110`, `3000` or their `${*_PORT}` variables without an explicit `127.0.0.1:`
(or documented internal-only) binding, and Prometheus no longer runs with `--web.enable-lifecycle` while
its port is published. Pinned by a suite that greps the compose files and fails on a bare `0.0.0.0`
publish. Measured today: `"${PROMETHEUS_PORT:-9090}:9090"`, `"${LOKI_PORT:-3100}:3100"`,
`"${TEMPO_PORT:-3110}:3100"` and `"${GRAFANA_PORT:-3000}:3000"` in the base file, mirrored in the prod
override, with `--web.enable-lifecycle` in Prometheus' args.

**S5** — The gateway's logs reach Loki: `GET http://localhost:3100/loki/api/v1/label/application/values`
returns both applications, and a suite asserts that every module that depends on
`loki-logback-appender` ships a `logback-spring.xml` declaring the `Loki4jAppender`. Measured today: the
label returns `["lifecontrol-api"]` only, while `api-gateway` has the dependency and the `loki.*`
properties but no logback configuration at all.

**S6** — Production sampling honours the environment variable: with
`TRACING_SAMPLING_PROBABILITY=0.01` in `docker/.env.prod`, the API's effective
`management.tracing.sampling.probability` is `0.01`. Measured today:
`life-control-api/src/main/resources/application-prod.properties:21` hardcodes `0.1`, and a
profile-specific property wins over the base file's `${TRACING_SAMPLING_PROBABILITY:0.1}`, so the env
value is silently ignored. Pinned by a suite that fails when a profile file hardcodes the property.

**S7** — `LOG_LEVEL` from compose is consumed by the API: `LOG_LEVEL=DEBUG` on `lifecontrol-api` raises
the root logger without a rebuild. Measured today: compose passes it
(`docker/docker-compose.yml:349`), `grep -rn 'logging.level.root' life-control-api/src/main/resources`
returns nothing, and `logback-spring.xml` pins `<root level="INFO">`.

**S8** — The Grafana auth block is gone and cannot come back as a knob that does nothing:
`grep -rn 'GF_AUTH' docker/.env*` returns nothing, the two keys the production override *does* forward
(`GF_AUTH_ANONYMOUS_ENABLED`, `GF_AUTH_DISABLE_LOGIN_FORM`) are pinned as compose-internal keys with the
compose defaults as their only values, and `image-pins.test.sh` fails if any of the three keys returns to
the template. Measured before the change: `docker/.env.template` and the four materialised env files
declared `GF_AUTH_ANONYMOUS_ENABLED=true`, `GF_AUTH_ANONYMOUS_ORG_ROLE=Admin` and
`GF_AUTH_DISABLE_LOGIN_FORM=true`; the base compose forwards none of them (`/api/dashboards/home` answers
`401`, `/login` answers `200`), while `docker/docker-compose.prod.yml:186` forwards
`GF_AUTH_ANONYMOUS_ENABLED=${GF_AUTH_ANONYMOUS_ENABLED:-false}` — so in production that block was not inert
at all: a `.env.prod` materialised from the template would have run Grafana with anonymous admin.

**S9** — One Grafana version everywhere, and the check that keeps it that way: `.env.template` and the
compose defaults declare `grafana/grafana:13.2.4`, `image-pins.test.sh` compares every `*_IMAGE` key a
materialised `.env.<env>` declares against the template and **fails on divergence**, and it prints a NOTE
instead of a PASS when no env file is materialised, so a comparison that never happened is never reported
as a pass. Measured before the change: `13.2.4` in dev and the template, `10.2.0` in the staging and prod
sequences, with the suite comparing only the template against the compose defaults. The env files are
gitignored and absent from a fresh worktree, so the alignment itself is an operator edit
(`setup-env.sh` never rewrites an existing file) and the parity check is the part that ships.

**S10** — The first dashboards and alerts are versioned, not hand-built: `docker/config/grafana/dashboards/`
holds JSON provisioned through `/etc/grafana/provisioning/dashboards`, covering at least server error
rate from `http_server_requests_seconds_count{outcome="SERVER_ERROR"}`, p95/p99 latency from the
`http_server_requests` histogram, and database pool saturation from `hikaricp_connections_*`, plus at
least one alert rule on the server error rate. Measured today:
`sqlite3 -readonly grafana.db "select count(*) from dashboard; select count(*) from alert_rule;"` →
`0` and `0`, and `docker/config/grafana/` contains only `datasources.yml`.

**S11** — Infrastructure and unattended work are observable: Prometheus reports `pg_up` for both
PostgreSQL instances and `redis_up`, and the access-provisioning worker publishes its queued work by
state (at least `PENDING` with the age of the oldest row), so a stalled queue is visible without reading
logs. Measured today: no exporter exists for either datastore, and
`grep -rl 'MeterRegistry|Counter|Timer' life-control-api/src/main/java` returns nothing.

**S12** — Documentation and cross-file configuration stop drifting: the Observability table in
`life-control-api/AGENTS.md` names OTLP/Tempo instead of `Brave`/`Zipkin`, a suite asserts that every
`targets` entry in `docker/config/prometheus/prometheus.yml` names a service and a port that exist in
compose, and a suite asserts that every URL in `docker/config/grafana/datasources.yml` resolves to a
compose service. Measured today: the table still reads "Micrometer Tracing + Brave + Zipkin" after the
OTLP migration, and `tempo-receivers.test.sh` is the only suite guarding any part of the stack.

## Tasks

| ID | Task | Specs | Route | Commit |
|----|------|-------|-------|--------|
| T1 | Reset the dev Tempo block store — delete, on the operator's explicit authorization — and prove readability end to end (S1); extend `deploy.sh health` with the targets-`up` and flushed-trace-read probes and the suite that asserts both verdicts (S2). | S1, S2 | inline | `29a226e` |
| T2 | Ship `docker/config/loki/loki.yml` with a 30-day retention declared and enforced — `limits_config.retention_period: 720h` plus `compactor.retention_enabled: true` and `delete_request_store: filesystem` — mounted read-only from the repository (never copied by `setup-env.sh`), and the suite that pins the values, the mount and the command (S3). | S3 | worker | `382f9a2` |
| T3 | Bind every observability port to `127.0.0.1` in the base compose and the prod override, and drop `--web.enable-lifecycle` from the Prometheus args; add the compose-grep suite (S4). | S4 | worker | `382f9a2` |
| T4 | Add `api-gateway/src/main/resources/logback-spring.xml` with the `Loki4jAppender` wired to the existing `loki.*` properties, and the parity suite that fails when a module depends on the appender without declaring it (S5). | S5 | inline | — |
| T5 | Make the API's prod sampling read the environment variable and make `LOG_LEVEL` reach the root logger; pin both with a properties suite (S6, S7). | S6, S7 | inline | — |
| T6 | Remove the Grafana auth block from the template, pin the two keys production does forward as compose-internal, and extend `image-pins.test.sh` with per-environment pin parity plus the guard that the block cannot come back (S8, S9). | S8, S9 | worker + parent | — |
| T7 | Provision the first dashboards and the first alert rule from the repository, with `setup-env.sh` installing them the way datasources already are (S10). | S10 | inline | — |
| T8 | Add the PostgreSQL and Redis exporters to compose and Prometheus, and publish the worker's queue gauge from `life-control-api` (S11). | S11 | inline | — |
| T9 | Correct the `AGENTS.md` Observability table and add the two cross-file parity suites for `prometheus.yml` and `datasources.yml` (S12). | S12 | inline | — |

## Decisions

**D1 — The dev store was deleted, not archived (T1), on explicit authorization.** The first draft of
this decision archived the directory, because the blocks' narratives are frozen and a downgrade would
otherwise have nothing to read. The operator answered that deleting them is fine, so the plan default
yielded to the owner's decision: the directory is emptied and the running image owns the store from
here on. Staging and prod are not touched by this unit.

**D2 — Loopback binding instead of adding authentication (T3).** The stack has no reverse proxy and no
identity for machine consumers, so an auth layer would be a new component; binding to loopback keeps the
ports usable from the host (where `deploy.sh health` and a human live) and unreachable from the LAN. A
remote reader would need a tunnel, which is the honest cost of not building an auth proxy in this slice.

**D3 — Retention as repository configuration, not image default (T2).** `prometheus.yml`, `tempo.yml` and
`datasources.yml` are already installed from `docker/config/`; Loki being the exception is what let an
unbounded retention reach a host disk unnoticed.

**D4 — Conventional alert thresholds, corrected by the operator (T7).** The first alert row uses
`http_server_requests_seconds_count{outcome="SERVER_ERROR"}` above 1% of requests over 5 minutes, p99
above 2s, and `hikaricp_connections_active / hikaricp_connections_max` above 0.8. These are starting
numbers to be adjusted, not a service-level objective, and the record says so where the rule lives.

## Log

**L1 — the user's original request, verbatim**: "genera el odd dee stos cambios"

**L2 — the request that produced the analysis this record specifies, verbatim**: "ok, puedes armar un
analisis de cosas que veas que se tengan que mejorar para la observabilidad"

**L3 — what was measured, on the running dev stack, 2026-10-24.** Every spec above quotes one of these
observations; none is inferred from reading a file alone.

| Observation | Command / output |
|---|---|
| Prometheus scrapes all three targets | `/api/v1/targets?state=active` → `api-gateway up`, `lifecontrol-api up`, `prometheus up` |
| Tempo ingests but cannot read blocks | `GET /api/traces/<id>` → `HTTP 500`, `vParquet2 is not a valid block version`, 71 log lines |
| Tempo runs 3.1.0 and writes vParquet5 | `Starting Tempo version=v3.1.0`; `compacting block version=vParquet5` |
| Loki keeps logs forever | `GET :3100/config` → `retention_period: 0s`, `enforce_retention_period: false` |
| Loki has one application | `GET :3100/loki/api/v1/label/application/values` → `["lifecontrol-api"]` |
| Grafana has no content | `sqlite3 -readonly grafana.db` → `0` dashboards, `0` alert rules |
| Grafana login is real, not anonymous | `:3000/api/dashboards/home` → `401`; `:3000/login` → `200` |
| Prometheus can be killed remotely | `--web.enable-lifecycle --config.file=... --storage.tsdb.retention.time=15d` on a published port |
| Every observability port is published on all interfaces | four `"${*_PORT}:3100/9090/3000"` publishes with no `127.0.0.1:` |
| The API ignores `LOG_LEVEL` | no `logging.level.root` in `life-control-api/src/main/resources`; `<root level="INFO">` |
| Prod sampling ignores the env var | `application-prod.properties:21` = `0.1` vs `.env.prod:66` = `0.01` |
| No business metrics exist | `grep -rl 'MeterRegistry\|Counter\|Timer' life-control-api/src/main/java` → nothing |
| Only one observability suite exists | `docker/scripts/tests/` → `tempo-receivers.test.sh` is the only stack guard |

**L4 — the two decisions the record waited on, both answered on 2026-10-24.** The operator authorized
deleting the dev Tempo store ("1 puedes borrar lo que existe sin problema") and set the log retention at
30 days ("2 dejalo en 30 d"), which is the value T2 writes: 30 days is already the longest range Loki
answers (`max_query_length: 30d1h`), so keeping logs longer only grows the disk without ever making them
queryable.

**L5 — how the work is delivered.** The repository's convention gives every unit its own worktree and
branch, so this record lives on `fix/observability-hardening`
(`~/workspace/LifeControl-worktrees/fix-observability-hardening`, cut from `origin/main` @ `53725ea`) and
the anchor stays a read-only reference. Tasks are committed as reviewable slices with tests and docs
alongside the behaviour; each task's commit is recorded in the table above as it lands.

**L6 — T1 landed, 2026-10-24.** The store was emptied (`docker run --rm -v
…/volumes-dev/tempo/data:/data --entrypoint sh alpine:3 -c 'rm -rf /data/*'`, zero entries left),
Tempo 3.1.0 restarted and `/ready` answered `200`. Evidence after the reset: `docker logs … | awk
'/Starting Tempo/{buf=""} …' | grep -c "vParquet2 is not a valid block version"` → `0`, and the newest
trace read `GET /api/traces/<id>` → `HTTP 200`. No trace has been flushed to a block yet at that point
(`find …/traces -name meta.json | wc -l` → `0`), so the flushed-block read is asserted from now on by
the probe itself rather than by one passing measurement: on a store younger than the probe window,
`deploy.sh health` says so on stderr instead of claiming a verified read.

**L7 — the probe needed a window, and that is the finding worth keeping.** The first version asked Tempo
for its newest trace and read it back. On the broken stack that probe returned `rc=0`: the newest trace
still lives in the ingester, which answered `200` while every flushed block answered `500`. The probe now
asks for a trace between 24h and 2h old — by then it is in a block — and the suite pins that the request
carries `start=`/`end=`, because a regression to the newest-trace read would pass the whole suite over
the defect the suite exists for.

**L8 — slice boundaries.** Slice 1 (T1) is `29a226e` and measures 535 authored changed lines,
above the ~400-line review budget: the probe and its suite are ~200 and this record is the rest,
which travelled with the slice on purpose because the record is the specification the reviewer reads
against it. The remaining slices keep their code under the budget, and T2+T3 and T4+T5 are delivered
as two independent slices because their files are disjoint (`docker/**` and the two Java modules).

**L9 — one spec clause was wrong, and it is corrected here instead of worked around.** S3 as first
written required that `GET /config` stop reporting `enforce_retention_period: false`. That field belongs
to the query engine, not to retention, and the switch it resembles (`limits_config.
enforce_retention_period`) was removed in Loki 2.6: declaring it under the pinned 3.7.8 fails the parse
with `failed parsing config: line 56: field enforce_retention_period not found in type
validation.plain`, so the container would never start. Enforcement is asserted by what the image
actually does — `retention_period: 30d` under `limits_config`, `retention_enabled: true` and
`delete_request_store: filesystem` under `compactor` — measured on a throwaway container of the pinned
image (`/ready` 200; `/config` printing all three). S3 also said the file would be installed by
`setup-env.sh` like the other configs; it is mounted from the repository instead, and the suite fails if
`setup-env.sh` ever copies it.

**L10 — slice 2 (T2+T3) was delivered by a bounded worker and verified by the parent.** Parent-side
checks beyond the worker's report: the new config parses against the pinned image
(`docker run --rm -v …/loki.yml:/etc/loki/config.yml:ro grafana/loki:3.7.8 -verify-config` →
`msg="config is valid"`, exit 0); the rendered project publishes five mappings and all five carry
`host_ip: 127.0.0.1` (loki 3100, prometheus 9090, tempo 3110 and 9411, grafana 3000) with published and
target ports unchanged; and all 12 suites under `docker/scripts/tests/` pass. The running dev containers
still hold the previous bindings until the next `deploy.sh <env> up`, which is the operator's call — the
change is rendered, not applied.

**L11 — one instruction in T4 could not be obeyed as written, and the parent resolved it instead of
recording a gap.** The delegated task asked that `loki.timeout` be wired into the appender. That is not
expressible: loki4j 1.5.2 exposes the batch timeout as `setBatchTimeoutMs(long)` while the gateway
declared `loki.timeout` as a Duration (`10s`/`12s`/`15s`), and `<batchTimeoutMs>${lokiTimeout}</batchTimeoutMs>`
logged `WARN … Failed to set property [batchTimeoutMs] to value "10s". Conversion to type [long] failed`
and silently kept the library default. The property was therefore renamed to `loki.batchTimeoutMs`
(fed by `LOKI_TIMEOUT_MS`, values `10000`/`12000`/`15000`) and wired; the dead `loki.timeout`/`LOKI_TIMEOUT`
keys are gone, and the test gained two assertions for the new binding, whose RED was measured by removing
the wiring again (`api-gateway logback-spring.xml Loki appender > … FAILED`) before restoring it.

The same pass removed a second piece of dead configuration rather than shipping it inert: the gateway's
`logging.pattern.console=${LOG_PATTERN:…}` fed Boot's default appender, which the shipped file replaces,
and its default carried neither the level nor the trace ids. The console format is now the shipped
file's, `logging.pattern.correlation` (mirroring the API's) supplies the ids, and the property is gone
with a comment saying why — an env-var knob that silently does nothing is the defect this whole unit
exists to remove, not something to leave behind for the next reader.

**L12 — slice 3 (T4+T5) delivered by a bounded worker, finished and verified by the parent.** The worker
reported T5 complete and T4 green except the timeout binding; it asked instead of guessing, which is the
behaviour the delegation asked for. Parent-side verification: the gateway suite's two classes pass
(`GatewayLokiAppenderConfigurationTest` 2 tests, plus the four pre-existing gateway classes), the API's
`ObservabilityEnvironmentOverrideTest` passes (3 tests), and the gateway has no Spotless plugin — there
is no formatting gate in that module, which is why only the API ran `spotlessCheck`.

**L13 — the Grafana block was not inert, and that changed what S8 was for.** The record called those keys
dead because the base compose has no `environment:` block for `grafana`. Half true: the production override
does forward two of them —
`GF_AUTH_ANONYMOUS_ENABLED: ${GF_AUTH_ANONYMOUS_ENABLED:-false}` and
`GF_AUTH_DISABLE_LOGIN_FORM: ${GF_AUTH_DISABLE_LOGIN_FORM:-false}` (`docker/docker-compose.prod.yml:186-188`).
A production env file materialised from the template would have carried `true` into both, i.e. an anonymous
Grafana whose anonymous role the template set to `Admin`. S8 stopped being hygiene and became a security
fix: the block is gone, and the compose default (`false`) is now the only source for those two keys. They
are pinned in `env-defaults.test.sh`'s compose-internal list so they cannot return as operator knobs, and
`image-pins.test.sh` fails if any of the three reappears in the template.

**L14 — two of the four env files cannot be committed, and the suite says so instead of pretending.** The
parity check this unit added can only compare materialised files, and `docker/.env*` is gitignored: in this
worktree none of `.env.dev`, `.env.staging` or `.env.prod` exists. The alignment is therefore an operator
edit and was made on the machine that runs the stack: the three `GF_AUTH_*` lines left all four local env
files, and `.env.staging`/`.env.prod` moved from `grafana/grafana:10.2.0` to `13.2.4`. `GF_AUTH_BASIC_ENABLED`
stays in `.env.prod`: that one *is* forwarded as a real knob and keeps its compose default. `setup-env.sh`
never rewrites an existing file, so the template fix reaches a machine only through this kind of edit — which
is exactly why the parity check, not the edit, is what ships.

**L15 — the worker could not touch the env file, and that was correct behaviour.** The bounded writer hit
the harness safety policy on `docker/.env*` (read and edit both refused) and reported the block instead of
routing around it. Its two follow-up findings were both real and its measurements were reproduced by the
parent: removing the block breaks `env-defaults.test.sh` (the allowlist must drop to seven and the two
forwarded keys must become compose-internal) and `worker-dev-default.test.sh` (its loop asserted the key
stayed in the template). Both suites were updated in this slice, and all 12 suites under
`docker/scripts/tests/` pass: `image-pins` 45, `env-defaults` 242, `worker-dev-default` 6.
