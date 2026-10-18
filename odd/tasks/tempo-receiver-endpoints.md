# The Tempo receivers answer again, and a test catches the drift

**Repository**: LifeControl — `docker/config/tempo/tempo.yml` (+ `docker/scripts/tests/`), issue **#425**
**Status**: implemented — the config, its drift suite and the live verification are in commit `255dcb6` on `fix/tempo-receiver-endpoints`; what remains is the delivery of this unit as its own PR. This header makes no claim about push or PR state.
**Created**: 2026-10-18 · **Risk**: medium — the change is confined to a dev/infra config file, but its failure mode is silent (nothing in the repo watched the receivers) and it touches the delivery workflow

## Specs

Verbatim from issue **#425** (`bug`, `status:approved`).

**S1** — From another container on the compose network, the OTLP HTTP receiver is alive again: `docker exec lifecontrol-dev-life-control-app-angular wget -q -O- --post-data="" --header="Content-Type: application/json" http://tempo:4318/v1/traces` no longer prints `Connection refused` and answers with the receiver's response (415 on that empty body, as recorded before the bump).

**S2** — The Zipkin receiver's expected state is explicit: either `docker exec lifecontrol-dev-life-control-app-angular wget -O- http://tempo:9411/` answers 400 as before, or `docker/config/tempo/tempo.yml` no longer declares `zipkin` and the record states why.

**S3** — `docker logs lifecontrol-dev-tempo | grep -E 'Starting (HTTP|GRPC) server'` shows the OTLP receivers bound to `0.0.0.0` (or `[::]`), not `127.0.0.1`.

**S4** — After a stack restart with the fix: `docker logs --since 10m lifecontrol-dev-lifecontrol-api | grep -c 'Failed to export spans'` prints 0, and the same for `lifecontrol-dev-api-gateway`; a request through the gateway produces a trace in Tempo whose root service is `api-gateway` and that includes `lifecontrol-api`.

**S5** — A test under `docker/scripts/tests/` fails when a receiver in `docker/config/tempo/tempo.yml` is left without an explicit endpoint, so the drift is caught by a command rather than by reading logs.

**S6** — Nothing else about tracing changes: the exporter endpoints, the sampling probability and the transports stay as they are, and `docker/scripts/tests/image-pins.test.sh` stays green.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S5, S6 | Inline — `docker/scripts/tests/tempo-receivers.test.sh` (RED first) | `255dcb6` |
| T2 | S1–S3, S6 | Inline — explicit `endpoint` per receiver in `docker/config/tempo/tempo.yml` | `255dcb6` |
| T3 | S5 | Inline — wire the suite into `docker-build-integrity` | `255dcb6` |
| T4 | S1–S4 | Inline — dev stack verification (receivers, spans, exporter errors) | `255dcb6` |
| T5 | — | Inline — work-unit commit and delivery | `255dcb6` |

## Log

**L1** — User request, verbatim: "si 424 y 425" (after confirming both approved issues are the pending work).

**L2** — Requirement source: issue **#425**, approved. Root cause as the issue states it: after `TEMPO_IMAGE` moved `grafana/tempo:2.3.0` → `3.1.0` (commit `f299b70`), `docker/config/tempo/tempo.yml` declares `grpc: {}`, `http: {}` and `zipkin: {}` with no `endpoint`, so the 3.x OTLP receiver takes the OpenTelemetry Collector's `localhost` default — the startup log showed `endpoint=127.0.0.1:4317` / `127.0.0.1:4318` — and nothing listened on 9411.

**L3** — Decision needed by **S2** (keep Zipkin or drop it): the compose file publishes `${TEMPO_ZIPKIN_PORT:-9411}:9411` (`docker/docker-compose.yml:188`) and `odd/tasks/otlp-tracing-migration.md` records the receiver alive before the bump. Neither application sends Zipkin anymore (both use `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`, and the same record proves zero `brave`/`zipkin` on either runtime classpath), so the receiver is kept only because dropping it would also mean removing the published port — a contract change outside this issue's scope. **Kept, with an explicit endpoint**, and the suite pins both the endpoint and the compose publication so the pair moves together if a later decision drops it.

**L4** — Out of scope, recorded as a follow-up and not changed here: `k8s/manifests/{infra,infrastructure}/tempo.yml:66` declare the same receivers without endpoints. No k8s environment runs in this stack's verification path.

**L5 — RED.** With `docker/config/tempo/tempo.yml` restored from `main` and the new suite in place: `./docker/scripts/tests/tempo-receivers.test.sh` → `1 passed, 6 failed`, exit 1 — three `declares no explicit endpoint (Tempo 3.x would bind it to localhost)` and three `endpoint table … got '-'`. A separate negative probe (`sed`-rewriting the fixed endpoints to `127.0.0.1`) fails with `binds 127.0.0.1:4318, which no other container can reach` plus the table mismatch, so both failure modes of the rule are covered.

