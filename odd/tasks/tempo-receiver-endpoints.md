# The Tempo receivers answer again, and a test catches the drift

**Repository**: LifeControl — `docker/config/tempo/tempo.yml` (+ `docker/scripts/tests/`), issue **#425**
**Status**: implemented — `255dcb6` (explicit endpoints, drift suite, CI wiring) and `bc2984b` (the Zipkin receiver dropped after a CRITICAL finding, see L11); what remains is the delivery of this unit as its own PR and the review-authority decision recorded in L11. This header makes no claim about push or PR state.
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
| T5 | — | Inline — work-unit commit | `255dcb6` |
| T6 | S2 (second branch) | Inline — drop the Zipkin receiver; pin the removal in the suite | `bc2984b` |

## Log

**L1** — User request, verbatim: "si 424 y 425" (after confirming both approved issues are the pending work).

**L2** — Requirement source: issue **#425**, approved. Root cause as the issue states it: after `TEMPO_IMAGE` moved `grafana/tempo:2.3.0` → `3.1.0` (commit `f299b70`), `docker/config/tempo/tempo.yml` declares `grpc: {}`, `http: {}` and `zipkin: {}` with no `endpoint`, so the 3.x OTLP receiver takes the OpenTelemetry Collector's `localhost` default — the startup log showed `endpoint=127.0.0.1:4317` / `127.0.0.1:4318` — and nothing listened on 9411.

**L3 — First decision on S2 (later reversed, see L11).** The Zipkin receiver was kept, with an explicit endpoint, on the argument that the compose file publishes `${TEMPO_ZIPKIN_PORT:-9411}:9411` (`docker/docker-compose.yml:188`) and that dropping the receiver would mean changing a published port — a contract change outside the issue's scope. That argument weighed the wrong side: the published port is the exposure, not the thing being protected.

**L4** — Out of scope, recorded as a follow-up and not changed here: `k8s/manifests/{infra,infrastructure}/tempo.yml:66` declare the same receivers without endpoints. No k8s environment runs in this stack's verification path.

**L5 — RED.** With `docker/config/tempo/tempo.yml` restored from `main` and the new suite in place: `./docker/scripts/tests/tempo-receivers.test.sh` → `1 passed, 6 failed`, exit 1 — three `declares no explicit endpoint (Tempo 3.x would bind it to localhost)` and three `endpoint table … got '-'`. A separate negative probe (`sed`-rewriting the fixed endpoints to `127.0.0.1`) fails with `binds 127.0.0.1:4318, which no other container can reach` plus the table mismatch, so both failure modes of the rule are covered.

**L6 — GREEN.** `./docker/scripts/tests/tempo-receivers.test.sh` → `7 passed, 0 failed` for the candidate at `255dcb6` and **`5 passed, 0 failed`** for the corrected candidate at `bc2984b` (the two Zipkin checks were replaced by one absence check). Full `docker/scripts/tests/` run: `freshness-guard` 18/0 · `version-consistency` 8/0 · `keycloak-setup` 68/0 · `compose-profile` 6/0 · `image-pins` **42/0** (S6) · `tempo-receivers` 5/0. `shellcheck --severity=warning` over the new suite passes with the CI-pinned `koalaman/shellcheck:stable` image.

**L7 — Live verification of the receivers (candidate `255dcb6`).** `setup-env.sh` installs the file into `$VOLUMES_ROOT/tempo/config` **only when absent** (`install_monitoring_configs`, line 63), so the already-materialized dev copy was replaced in place (root-owned path; `docker run --entrypoint cp`) and `lifecontrol-dev-tempo` was restarted at `2026-10-18T21:26:29.916Z`.

- **S3** — `docker logs lifecontrol-dev-tempo` then showed `Starting GRPC server component=tempo endpoint=[::]:4317` and `Starting HTTP server component=tempo endpoint=[::]:4318` (before: `127.0.0.1`).
- **S1** — from `lifecontrol-dev-life-control-app-angular`, the exact command in the AC no longer printed `Connection refused`: an empty body answered **400** (the pre-bump evidence recorded 415 — Tempo 3.1 changed how it rejects an empty payload), and a well-formed OTLP/JSON payload (`{"resourceSpans":[]}`) was **accepted**: `{"partialSuccess":{}}`, exit 0.
- **S2 (first answer)** — `wget -O- http://tempo:9411/` answered **400** with body `unexpected end of JSON input`: the Zipkin API, alive. This is the state L11 reverses.
- **S4** — exporter failures stopped at the moment Tempo came up: last `Failed to export spans` in `lifecontrol-dev-lifecontrol-api` at `21:26:29.962Z` and in `lifecontrol-dev-api-gateway` at `21:26:20.516Z` (both *before* the restart), `0` afterwards in both. One authenticated request through the gateway produced trace `74fb11c5dcdfa9073f628516d0357191`, root service `api-gateway`, root span `http get /api/companies/**`, matched by TraceQL `{resource.service.name="api-gateway"} && {resource.service.name="lifecontrol-api"}`.

