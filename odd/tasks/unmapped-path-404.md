# Unmapped request paths answer 404 instead of 500

**Repository**: LifeControl — component `life-control-api/` (`exception/GlobalExceptionHandler`), issue **#424**
**Status**: implemented — the handler, its tests and the container verification are in commit `1b49985` on `fix/unmapped-path-404`; what remains is the delivery of this unit as its own PR. This header makes no claim about push or PR state.
**Created**: 2026-10-18 · **Risk**: medium — the affected code is the shared catch-all advice that every endpoint's errors pass through

## Specs

Verbatim from issue **#424** (`bug`, `status:approved`).

**S1** — Expected outcome: "An unmapped path is answered `404` with this repository's error contract, and no ERROR line is logged for it. Every path that does match a handler keeps its current behaviour, including the other mappings declared in the same class."

**S2** — "`curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8082/actuator/nope` prints `404` (today: `500`)."

**S3** — "`curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8082/favicon.ico` prints `404` (today: `500`)."

**S4** — "`docker logs --since 1m lifecontrol-dev-lifecontrol-api | grep -c 'NoResourceFoundException'` is at least 1 for those two requests while `grep -c 'Unhandled exception'` is 0, i.e. the 404 is not reported as an unhandled failure."

**S5** — "An unmapped path under the authenticated API keeps answering 404 once authenticated: `curl -s -o /dev/null -w '%{http_code}' http://localhost:8082/api/companies/00000000-0000-0000-0000-000000000000/stores` still prints `401` unauthenticated (the security filter chain must keep answering before routing) and `404` with a valid token."

**S6** — "A test that fails today is added, observing RED first: it requests an unmapped path and asserts the 404 status together with the error-contract body."

**S7** — "The already-mapped error cases keep their status codes: `ResourceNotFoundException` → 404, `IdentityProviderNotFoundException` → 404, `ConflictException` → 409, `MethodArgumentNotValidException` → 400, `AccessDeniedException` → 403, `HttpRequestMethodNotSupportedException` → 405."

**S8** — Error contract, as returned today (`GlobalExceptionHandler.ErrorResponse`): `{"status":…,"message":…,"path":…,"timestamp":…,"correlationId":…}`.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S6 | Inline — RED `@WebMvcTest` on an unmapped path | `1b49985` |
| T2 | S1, S7, S8 | Inline — explicit 404 handlers in `GlobalExceptionHandler` | `1b49985` |
| T3 | S2–S5 | Delegated — independent dev-container verification (`gentle-ai-verify`) | `1b49985` |
| T4 | S1, S6, S7 | Inline — gate: `spotlessCheck spotbugsMain` + `test` | `1b49985` |
| T5 | — | Inline — work-unit commit and delivery | `1b49985` |

## Log

**L1** — User request, verbatim: "si 424 y 425" (after confirming both approved issues are the pending work).

**L2** — Requirement source: issue **#424**, approved. Root cause as the issue states it: `GlobalExceptionHandler` declares a catch-all `@ExceptionHandler(Exception.class)` (`life-control-api/src/main/java/com/lifecontrol/api/exception/GlobalExceptionHandler.java:201`) returning 500; Spring MVC raises `NoResourceFoundException` for an unmapped path, which `DefaultHandlerExceptionResolver` would turn into 404, but the advice is consulted first.

**L3** — Two exception types reach the advice from the same class of request, read from the existing tests: `NoResourceFoundException` (full context, `PurchaseOrderDetailVariantIntegrationTest:699`) and `NoHandlerFoundException` (the dispatcher with no resource registry, `PurchaseOrderControllerTest:432`). Both comments stated "The global catch-all maps it to 500", which this change falsifies. Those two tests asserted only `getResolvedException()`, so they stayed valid; their comments now describe the new behaviour and each one **also asserts `status().isNotFound()`**, so both exception types are pinned at their HTTP level.

**L4 — RED.** `./gradlew test --tests '*UnmappedPathErrorContractTest*'` → `tests="3" failures="2"`: `java.lang.AssertionError: Status expected:<404> but was:<500>` for `/no-such-path` and for `/actuator/nope`; the third case (a malformed `includeDisabled` on a mapped path) passed with 400, as it must before and after.

**L5 — Fix.** `GlobalExceptionHandler` gains `@ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})` returning 404 with the standard envelope and message `No handler or resource found for this path`, logged at **DEBUG** (not ERROR: an unmapped path is not an unhandled failure, and this repository alerts on 5xx). The catch-all for real failures is untouched, and the new handler names only those two types, so no other mapping can be shadowed.

