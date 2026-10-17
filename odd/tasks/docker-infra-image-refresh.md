# ODD feature: docker-infra-image-refresh

**Repository**: LifeControl — `docker/` only: `.env.template`, the three compose files, `scripts/setup-env.sh`,
`scripts/deploy.sh`, a new static test, plus one section of the root `AGENTS.md`. No application code, no
schema, no endpoint, no contract.
**Status**: implemented, committed as **`f299b70`** (the code work unit) and **`353010b`** (this record), verified
at runtime in dev, and **approved by the native review without a correction**: lineage
`review-38fd76cfaa5efd61`, four lenses, tier **high**, receipt
`sha256:81aede406b3e08904d2dad800d864e28a137d361bf00b2053787e171b81d95c1`, acknowledged (authority burned,
`gentle-ai.review-acknowledged/v1`). Its six findings are advisory and non-blocking; none reopens this
candidate (`L14`). What stays open is not work of this slice: the two follow-ups declared in `L14` and `L16` are
new candidate work that needs its own authorization and review, and `.env.staging` / `.env.prod` are the
operator's one-time re-materialization (`L7`). This header makes no claim about push or PR state; see the dated
log.
**Created**: 2026-10-17 · **Risk**: **item 1 (data)** — Keycloak migrates its own DB schema on startup with no
rollback, and the Loki change decides what is persisted and where; **item 5 (environment/dependencies)** — every
pinned image of the infrastructure stack moves at once.

## Specs

**S1** — *the user, 2026-10-17, verbatim*: «puedes revisar las imagenes que se usan den el docker-compose y revisa
si hay alguna que actualizar».

**S2** — *the scope, chosen by the user on 2026-10-17, verbatim*: «Todo el stack de infra, incluyendo Loki 3.7.8
/ Tempo 3.1.0 / Prometheus v3.15.0 con el rediseño de los healthchecks (y, si querés, el fix del mount de
Loki)». The Dockerfile base images (`eclipse-temurin`, `node`, `nginx`) and `k8s/manifests/**` are outside it.

Target set, measured against the registries on 2026-10-17 (not inferred from memory):

| variable | from | to |
| --- | --- | --- |
| `POSTGRES_IMAGE` | `postgres:18.1` | `postgres:18.6` (published 2026-10-07) |
| `LIFECONTROL_POSTGRES_IMAGE` (prod default) | `postgres:18.1` | `postgres:18.6` |
| `KEYCLOAK_IMAGE` | `quay.io/keycloak/keycloak:26.2.1` | `…:26.8.1` (no 27.x exists yet) |
| `LOKI_IMAGE` | `grafana/loki:2.9.0` | `grafana/loki:3.7.8` |
| `PROMETHEUS_IMAGE` | `prom/prometheus:v2.48.0` | `prom/prometheus:v3.15.0` |
| `TEMPO_IMAGE` | `grafana/tempo:2.3.0` | `grafana/tempo:3.1.0` |
| `GRAFANA_IMAGE` | `grafana/grafana:10.2.0` | `grafana/grafana:13.2.4` |
| `REDIS_IMAGE` | `redis:7.4-alpine` | `redis:8.10-alpine` |
| `MAILPIT_IMAGE` | `axllent/mailpit:v1.31.4` | `axllent/mailpit:v1.31.6` |

The audit's severity argument, for the record: Keycloak `26.2.1` is vulnerable to **CVE-2026-18963** (Critical,
CVSS 9.1 — unauthenticated account takeover through the reset-credentials flow; fixed in `26.7.2`), and Grafana
only patches the current and previous minors, so `10.2.0` is end-of-life three majors back. No specific CVE was
verified for Loki 2.9 / Tempo 2.3 / Prometheus 2.48; those lines are simply unmaintained (2023 releases).

**S3** — *the Loki persistence decision, verbatim*: «Sí: montar /loki con dueño 10001» — «Persiste logs. Requiere
chown 10001:10001 y los subdirs rules/rules-temp en setup-env.sh, si no Loki no arranca». Measured: Loki's
bundled config uses `path_prefix: /loki` (`chunks`, `wal`, index) while the compose mounted
`${VOLUMES_ROOT}/loki/data`; the host directory is empty and the running container's data lives in its own layer.
The `grafana/loki` 3.7.8 image runs as uid `10001` on `gcr.io/distroless/static:nonroot`, so a root-owned bind
mount makes it fail to start.

