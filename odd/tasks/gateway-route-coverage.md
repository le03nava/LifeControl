# ODD feature: gateway-route-coverage

**Repository**: LifeControl — module `api-gateway/` (with `life-control-api/` as read-only evidence)
**Status**: merged — PR #152 (`fix/gateway-route-coverage` @ `d7317f659`), 2026-09-22. No work left.
**Created**: 2026-09-22

## Objective

Make every `/api/**` prefix exposed by `life-control-api` reachable through `api-gateway`, and
install a guard that fails the build when a new API prefix is not proxied.

## Problem (confirmed evidence, re-verified against source by the orchestrator)

1. `ProductVariantStoreController.java:30` maps `@RequestMapping("/api/variants")`. `Routes.java`
   declares 21 `RequestPredicates.path("/api/...")` routes but **not** `/api/variants/**`, so
   `PUT /api/variants/{variantId}/stores/{storeId}` returns **404 from the gateway**. Confirmed:
   both ids exist in `product_variants` / `company_stores`; and
   `docker logs lifecontrol-dev-lifecontrol-api` contains **no request and no
   `Upserting variant store stock` line** for the failing call, while the sibling GETs of the same
   screen are present. The API never received it.
2. `ActivityLogController.java:28` maps `@RequestMapping("/api/activity-logs")`; also absent from
   the gateway. Latent — the Angular app does not call it yet (grep: zero hits).
3. No test asserts gateway/API route agreement. `life-control-api` tests exercise
   `/api/variants/...` through MockMvc directly, bypassing the gateway
   (`ProductVariantControllerTest.java:455`, `ProductVariantStoreStockWriteIntegrationTest.java:97`),
   so the whole suite is green while the endpoint is unreachable in the deployed topology.
4. `api-gateway` has **no CI job**. `.github/workflows/api-ci.yml` filters `life-control-api/**`
   only, so `ApiGatewayApplicationTests`, `SecurityConfigTests` and
   `JwtIssuerAllowlistValidatorTests` never run in CI.

## Decisions (orchestrator, binding for this slice)

### D1 — Keep the explicit route list; enforce it with a guard instead of collapsing to `/api/**`

Collapsing the 19 prefixes of `lifeControlApiRoute()` into a single `/api/**` route was considered
and **rejected**. Every route already targets the same `props.lifeControlApiUri()`, so the list adds
no routing value today — but the three circuit breakers (`productServiceCircuitBreaker`,
`companyServiceCircuitBreaker`, `lifeControlApiCircuitBreaker`) provide per-area isolation under the
`default` config (`slidingWindowSize=10`, `minimumNumberOfCalls=5`, `failureRateThreshold=50`).
A single shared breaker would let a failing `/api/products` open the circuit for `/api/customers`
for `waitDurationInOpenState=5s`. Resilience isolation is not traded for convenience; the list stays
and gains a guard.

### D2 — Guard mechanism: deterministic source-consistency test, not a live proxy test

A behavioural test (authenticated probe per prefix, assert non-404) was considered and **rejected**:
it needs `spring-security-test`, a fast-failing upstream, and retry/timelimiter overrides
(`maxAttempts=3`, `timeout-duration=3s` would make 22 prefixes unacceptably slow and flaky). The
guard is an offline source-consistency test that fails closed when `Routes.java` stops declaring
prefixes as literal `RequestPredicates.path("/api/<segment>/**")`.

### D3 — Do not change the API contract

`/api/variants` remains as mapped. Renaming it under `/api/product-variants` would collapse two
lookalike prefixes (removing the footgun that caused this defect), but it changes a public surface
consumed by the Angular service (`product-variant.service.ts`), the backend controller tests and the
integration test. Out of scope for a routing defect; recorded as a follow-up.

## Scope

### In scope
- `api-gateway/src/main/java/com/lifecontrol/gateway/routes/Routes.java` — add the two missing routes.
- `api-gateway/src/test/java/com/lifecontrol/gateway/routes/GatewayRouteCoverageTests.java` — new guard.
- `.github/workflows/gateway-ci.yml` — new workflow so the guard actually runs.

### Out of scope (explicitly not implemented)
- Renaming `/api/variants` (D3).
- Collapsing the route list to `/api/**` (D1).
- Spotless/SpotBugs for `api-gateway` (it has neither plugin, unlike `life-control-api`); separate concern.
- `/api/user/**`: a gateway route with **no** matching API controller (legacy). Reported, not removed.

## Constraints (non-negotiable)
- `life-control-api/` production source is read-only for this slice; it is evidence, not a target.
- The guard must be deterministic and offline: no network, no Spring context, no timing.
- The guard must fail closed when the convention is broken (see positive controls in T2).