**L8 — Finding, out of scope for this issue (follow-up).** Reading a trace **by id** answers 500 from Tempo: `error opening block for reading, blockID: f86690f2-528c-4d47-9536-f018faa5ffd0: vParquet2 is not a valid block version`. The dev volume still holds blocks written by Tempo 2.3.0 under `docker/volumes-dev/tempo/data/traces/single-tenant/`, which Tempo 3.1.0 cannot open, so the read path over a window that includes them fails while ingest and search work. Same class of leftover volume as the Loki `/note/` in the root `AGENTS.md`; not caused by this change and not fixed here (the fix is a dev-volume wipe).

**L9 — Native review of this candidate (RDD is on; `assess` reported `nativeRisk: high`, `reviewDue: true`).** `inspect` offered one START; START created lineage `review-13c5e43039050a37` (tier `high`, three lenses: risk, resilience, reliability, base `main`, committed-only) and STATUS offered the three materialize slots. The group forecast was relayed and acknowledged (`model_runs: 3`, `transport: pi_host_relay`).

**L10 — The provider refused the risk reviewer at admission.** `pi-host-relay-transport-failure`, kind `submission-refused`, reason: `reviewer artifact admission out_of_scope: reviewer evidence references a path outside the frozen repository; admission_diagnostic={"code":"evidence_path_out_of_scope","location":"0.0.0.0:9411","reason":"unknown_or_malformed_repository_path"}`. Nothing was consumed (`prepared_reviewers: 3, submitted_reviewers: 0, mutation_performed: false`) and the payload was preserved at `.git/gentle-ai/rejected-results/review-13c5e43039050a37/review-risk-1-bc44b25fe4c9.json`. The refusal is an admission heuristic misfiring: the reviewer's evidence **prose** contains the endpoint literal `0.0.0.0:9411`, which the admission read as a repository path; the finding's actual location field was the in-repo path `docker/config/tempo/tempo.yml:26`.

**L11 — The refused payload carried a CRITICAL finding, and it was right.** Read from the preserved artifact: `{"location": "docker/config/tempo/tempo.yml:26", "severity": "CRITICAL", "claim": "Binding the Zipkin receiver to 0.0.0.0:9411 while the new suite pins the compose host-port publication exposes an unauthenticated trace-ingestion endpoint on the host…", "evidence_class": "deterministic", "causal_disposition": "introduced"}`. Candidate-caused and deterministic, so it blocks: the compose file publishes 9411 in **every** environment (the prod override publishes `${TEMPO_ZIPKIN_PORT:-9611}:9411`), and declaring the receiver turns that published port into an ingest socket nothing consumes — neither application sends Zipkin (proved by `odd/tasks/otlp-tracing-migration.md`: zero `brave`/`zipkin` on either runtime classpath). **Correction applied in `bc2984b`**: the Zipkin receiver is not declared; the published port is left untouched (with no receiver behind it, it accepts nothing); the suite gained a fail-closed rule that names the receiver as forbidden and fails if it comes back, plus reports the reason. The correction is squarely inside the issue's S2 ("or `docker/config/tempo/tempo.yml` no longer declares `zipkin` and the record states why"). Effect on the two answers of L7: the Zipkin branch of S2 is the second one, and S1/S3/S4 are unaffected because the OTLP receivers and the exporters are untouched.

**L12 — Live verification of the corrected candidate (`bc2984b`).** The new file was installed into the dev volume and `lifecontrol-dev-tempo` restarted again; measured: listeners in the container's network namespace are `:::4317`, `:::4318`, `:::3100` and **no `:::9411`**; from the web-app container `wget http://tempo:9411/` → `Connection refused`; from the host, the published port answers `http_code=000` (connection closed); a well-formed OTLP/JSON payload still returns `{"partialSuccess":{}}`; and `Failed to export spans` counts `0` for both applications in the window after the restart. Per-service TraceQL searches keep returning fresh spans from both `api-gateway` and `lifecontrol-api` (Tempo's search index lags ingest by minutes; the same-both-services query that proved S4 in L7 was taken on the OTLP-identical configuration).

**L13 — Review authority left in `reviewing`, decision needed.** The lineage `review-13c5e43039050a37` is frozen on the *pre-correction* tree (`sha256:a9864000…`) and never admitted a lens result: the risk slot is refused by the admission defect of L10, and the candidate has since changed (`bc2984b`), so its frozen target no longer describes the branch. Pi submits nothing further on its own: the lineage needs a maintainer decision (retry is pointless while the admission rejects that evidence shape; releasing it is an audited native operation, and disabling the review switch for this clone would let ordinary policy decide). This record makes no claim about that decision's outcome.

**L14 — Delivery.** T1–T5 land in `255dcb6`, T6 plus this record's revision in `bc2984b` and the record commit that follows it; 306 authored changed lines in total on `fix/tempo-receiver-endpoints`, under the repository's ~400-line review budget. Header written in the "what remains" shape; nothing here claims push or PR state.
