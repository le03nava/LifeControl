# ODD feature: otlp-tracing-migration

**Repository**: LifeControl — `life-control-api` + `api-gateway` (deps and tracing config),
`docker/docker-compose.yml` (OTLP endpoint).
**Status**: delivered — T1-T8 verified; code committed on `fix/otlp-tracing-migration`
**Created**: 2026-09-22
**Branch**: `fix/otlp-tracing-migration`, cut from `main` @ `d7317f6`.

## Objective

Make distributed tracing actually work. Today both services ship a Zipkin exporter pointed at
Spring Boot's default `http://localhost:9411/api/v2/spans`, where nothing listens inside the
container, so every span is dropped with a `ConnectException`. Migrate to the OpenTelemetry
bridge with OTLP export to the Tempo receiver that is already running, and fix the API's tracing
properties, which currently ignore the environment variables the compose files pass it.

## Evidence of the defect (measured, not inferred)

| Fact | Evidence |
|---|---|
| Nothing listens on 9411 inside the API container | `ss -ltnp` inside `lifecontrol-dev-lifecontrol-api`: only `127.0.0.11:42497` (Docker DNS) and `:::8082` (java) |
| Spans are being dropped now | API logs: `Dropped 5 spans due to ConnectException()`, 10 spans total; gateway logs: 90 matching lines |
| The exporter target is the Boot default, not configured anywhere | `ZipkinHttpClientSender{http://localhost:9411/api/v2/spans}` in logs; no `management.zipkin.tracing.endpoint` in any property file or compose env |
| Tempo is alive and has both receivers enabled | `docker/config/tempo/tempo.yml` declares `otlp.protocols.{grpc,http}` and `zipkin`; `GET localhost:3110/ready` -> `ready` |
| Tempo has received **zero** traces ever | `GET localhost:3110/api/search?limit=3` -> `{"traces":[]}` |
| Both OTLP and Zipkin receivers answer inside the compose network | from the web-app container: `GET tempo:9411/` -> 400 (Zipkin alive), `POST tempo:4318/v1/traces` -> 415 (OTLP HTTP alive) |
| No application code references Brave, Zipkin or Micrometer Tracing | `grep -rnE "io\.micrometer\.tracing\|brave\.\|zipkin" --include=*.java` -> no hits |

## Decisions made (by the user)

1. **Migrate to OTLP** rather than just pointing the Zipkin exporter at Tempo. Zipkin is legacy
   in the Grafana stack; Tempo already exposes the OTLP receiver.