**L6 — GREEN, focused.** `./gradlew test --tests '*UnmappedPathErrorContractTest*' --tests '*GlobalExceptionHandlerTest*' --tests '*PurchaseOrderControllerTest*' --tests '*PurchaseOrderDetailVariantIntegrationTest*'` → `BUILD SUCCESSFUL` (33s).

**L7 — Gate.** `./gradlew spotlessCheck spotbugsMain --no-daemon` → `BUILD SUCCESSFUL` (33s). `./gradlew test --no-daemon` → `BUILD SUCCESSFUL` in 3m01s, **3527 tests, 0 skipped, 0 failures, 0 errors** (counted from `build/test-results/test/*.xml`). This is the same command pair `.github/workflows/api-ci.yml` runs.

**L8 — Container verification, independent (`gentle-ai-verify`, over the working tree).** The verifier first probed the **old** jar in the running container: `/actuator/nope` → 500, `/favicon.ico` → 500, `/nonexistent-static-path` → `{"status":500,"message":"An unexpected error occurred",…}`. Then it copied the jar built from this worktree (`sha256 bed172e3601ebe52744518e3bdc136d8aea2ce6a0734881b46f46e4105c4f289`) over the anchor's `life-control-api/build/libs/`, rebuilt the `lifecontrol-api` service (image `sha256:3b880451212dea868af408e25a9fd4c916a06a51b3d0e4f8ade8016f318224fb`), restarted only that container (`StartedAt 2026-10-18T21:33:00.808Z`, probe baseline `21:33:18Z`) and measured:

| Probe | Expected | Actual |
| --- | --- | --- |
| `/actuator/nope` (S2) | 404 | **404** |
| `/favicon.ico` (S3) | 404 | **404** |
| `/nonexistent-static-path` body (S8) | 404 envelope | `{"status":404,"message":"No handler or resource found for this path","path":"/nonexistent-static-path","timestamp":…,"correlationId":…}` |
| `/api/companies/000…000/stores`, no token (S5) | 401 | **401** |
| same URL, `Bearer` smoke-admin token (S5) | 404 | **404** |
| `/api/countries?includeDisabled=not-a-boolean`, token (S7) | 400 | **400** (`Invalid value for parameter 'includeDisabled'`) |

Logs since the baseline (S4): `NoResourceFoundException` count **10** (≥1), `Unhandled exception` count **0**, `' ERROR '` lines **0**, with the raw line `DEBUG … c.l.a.e.GlobalExceptionHandler - No handler or resource for this path`. The in-container digest `docker exec lifecontrol-dev-lifecontrol-api sha256sum /app/app.jar` is **identical** to the jar built from this worktree, so the container provably runs the fixed build. Its verdict: S2, S3, S4, S5 and S7 (for the probed mapped path) **met**, no blockers.

**L9 — S7 coverage map** (the criterion asks that the other mappings keep their codes; none of their handlers was touched):
`ResourceNotFoundException` → 404 and `ConflictException` → 409 and `MethodArgumentNotValidException` → 400: `GlobalExceptionHandlerTest` (`handleNotFound`, `handleConflict`, `handleValidationErrors`); `IdentityProviderNotFoundException` → 404: `GlobalExceptionHandlerTest` plus `UsersAdminControllerTest`/`RolesControllerTest`/`ProfileControllerTest`; `AccessDeniedException` → 403: the `*ControllerSecurityTest` classes; `HttpRequestMethodNotSupportedException` → 405: `GlobalExceptionHandlerTest` (`handleMethodNotSupported_Returns405`) and `isMethodNotAllowed` elsewhere. Plus the two updated purchase-order tests (404 for both unmapped-path exception types) and the container probes above. Docs: no component document enumerates the error contract (`grep -rln "GlobalExceptionHandler\|An unexpected error occurred" --include=*.md` finds only `odd/tasks/` records), so there is no help text or doc to update beyond this record.

**L10 — Delivery.** T1–T4 land in `1b49985` (`fix(api): answer 404 for a path no handler maps`): the handler, `UnmappedPathErrorContractTest`, and the two refreshed purchase-order tests — 96 authored changed lines. This record is committed immediately after it on the same branch, so its own hash is not recorded here by construction. Header written in the "what remains" shape; nothing here claims push or PR state.
