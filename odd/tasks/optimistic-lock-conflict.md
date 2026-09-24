# ODD feature: optimistic-lock-conflict

**Repository**: LifeControl — backend `life-control-api/` only. The Angular half of W2-D13 (the conflict
UX) is deliberately a separate slice; see `## Scope`.
**Status**: **in progress** — plan written, no source written yet.
**Created**: 2026-09-24
**Risk**: **medium** — one new `@ExceptionHandler` (platform-wide effect on the store tree's HTTP
contract) plus an optional precondition on one existing contract. No auth, role, schema or data change.

## Why this exists

`odd/tasks/purchase-order-goods-receipt.md` records **W2-D13** as deferred debt, in three parts:
(a) `GlobalExceptionHandler` has no `ObjectOptimisticLockingFailureException` handler, so an
optimistic-lock conflict answers **500** instead of 409 — pre-existing since `V8`, affecting the whole
store tree; (b) the settings contract carries no `version`, so a client cannot detect a lost update
(W2b debt #2); (c) there is no conflict UX in Angular (W2d recon). W2-D13 explicitly recorded the debt
as **"its own follow-up slice"**, which is this document.

The user chose this scope on 2026-09-24: **backend only** — the 409 handler platform-wide plus the
`version` contract on the settings endpoint, enforced server-side. The Angular UX (part c) is its own
slice and is **not** in this one.

## Recon (read-only, `file:line` anchored at `964e826`)

- **Five `@Version` entities**, all primitive `long`: `store/model/CompanyStore.java:39-41`,
  `store/model/StoreArea.java:40-42`, `store/model/StoreZone.java:42-44`,
  `store/model/StoreLocation.java:41-43`, `inventory/model/StoreInventorySettings.java:38-40`
  (schema `V8:12-15` and `V11:29`). `GoodsReceipt` is immutable and `ProductVariantStoreStock` uses
  pessimistic locks; neither carries `@Version`.
- **Services that mutate a loaded entity in place** (so a stale flush throws): `StoreInventorySettingsService:180-183`,
  `CompanyStoreService:199,212-213,235-236`, `StoreAreaService:253,278-279,304-305,329-330`,
  `StoreZoneService:336,401-402,435-436,451-452`, `StoreLocationService:381,412-413,452-453`. There is
  **no** repository-level locking query anywhere in `store/`.
- **`ObjectOptimisticLockingFailureException` is a `DataAccessException` but not a
  `DataIntegrityViolationException`** (Spring Framework 6.2.19: it extends `OptimisticLockingFailureException`
  → `ConcurrencyFailureException` → `TransientDataAccessException`). It therefore matches no typed
  handler in `GlobalExceptionHandler` and falls into the catch-all `Exception` handler
  (`GlobalExceptionHandler.java:178-180`) → **500**. Confirmed.
- **Spring resolves `@ExceptionHandler` by class-hierarchy specificity, not declaration order**
  (`ExceptionHandlerMethodResolver`), so the new handler needs no particular position — including no
  need to sit before the catch-all. A handler on `DataAccessException` would be the wrong, broader tool.
- **The `version` column already exists** (`V11__store_inventory_settings.sql`, `version BIGINT NOT NULL
  DEFAULT 0`) and is already mapped, so **no migration is needed**. The purchase-order record's claim
  that the Flyway head is `V8` is stale: the head is `V14__product_variant_definition.sql`.
- **Existing lock tests assert the exception, never the status**: `StoreOptimisticLockingIntegrationTest:93`
  and `StoreInventorySettingsIntegrationTest:424` (`staleUpdateFailsInsteadOfSilentlyWinning`, asserting
  at the repository/`TransactionTemplate` level). Every existing store-tree 409 assertion is a
  duplicate-key or disabled-parent conflict, never a lock conflict — the record's claim is confirmed.
- **Adding a component to `StoreInventorySettingsResponse` breaks three constructions**:
  `StoreInventorySettingsControllerTest:94,131` and `StoreInventorySettingsControllerSecurityTest:94`.
- **No Angular interface carries `version` anywhere** in the app, and `http-error-message.ts` maps 409
  to `UNKNOWN_ERROR_MESSAGE`.

## The lost update this closes (and why the entity's `@Version` alone does not)

The `@Version` column only catches a **concurrent flush** — two writers in flight at the same time. It
does **not** catch the real lost update: a client reads the settings (v3), another writer changes them
(v4), and the first client then PUTs. The request loads the row **fresh** (v4), applies the client's two
location ids, flushes (v4 → v5) and **succeeds silently**, overwriting the other writer's change. The
client must therefore assert the version it read, and the server must compare it against the
freshly-loaded entity — which is exactly what this slice adds.

## Decisions

- **D1 — the handler is platform-wide.** `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)`
  → **409**, with a generic message that leaks no internal detail (no entity name, no SQL, no version
  numbers), matching the style of the existing `handleDataIntegrityViolation` fixed message. It logs at
  WARN with the correlation id, like its neighbours. Rationale: the debt is platform-wide since `V8`
  and the exception carries no safe per-endpoint detail; a narrower mapping would need per-service
  translation that does not exist and would leave the other four store-tree write paths answering 500.
- **D2 — the response always carries the version.** `StoreInventorySettingsResponse` gains a required
  `long version` (the entity always has one), so the client has something to assert. Cost: three test
  constructions to update (named in `## Recon`).
- **D3 — the request's version is an optional precondition, not a required field.** The request DTO
  gains `Long version` (nullable). This is deliberate and is the one place the slice trades strictness
  for safety:
  - **Why not required**: making it mandatory would turn every existing PUT into a 409, which **breaks
    the store inventory-settings screen that is already on `main`** (its PUT sends no version today).
    Shipping that would break a working screen in exchange for closing a backend debt, and the user
    explicitly scoped the Angular half out of this slice.
  - **The semantics are `If-Match` in the body**, which is the industry norm for an optional
    precondition: absent means "no precondition, today's behaviour", present means "reject me if the
    world moved".
  - **Consequence, stated plainly**: the fix is only user-visible once the Angular slice sends the
    version. Until then the backend half is real but dormant for the UI. That is recorded as the
    handoff below, not left implicit.
- **D4 — the comparison is a business conflict, not an infrastructure one.** A version mismatch is
  detected by the service **before** the flush and signalled with the existing
  `ConflictException` (`exception/ConflictException.java`) → 409 through the already-existing handler.
  No new exception class: `ConflictException`'s own javadoc covers "invalid state transitions that
  represent a conflict with the current server state", and a single-call-site subclass would add surface
  without value. The new handler (D1) covers the other path — the genuine concurrent flush race.
  Both paths therefore answer 409, by two different mechanisms, and both get a test.
- **D5 — no migration.** The column exists and is mapped; this slice changes only DTOs, the mapper, the
  service and the handler.
- **D6 — store-tree-wide adoption is out of scope, and that is recorded rather than implied.** W2b debt
  #2 warned that adding `version` to this endpoint alone "introduces a new pattern". The slice accepts
  that for one endpoint and hands the store-tree-wide adoption (`store/dto/*`, the other four edit
  screens, and the `If-Match`/interceptor question) to its own follow-up. Deciding it here would turn a
  bounded debt fix into a cross-cutting contract change.
- **D7 — the HTTP-level gap gets closed here.** The existing lock tests assert the exception, not the
  status. This slice adds the missing HTTP assertion: a PUT that asserts a stale version must answer
  **409** through `MockMvc`, not 200 and not 500.

## Scope

**In**: the `ObjectOptimisticLockingFailureException` → 409 handler and its unit test; `version` on the
settings response; the optional `version` precondition on the settings request and its enforcement in
`upsertSettings`; the D7 integration test; the test-fixture updates the response field forces.

**Out, explicitly**: the Angular conflict UX and the `409` case in `http-error-message.ts` (D13b);
`version` on the other four store-tree contracts (D6); any `If-Match`/ETag header or interceptor;
`@Version` on `GoodsReceipt`; the W3 sales rework; any migration.

## Task list

| Id | Task | Evidence |
|----|------|----------|
| **T1** | `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)` → 409 in `GlobalExceptionHandler`, generic sanitized message, WARN log (D1) | new test in `GlobalExceptionHandlerTest` |
| **T2** | `StoreInventorySettingsResponse` gains required `long version` + the mapper fills it (D2); update the three constructions it breaks | controller/security test specs |
| **T3** | `StoreInventorySettingsRequest` gains `Long version`; `upsertSettings` compares it against the freshly-loaded entity and throws `ConflictException` on mismatch or on "version asserted but no row" (D3/D4) | `StoreInventorySettingsServiceTest` |
| **T4** | HTTP-level integration test: a PUT asserting a stale version answers 409 (D7) | `StoreInventorySettingsIntegrationTest` |
| **T5** | Point the purchase-order record's W2-D13 rows at this document and record the handoff | `odd/tasks/purchase-order-goods-receipt.md` |

**Work units** (one work-unit commit each): **W1** = T1–T4, the whole change. The handler and the
contract are one reviewable unit — the handler is what makes the contract's rejection path coherent,
and splitting them would ship a 409 handler whose only caller is untested (or a contract whose rejection
answers 500).

### The version matrix T3 must implement (one test per case)

| Request `version` | Row exists | Outcome |
|---|---|---|
| absent (`null`) | no | create — today's behaviour, unchanged |
| absent (`null`) | yes | update — today's behaviour, **no** precondition asserted |
| present | no | **409** — the client asserts a version for a row that does not exist |
| present | yes, equal | update |
| present | yes, different | **409** — the lost update this slice exists to prevent |

## Verification plan

Backend, from `life-control-api/` (the root `gradlew` is absent in a fresh worktree by design, so Gradle
runs from the module directory):
- `./gradlew spotlessCheck spotbugsMain --no-daemon`
- `./gradlew test --no-daemon` (Testcontainers integration tests included; Docker 28.5.2 is up on this
  box, and `AbstractPostgresIntegrationTest` forces `ddl-auto=validate`)

Focused loop while iterating: `./gradlew test --no-daemon --tests '*GlobalExceptionHandlerTest*'`,
`--tests '*StoreInventorySettingsServiceTest*'`, `--tests '*StoreInventorySettingsIntegrationTest*'`.

## Review workload

**Forecast**: ~40 source lines (handler ~10, response DTO 1, request DTO 1, mapper 1, service compare
~20) plus ~120-180 spec lines (handler test, five service cases, one HTTP integration test, three
fixture updates). **~160-220 changed lines total, in one area** — under the ~400-line review guardrail,
which is exactly why the Angular half is not here (with it, the honest estimate was 350-450 lines across
two components).

## Evidence log

| Date | Slice | Commit | Evidence |
|---|---|---|---|
| 2026-09-24 | recon | — | Read-only `gentle-ai-explore` scout over the W2-D13 surface at `964e826`, every claim `file:line` anchored: the five `@Version` entities and their in-place mutators, the exception's Spring hierarchy (a `DataAccessException`, not a `DataIntegrityViolationException`), handler resolution by specificity rather than order, the existing lock tests asserting the exception rather than the status, the three response constructions the new field breaks, and the fact that the `version` column already exists so no migration is needed. Also found the purchase-order record's Flyway-head claim stale (`V8` → `V14`). No source written. |

## Handoff to the Angular slice (D13b), recorded so it cannot evaporate

1. `features/inventory/models/store-location-summary.models.ts:35-45` — add `version` to both interfaces.
2. `features/inventory/data/store-inventory-settings.service.ts:61-66` — send the version read from the
   GET in the PUT body.
3. `features/companies/stores/pages/store-inventory-settings/store-inventory-settings.ts:241-258` —
   `handleSaveError` has no 409 case; a conflict must tell the operator to reload, not show the generic
   message.
4. `shared/data/http-error-message.ts` — 409 currently falls to `UNKNOWN_ERROR_MESSAGE`.
5. The other four `@Version`-backed edit screens (`stores`, `store-areas`, `store-zones`,
   `store-locations`) need the same treatment; that is the store-tree-wide adoption of D6.