2. **Include the tracing-property fix in the same pass** (the user's "renombre"), because the
   API's sampling bug is more expensive in prod than the dropped spans.

## Verified technical constraints

| Constraint | Source |
|---|---|
| Spring Boot is **3.5.16**, where the namespace is `management.otlp.tracing.*` | `life-control-api/build.gradle:3`; `OtlpTracingProperties` is `@ConfigurationProperties("management.otlp.tracing")` in the 3.5 API docs |
| The `management.opentelemetry.tracing.export.otlp.*` namespace and `spring-boot-starter-opentelemetry` belong to Boot **4.0**, not 3.5 | current docs page vs 3.5.11 API docs; must not be used here |
| Boot 3.5.16's BOM manages `micrometer-tracing-bridge-otel` but **not** `micrometer-tracing-bridge-otlp` | `spring-boot-dependencies-3.5.16.pom` artifactId scan |
| Boot's BOM imports `opentelemetry-bom` **1.49.0**, so `io.opentelemetry:opentelemetry-exporter-otlp` needs no explicit version | same POM; `<opentelemetry.version>1.49.0</opentelemetry.version>` |
| Boot's OTLP HTTP exporter is the default transport and expects the full path `.../v1/traces` | `OtlpTracingAutoConfiguration` (3.5.x) uses `OtlpHttpSpanExporter`; default endpoint `http://localhost:4318/v1/traces` |
| **The gateway already parameterizes its tracing properties; the API does not** | `api-gateway/src/main/resources/application.properties:45-46` vs `life-control-api/src/main/resources/application.properties:41` |
| Per-environment `.env` files are **untracked**; only `.env.template` is versioned | `git ls-files docker/.env.dev docker/.env.prod docker/.env.staging` -> only `.env.template` |
| Compose merges `environment` entries by key across base + override files | Compose merge semantics; the prod override only re-declares the gateway's `TRACING_*` |
| API CI gate is `spotlessCheck spotbugsMain` then `test` | `.github/workflows/api-ci.yml` |
| The two services run **different Spring Boot versions**: API 3.5.16, gateway **3.4.0** | `life-control-api/build.gradle:3`, `api-gateway/build.gradle:4` |
| Boot 3.4.0's own BOM manages `micrometer-tracing` 1.4.0 and `opentelemetry` 1.43.0, so the gateway's older resolution is correct for its Boot version, not a BOM conflict | `spring-boot-dependencies-3.4.0.pom`; `dependencyInsight` -> `selected by rule` |

## Corrections to the initial diagnosis

- **The dead-env-var bug is API-only.** The gateway reads `TRACING_ENABLED` and
  `TRACING_SAMPLING_PROBABILITY` through property placeholders, so in prod the gateway really
  does sample at 0.01. Only the API ignores them and samples at a hardcoded 1.0 in every
  environment.
- **No env-var rename is needed.** The gateway already proves the repo convention: bind the
  property to the existing environment variable with a placeholder. The fix is to make the API
  match, not to rename anything in compose or in the `.env` files. This also keeps the change
  out of the untracked `.env.*` files.

## Tasks

- [x] T1 — Replace the Brave/Zipkin dependencies with the OTel bridge + OTLP exporter in both `build.gradle`
- [x] T2 — Bind the API's tracing properties to the existing env vars and add the OTLP endpoint property to both services
- [x] T3 — Add the in-network `OTLP_TRACING_ENDPOINT` to the base compose for both services
- [x] T4 — Verify dependency resolution: Brave and Zipkin gone, OTel + OTLP present and BOM-versioned
- [x] T5 — Build both JARs and pass the API CI gate (`spotlessCheck`, `spotbugsMain`)
- [x] T6 — Rebuild + redeploy dev and prove traces land in Tempo with zero dropped spans
- [x] T7 — Re-verify against the new `main` (`d7317f6`): the gateway test suite that gained a CI gate in the meantime, and the API test suite the original pass had skipped
- [x] T8 — Commit the change as a work unit and open the PR

## Out of scope

- Removing the now-unused `TEMPO_ZIPKIN_PORT` mapping (harmless; other tooling may use it).
- Exposing Tempo's OTLP port 4318 to the host (not needed: the apps talk to it in-network).
- The `k8s/` manifests, which have their own Tempo wiring and are not part of the compose stack.

## Known behavioral consequence

Switching the bridge from Brave to OpenTelemetry changes context propagation from **B3** to
**W3C `traceparent`**. Both services are migrated together, so gateway -> API propagation stays
consistent, but any external client that injects B3 headers will no longer be honored.

## Verification results (measured after the change)

| Check | Result |
|---|---|
| Dependency resolution, API | `micrometer-tracing-bridge-otel -> 1.5.12`, `opentelemetry-exporter-otlp -> 1.49.0`; zero brave, zero zipkin |
| Dependency resolution, gateway | `micrometer-tracing-bridge-otel -> 1.4.0`, `opentelemetry-exporter-otlp -> 1.43.0`; zero brave, zero zipkin |
| API CI gate | `./gradlew spotlessCheck spotbugsMain --no-daemon` -> BUILD SUCCESSFUL |
| Builds | `bootJar` BUILD SUCCESSFUL in both modules |
| Deploy | `./docker/scripts/deploy.sh dev start` -> EXIT=0, `All services healthy!`, HEAD stable at `455dc15` before and after |
| Migrations | none in the diff; V13/V14 already applied, no new destructive step |
| Dropped spans, API | **0** (was 10) |
| Dropped spans, gateway | **0** (was 90 matching lines) |
| Tempo traces | **12+** stored (was `{"traces":[]}` — zero, ever) |
| Cross-service propagation | Trace `35febbed13dffa32f2a4a0f5c370e4f3` contains `api-gateway` SERVER `http get /api/products/**` (200), `api-gateway` CLIENT `http get -> http://lifecontrol-api:8082/ap...` (200), and `lifecontrol-api` SERVER `http get /api/products` (200) under one trace ID |
| Effective env in containers | `OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces`, `TRACING_ENABLED=true`, `TRACING_SAMPLING_PROBABILITY=0.5` in both |

### Outcome
The migration is proven end to end: spans reach Tempo, both services report under the same trace
for a gateway-routed request, and nothing is dropped. The API now honors the tracing environment
variables instead of hardcoding 100% sampling, which also fixes prod, where the intended value was
0.01.

### Follow-ups (not done here)
- The gateway runs Spring Boot 3.4.0 while the API runs 3.5.16. Pre-existing, unrelated to
  tracing, but it is why the two services resolve different OTel versions (1.4.0 / 1.43.0 versus
  1.5.12 / 1.49.0). Worth its own change.
- `TEMPO_ZIPKIN_PORT` is now unused by the applications; kept for other tooling.

## Re-verification on the delivery branch (2026-09-22, later)

The gates above were measured against `main` @ `455dc15`. `main` then advanced to `d7317f6`
(PR #152, gateway route coverage), which added `.github/workflows/gateway-ci.yml` — a gateway test
suite that did not exist during the original pass, and one that now runs because this change touches
`api-gateway/**`. The 5 changed files do not overlap with that merge
(`git diff --name-only 455dc15 d7317f6` lists 4 files, none of them here), so the change was
re-verified at the new HEAD before commit:

| Re-check | Result |
|---|---|
| `api-gateway ./gradlew cleanTest test --no-daemon` (the new CI gate, forced rerun) | BUILD SUCCESSFUL, **7 tests / 0 failures / 0 errors**; fresh results at 21:09, not the cached 14:32 report |
| `life-control-api ./gradlew spotlessCheck spotbugsMain --no-daemon` | BUILD SUCCESSFUL (up to date: the API's main sources are byte-identical to the last green analysis) |
| `life-control-api ./gradlew test --no-daemon` | BUILD SUCCESSFUL, **2054 tests / 0 failures / 0 errors** — this gate had **not** been run in the original pass, which recorded only static analysis |
| Dependency resolution, both modules (`runtimeClasspath`) | zero `brave`, zero `zipkin`; `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` present, BOM-managed, no version declared in `build.gradle` |
| Live dev stack re-checked | both containers still carry `OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces`; zero `dropped` matches in either container log; the gateway log names `OtlpHttpSpanExporter` and never the Zipkin sender; Tempo returns traces whose root service is `api-gateway` **and** `lifecontrol-api` |

## Delivery

- Branch `fix/otlp-tracing-migration`, cut from `main` @ `d7317f6`.
- Commit 1 — `6e37181` `fix(tracing): migrate both services to OTLP export and honor tracing env vars` (the 5 files above).
- Commit 2 — this document, recording that work unit's identity.
- PR opened from that branch against `main`.

Only 10 insertions and 5 deletions of source, config and compose; the rest of this branch is this
record.