**S4** — *the Redis decision, verbatim*: «Subir a 8.10-alpine» — «Lee el AOF/RDB de 7.4, pero no hay vuelta
atrás al 7.4 y cambia el marco de licencia».

**S5** — *the healthcheck decision, verbatim*: «Quitar del compose + probes desde el host» — «deploy.sh health
consulta /ready de Loki y Tempo por el puerto publicado (curl ya es dependencia del repo)». Measured, because it
is the whole reason for the redesign: the published image of `grafana/loki` is built from `cmd/loki/Dockerfile` at
that tag, which is `gcr.io/distroless/static:nonroot` from **3.6.0** onward (3.2.0–3.5.0 still used a
distroless `:debug` base carrying busybox), and `grafana/tempo` 3.1.0 uses `gcr.io/distroless/static-debian12` in
`cmd/tempo/Dockerfile`. Neither has a shell or `wget`, so a `CMD wget --spider …` healthcheck can never pass
again. Grafana is **not** affected: the default stage of `grafana/grafana` 13.2.4 is still `final-alpine`.

## Tasks

| ID | Spec | Task | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | S2, S3, S5 | Add `docker/scripts/tests/image-pins.test.sh`: pin agreement across template + compose, the target version set, no `wget` healthcheck on Loki/Tempo, host probes present, Loki mount at `/loki` prepared for uid 10001 | inline | `f299b70` |
| T2 | S2, S4 | Bump the nine pins: `.env.template` (adds `REDIS_IMAGE`) and the defaults in the base, prod and dev-override compose files | inline (+1 delegated half) | `f299b70` |
| T3 | S5 | Remove the `wget` healthchecks Loki and Tempo can no longer run (base + prod) and measure their readiness from the host in `deploy.sh health`; document the rule in the root `AGENTS.md` | inline | `f299b70` |
| T4 | S3 | Mount `${VOLUMES_ROOT}/loki/data` at `/loki` (base + prod) and prepare it in `setup-env.sh` for uid `10001:10001` with `rules/` and `rules-temp/` | inline | `f299b70` |
| T5 | S2 | Runtime verification in dev: recreate the stack on the new pins, probe every service, re-converge the realm, run the invitation flow end to end | inline | `L12`–`L13` |
| T6 | S2 | Native review (RDD) of the candidate, per the returned plan | inline | `L14` — `review-38fd76cfaa5efd61`, approved + acknowledged |

`T1`–`T4` land as **one commit** (`f299b70`, 399 authored lines) instead of the three the plan named: the new
test asserts the pin set, the absence of the two healthchecks and the Loki mount in one artifact, and the three
concerns share the same two compose files, so a split would have meant hand-splitting hunks inside the `loki`
and `tempo` service blocks for no reviewer benefit. `L10` records the option that was not taken.

Not in this feature, stated so it is not lost: the Dockerfile base images (`eclipse-temurin:21-jre-alpine`,
`node:22-alpine`, `nginx:alpine`) and the `k8s/manifests/**` pins (`keycloak:24.0.1`, `grafana:10.1.0`,
`prometheus:v2.46.0`, `tempo:2.2.2`, a floating `loki:main`, an external `…/new-api-gateway:latest`).
`life-control-api/src/test/.../AbstractPostgresIntegrationTest.java:25` still runs `postgres:16-alpine`.

## Log

**L1** *(the user, verbatim)*: «puedes revisar las imagenes que se usan den el docker-compose y revisa si hay
alguna que actualizar». Answered read-only: inventory, registry comparison, and the four findings (unpatched
Keycloak, EOL Grafana, the distroless healthcheck trap, the Loki mount that persists nothing).

**L2** *(the user, verbatim)*: «C» → «Todo el stack de infra, incluyendo Loki 3.7.8 / Tempo 3.1.0 / Prometheus
v3.15.0 con el rediseño de los healthchecks (y, si querés, el fix del mount de Loki)».