**L6 — GREEN.** `./docker/scripts/tests/tempo-receivers.test.sh` → `7 passed, 0 failed`, exit 0. Full `docker/scripts/tests/` run in this worktree: `freshness-guard` 18/0 · `version-consistency` 8/0 · `keycloak-setup` 68/0 · `compose-profile` 6/0 · `image-pins` **42/0** (S6) · `tempo-receivers` 7/0. `shellcheck --severity=warning` over `docker/scripts/tests/tempo-receivers.test.sh` passes with the CI-pinned `koalaman/shellcheck:stable` image.

**L7 — Live verification, `[::]` binds.** `setup-env.sh` installs the file into `$VOLUMES_ROOT/tempo/config` **only when absent** (`install_monitoring_configs`, line 63), so the already-materialized dev copy was replaced in place (root-owned path; `docker run --entrypoint cp`) and `lifecontrol-dev-tempo` was restarted at `2026-10-18T21:26:29.916Z`.

- **S3** — `docker logs lifecontrol-dev-tempo` now shows `Starting GRPC server component=tempo endpoint=[::]:4317` and `Starting HTTP server component=tempo endpoint=[::]:4318` (before: `127.0.0.1`). `netstat -ltn` inside the container's network namespace: `:::3100`, `:::9095`, **`:::4317`**, **`:::4318`**, **`:::9411`**.
- **S1** — from `lifecontrol-dev-life-control-app-angular` (another container on the compose network), the exact command in the AC no longer prints `Connection refused`: an empty body now answers **400** (the pre-bump evidence recorded 415 — Tempo 3.1 changed how it rejects an empty payload), and a well-formed OTLP/JSON payload (`{"resourceSpans":[]}`) is **accepted**: `{"partialSuccess":{}}`, exit 0. The receiver is reachable and processing, which is what the criterion asks for; the 415/400 difference is stated here rather than glossed over.
- **S2** — `wget -O- http://tempo:9411/` from the same container answers **400** with body `unexpected end of JSON input` — the Zipkin HTTP API, i.e. alive as before the bump. The receiver was kept (L3).
- **S4** — exporter failures stop at the moment Tempo came up: last `Failed to export spans` in `lifecontrol-dev-lifecontrol-api` at `21:26:29.962Z` and in `lifecontrol-dev-api-gateway` at `21:26:20.516Z` (both *before* the restart at `21:26:29.916Z`); afterwards `docker logs --since 2m` counts **0** in both, and the same 0 holds for a post-restart window anchored at `21:27:34Z`. (The AC's literal `--since 10m` window still contains the pre-fix lines, so the measurement is anchored after the restart instead.) One client request through the gateway (authenticated `GET http://localhost:9000/api/companies` → 200) produced a single trace carrying both services: TraceQL `{resource.service.name="api-gateway"} && {resource.service.name="lifecontrol-api"}` → `74fb11c5dcdfa9073f628516d0357191`, root service `api-gateway`, root span `http get /api/companies/**`, start `21:29:45Z`, and the per-service queries confirm a `lifecontrol-api` span inside it. Spans also arrive from the API's own instrumentation (`http get /actuator/health`, `/actuator/prometheus`).

**L8 — Finding, out of scope for this issue (follow-up).** Reading a trace **by id** answers 500 from Tempo: `error opening block for reading, blockID: f86690f2-528c-4d47-9536-f018faa5ffd0: vParquet2 is not a valid block version`. The dev volume still holds blocks written by Tempo 2.3.0 under `docker/volumes-dev/tempo/data/traces/single-tenant/`, and Tempo 3.1.0 cannot open them, so the read path over a window that includes them fails while `GET /api/search` and trace ingest work. It is the same class of leftover volume as the Loki `/loki` note in the root `AGENTS.md`, and it is not caused by this change (the receivers were unreachable before, so no new block had been written). Not fixed here: the fix is a dev-volume wipe, which belongs with a decision about dev trace history.

**L9 — Delivery.** T1–T5 land in `255dcb6` (`fix(docker): bind the Tempo receivers to the container interface`): `docker/config/tempo/tempo.yml`, the new `docker/scripts/tests/tempo-receivers.test.sh` and the `docker-build-integrity` step — 228 authored changed lines. This record is committed immediately after it on the same branch, so its own hash is not recorded here by construction. Header written in the "what remains" shape; nothing here claims push or PR state.