## Tasks
- [x] T1 Add `/api/variants/**` and `/api/activity-logs/**` to `lifeControlApiRoute()`.
- [x] T2 Guard test comparing API `@RequestMapping` prefixes against `Routes.java` declarations.
- [x] T3 `gateway-ci.yml` running `./gradlew test` with `working-directory: api-gateway`.
- [x] T4 Verify: guard is RED before T1 and GREEN after; gateway suite green.

## Verification

- **RED (before T1).** `GatewayRouteCoverageTests` written first and run against unmodified `Routes.java`:
  `./gradlew test --tests "com.lifecontrol.gateway.routes.GatewayRouteCoverageTests"` → **FAILED**, reporting
  `[activity-logs, variants]` as unreachable and printing the exact routes to add. The guard reproduced the
  defect independently of the manual analysis.
- **GREEN (after T1).** `api-gateway/gradlew cleanTest test` → **BUILD SUCCESSFUL**, 7 tests, 0 failures,
  0 errors:

  | Test class | tests | failures | errors |
  | --- | --- | --- | --- |
  | `ApiGatewayApplicationTests` | 1 | 0 | 0 |
  | `config.JwtIssuerAllowlistValidatorTests` | 4 | 0 | 0 |
  | `config.SecurityConfigTests` | 1 | 0 | 0 |
  | `routes.GatewayRouteCoverageTests` (new) | 1 | 0 | 0 |

- **End-to-end against the running dev stack (verified).** The gateway was rebuilt in isolation
  (`api-gateway/gradlew bootJar -Pprofile=dev -x test` → `docker compose ... build api-gateway` →
  `up -d api-gateway`); `lifecontrol-api` was **not** restarted, so its running container and the
  in-progress OTLP work in the working tree were left untouched. Probes use a valid Keycloak
  `client_credentials` token for `life-control-admin-client` (the service account has **no** `lc-admin`
  role, which is why the real `PUT` answers 403 instead of 200 — a permissions result, not a routing
  one).

  | Probe (authenticated) | Before | After | Reading |
  | --- | --- | --- | --- |
  | `GET /api/products/not-a-uuid` (control) | 400 | 400 | routed before and after |
  | `GET /api/product-variants/search?q=x&storeId=not-a-uuid` (control) | 400 | 400 | routed before and after |
  | `GET /api/variants/not-a-uuid/stores/not-a-uuid` | **404** | **405** | gateway -> API |
  | `PUT /api/variants/{realVariantId}/stores/{realStoreId}` (the reported request) | **404** | **403** | gateway -> API |
  | `GET /api/activity-logs` | **404** | **403** | gateway -> API |

  Unauthenticated probes are useless as evidence: security runs **before** routing, so every path
  (including a nonexistent one) answers 401 without a token.

- **The 405/403 envelopes are the API's, not the gateway's.** Both carry
  `{status, message, path, timestamp, correlationId}`, the shape defined at
  `life-control-api/.../exception/GlobalExceptionHandler.java:30`. The gateway contains no
  `correlationId` generation at all (`grep -rl correlationId api-gateway/src/main/java` → no matches),
  so it cannot emit that body. The `PUT` on the real ids therefore reached the API's security layer.

- **Not verified by this slice.** A `200 OK` on the real `PUT` requires a principal holding
  `lc-admin`/`lc-sales` (the reporter's browser token does). That is an authorization check, not
  routing, and it is left to a UI retry by the user.

## Status

Verified end-to-end against the running dev stack, uncommitted. Source changes isolated from
pre-existing working-tree modifications (the OTLP tracing work: `application.properties`,
`build.gradle`, `docker-compose.yml`, `odd/tasks/otlp-tracing-migration.md` — none of those were
touched by this slice):

- `api-gateway/src/main/java/com/lifecontrol/gateway/routes/Routes.java` — 2 added lines.
- `api-gateway/src/test/java/com/lifecontrol/gateway/routes/GatewayRouteCoverageTests.java` — new.
- `.github/workflows/gateway-ci.yml` — new.
- `odd/tasks/gateway-route-coverage.md` — this document.

## Follow-ups (recorded, not implemented)

1. **D3 / rename `/api/variants`** under `/api/product-variants`, collapsing the two lookalike prefixes
   that caused this defect. Distinct change: touches the Angular service, the backend controller tests
   and the integration test. The T2 guard makes it safe to do later.
2. **`/api/user/**`** is declared by the gateway with no matching API controller (legacy). Dead config;
   needs a product decision before removal.
3. **Gate action pins to SHAs** (`zizmor:unpinned-uses`) across all workflows, not just this one.
4. **Spotless/SpotBugs for `api-gateway`**, so it matches `life-control-api`'s quality gates.

## Next step

Commit as reviewable work units once the user authorizes delivery.