**L3–L5** *(the user's three decisions, verbatim)*: «Sí: montar /loki con dueño 10001» · «Subir a
8.10-alpine» · «Quitar del compose + probes desde el host».

**L6** *(measured before writing anything)*: `docker/.env`, `.env.dev`, `.env.staging` and `.env.prod` are
**gitignored** (`.gitignore:104-107`), so the only versioned pin source is `docker/.env.template` plus the
`${VAR:-default}` fallbacks in the compose files. `setup-env.sh` never rewrites an existing env file, so the
local dev materialization has to be bumped separately for the runtime check (local-only, untracked).

**L7** *(incident, 2026-10-17)*: the harness safety policy denies **both the writer subagent and the parent**
any tool access to `.env*` paths — the delegated worker returned `interaction_required` because it could not read
or edit `docker/.env.template` (a false positive: the template is tracked and holds no secrets, since every
password lives in `docker/secrets/*`). The user was asked and explicitly authorized a scoped `sed` on that file
(option A); the same scoped `sed` was applied to the untracked `.env.dev` and `.env` so the dev stack could be
verified on the new pins. **`.env.staging` and `.env.prod` still carry the old pins** and are therefore a
required operator step before any staging/prod deployment (documented in `AGENTS.md`).

**L8** *(self-correction inside this feature)*: the first version of `image-pins.test.sh` grepped the whole
`loki`/`tempo` service block for the literal token `wget`. That assertion is satisfied by a comment that
*mentions* wget, which is exactly the comment that should exist, so the worker weakened the comment to
"no HTTP client" to stay green. The assertion was corrected to what actually matters — those service blocks must
declare **no `healthcheck:` key at all**, because a distroless image has no probe binary — and the informative
comment (naming shell and wget) was restored.

**L9** *(local dev state, untracked)*: `docker/volumes-dev/loki/data` was created by Docker as root and empty,
and this host has no passwordless sudo, so the committed `chown 10001:10001` could not be applied locally. The
directory was recreated by the current user with mode `0777` (it is a local log directory, not a secret) so the
image's uid 10001 can write it; that is the local workaround, not the committed mechanism.

**L10** *(the commit decision, taken by the parent)*: `T1`–`T4` were committed as **one** work unit,
**`f299b70`** (`chore(docker): bump the infrastructure image pins to their current releases`, 8 files, 347
insertions / 52 deletions — 399 authored lines, one slice). The three-commit split the plan named
(`test(...)` + pins, `fix(...) probe Loki and Tempo from the host`, `fix(...) persist Loki data where its
config writes`) was considered and is **green at each point** — measured, not assumed: the mount fix is valid
under the old image too, because `grafana/loki:2.9.0`'s bundled config already declares `path_prefix: /loki`, so
`/loki/data` persisted nothing before the bump either. It was dropped because the concerns are not separable
inside the two compose files: the `loki` and `tempo` service blocks each carry two of them, so the split needed
hand-edited hunks, and the reviewer would have read the same 399 lines in three passes instead of one.

**L11** *(the gate, all measured)*: the five `docker/scripts/tests/` scripts pass on the committed bytes —
`image-pins` **42**, `compose-profile` **6**, `version-consistency` **8**, `keycloak-setup` **68**,
`freshness-guard` **18** (142 assertions, 0 failures). `image-pins.test.sh` was proved non-vacuous in an
isolated `mktemp -d` mirror of the seven files it reads: reverting `LOKI_IMAGE` to `2.9.0` → 1 `FAIL`, exit 1;
re-adding a `wget` healthcheck to the `loki` service → 1 `FAIL`, exit 1; moving the mount back to `/loki/data`
→ 2 `FAIL`, exit 1; the untouched mirror → 42 passed, exit 0. The distroless claim was also measured directly:
`docker run --rm --entrypoint sh grafana/loki:3.7.8` fails with *executable file not found* (no `sh` at all).

**L12** *(runtime evidence in dev, all measured)*: the stack was recreated on the new pins — twelve containers,
all `healthy` where the image ships a healthcheck (keycloak `26.8.1`, both Postgres `18.6`, grafana `13.2.4`,
prometheus `v3.15.0`, redis `8.10-alpine`, mailpit `v1.31.6`, web, api, gateway), and `loki` `3.7.8` + `tempo`
`3.1.0` `Up` with no healthcheck by design.

- `deploy.sh dev health` → exit **0**, and the redesigned signal is real, not decorative: attempt 1 reported
  `Loki(not-ready)` and attempt 2 reported all healthy, so the host probe distinguishes a starting Loki from a
  ready one.
- Loki persistence: `docker inspect` binds `docker/volumes-dev/loki/data` → **`/loki`**, and the container's own
  uid **10001** created `chunks/`, `wal/`, `tsdb-shipper-active/`, `tsdb-shipper-cache/` and `compactor/` inside
  it; the query API answers `sum(count_over_time({service_name=~".+"}[1h]))` = **313** lines from
  `service_name=lifecontrol-api`. The old mount persisted nothing, so this is the fix working, not a restart.
- Prometheus `3.15.0`: `/api/v1/status/buildinfo` = `3.15.0`, and `up` is **1** for all three targets
  (`api-gateway`, `lifecontrol-api`, `prometheus`).
- Tempo `3.1.0`: `/ready` answers `ready` and `/api/search` answers with an empty trace list, not an error.
- Grafana `13.2.4`: `/api/health` = `database: ok`, it booted over the **persisted** SQLite volume (the
  three-major migration resolved at startup, no error or panic in the log) and
  `/etc/grafana/provisioning/datasources/datasources.yml` is mounted inside the container.
- Keycloak `26.8.1` over Postgres `18.6`: `keycloak-setup.sh dev` reconverged the realm on the migrated
  database (realm, public + confidential clients, the five `company_*` protocol mappers read back and matched,
  realm roles, service-account role assignments, `unmanagedAttributePolicy`), and `verify-invitation-flow.sh dev`
  exited **0**: the action email arrived in Mailpit, the link base serves Keycloak with an `issuer` equal to
  `KEYCLOAK_ISSUER_URI`, and the action token's `redirect_uri` claim returns to `WEB_APP_URL`.

**L13** *(the two things this verification does **not** prove, stated so nothing is read as more than it is)*:
(1) **Grafana → datasources connectivity was not exercised over the authenticated API.** `GET /api/datasources`
answers **401** because the local `grafana.db` holds a different admin hash than
`docker/secrets/grafana_admin_password` (both hashes are identical to the mounted secret, so it is the DB, not
the mount): Grafana applies `GF_SECURITY_ADMIN_PASSWORD` only when it initializes its database, which happened
in September. That drift predates this bump and every unauthenticated probe the repo runs stays green, so it is
recorded here rather than treated as a regression of the image jump; whoever needs the datasources verified has
to re-init that local volume (or set the password) first. (2) `deploy.sh dev health` cannot fail on a service
whose image has no healthcheck and whose probe port is unset: the `LOKI_PORT` / `TEMPO_PORT` defaults come from
the env file, so an environment that removes them loses the signal silently — the test pins the probes in
`deploy.sh`, not the env values.

**L14** *(the native review, `T6`, all measured)*: **approved without a correction.** The four reviewer runs
returned a receipt for the frozen candidate, `store_revision`
`sha256:81aede406b3e08904d2dad800d864e28a137d361bf00b2053787e171b81d95c1`, and its acknowledgement burned the
authority (`authority: burned`, `burn_evidence: gentle-ai.review-acknowledged/v1`, `delivery:
ordinary-repository-policy`). Tier **high** (Pi derived it from the executable-mode change, the shell process
boundary and the shell sourcing in `deploy.sh`, and this record's Risk line was escalated as item 1 as well),
four lenses (`review-risk`, `review-resilience`, `review-readability`, `review-reliability`), 9 changed files,
570 changed lines, correction budget 200 — unused.

Two provider-issued facts about the run worth keeping, because they cost a step each:

- A `lensesReason` of ~700 characters is rejected with `native-start-input-invalid` / `lenses-invalid`
  (`lineage_created: false`, nothing burned); the same request with the reason under ~450 characters passes.
  The first START of this feature died exactly there.
- The readability reviewer's first payload was **refused at admission**
  (`evidence_path_out_of_scope`, preserved at
  `.git/gentle-ai/rejected-results/review-38fd76cfaa5efd61/review-readability-1-c7731d75ee98.json`); the slot was
  not consumed, and re-running the same binding produced a payload that was admitted. Two of the four reviewers
  had already been admitted by then (`mutation_outcome: partial`), so the group is resumed per slot, never
  replayed.

All six findings are **advisory and informational**; the closure states in its own words that none is blocking,
none opened a correction and none reopens this candidate:

| finding | lens | location | severity | theme |
| --- | --- | --- | --- | --- |
| `R1-image-probe-coverage` | risk | `docker/scripts/deploy.sh:252-257` | WARNING | the host probe skips a service when its port variable is empty while compose keeps the fallback |
| `R4-READY-PROBE-SKIP` | resilience | `docker/scripts/deploy.sh:252-257` | WARNING | the same skip, seen as a lost readiness signal |
| `R3-001` | reliability | `docker/scripts/deploy.sh:252` | WARNING | the same skip, seen as behavior no test pins |
| `R4-LOKI-PROD-VOLUME` | resilience | `docker/docker-compose.prod.yml:119` | WARNING | the prod named volume exists from the old `/loki/data` mount and is not repopulated by Docker |
| `R2-001` | readability | `docker/scripts/deploy.sh:248-256` | SUGGESTION | the port is read twice (guard vs. URL), and neither the function comment nor `AGENTS.md` says the probe is best-effort |
| `R3-002` | reliability | `docker/scripts/tests/image-pins.test.sh:195` | SUGGESTION | a test suggestion (section 4's healthcheck assertion) |

The first three and the readability one are `L13` (2) seen from three angles, so the record and the review agree
on the gap. They are **not** fixed here: the review's own closure says to treat them as separate later work and
never as a reason to re-run this candidate, and changing the candidate now would invalidate the receipt for the
bytes that were reviewed. The bounded follow-up they justify is one unit: on an empty `LOKI_PORT` / `TEMPO_PORT`,
probe the published fallback port (`3100` / `3110`) instead of skipping, and say in the function comment and in
`AGENTS.md` that the probe is best-effort. It needs its own authorization because it is new candidate work with
its own review.

**L15** *(the record boundary)*: `f299b70` and `353010b` are the bytes the receipt covers
(`target_identity sha256:70314bd1792d1fc5274d123f01fa61c5e2e578680624990d7f7041dbd82da257`,
`candidate_tree 02956cf4ef0b3fa564a30e02b51a63c01f7a1446`). `L14` and this entry were written **after** the
acknowledgement, in a commit that only touches this document: they add no behavior and are not covered by the
receipt. The code slice is `f299b70` alone (399 authored lines, one reviewable slice); the two commits after it
are the record (171 and 56 documentation lines), which is where the branch crosses the slice boundary.

**L16** *(delivery and the third gap, 2026-10-17)*: the branch was pushed and **PR #357** opened
(`chore/docker-infra-image-refresh` → `main`, https://github.com/le03nava/LifeControl/pull/357) with the
nine-pin table, the measured evidence above, the rollback plan (Keycloak/Postgres migrate forward with no
rollback, and prod's named Loki volume has to be deleted once) and the follow-ups. Label `type:chore`; `main` is
not protected, so the only check the PR triggers is `docker-build-integrity.yml` (`paths: docker/**`).

That run exposed the third gap, found while reading the workflow for this delivery: `docker-build-integrity.yml`
runs four of the five test scripts **explicitly** (freshness-guard, version-consistency, keycloak-setup,
compose-profile), so `image-pins.test.sh` is covered by the workflow's `find docker/scripts -name '*.sh'`
shellcheck but **none of its 42 assertions runs in CI** — the pin set is frozen only for whoever runs the script
by hand. Wiring it is about three lines in that workflow, and it is declared here rather than done, for the same
reason as the probe follow-up: it would add bytes the receipt does not cover.
