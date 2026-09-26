# ODD feature: version-precondition-store-tree

**Repository**: LifeControl — spans `life-control-api/**` (Spring Boot, Java 21) and
`life-control-app-angular/**` (Angular 20). Four vertical slices, one per screen.
**Status**: W0 closed, **W1 `stores` delivered** — PR #183, merge `50826e0` — and **W2 `store-areas`
implemented and committed** as `5345a5b` on `feat/store-version-precondition-areas`; its pull request is
the delivery still to open. **W3 `store-zones` and W4 `store-locations` remain**, in that order, one pull
request each. Per-slice detail and the measured evidence live in `## Evidence log`.
**Created**: 2026-09-26
**Risk**: **medium.** A write path gains a precondition it did not carry and four response contracts
gain a field. No auth change, no role change, no schema change, no migration, no data migration. The
blast radius is four edit endpoints plus four Angular screens and the fixtures of four shared read
models.
**Requested by**: the user — "continua con d13b", after D13b steps 1–4 landed in PR #172 and this
record's `## Origin` shows the deferred step 5 sized at ~60–70 files.

## Origin

`odd/tasks/optimistic-lock-conflict.md` (PR #161, merge `bcb8d10db`) shipped the backend half of
W2-D13: `ObjectOptimisticLockingFailureException` maps to **409** platform-wide and the
store-inventory-settings endpoint gained an optional `version` precondition. Its
`## Handoff to the Angular slice (D13b)` lists five steps.

Steps 1–4 landed in PR #172 (`fix/inventory-settings-conflict-ux` → merge `2b7368e`) for **one**
screen: settings. Step 5 — the store-tree-wide adoption across `stores`, `store-areas`,
`store-zones` and `store-locations` — was explicitly narrowed out of that slice and is what this
record executes.

`odd/tasks/purchase-order-goods-receipt.md` W2-D13 carries the same deferral. This record supersedes
that deferral; it does not restate it.

## What is already in place (verified 2026-09-26)

| Fact | Evidence |
| --- | --- |
| All four entities already carry `@Version` | `V8__store_optimistic_locking.sql:11-14` adds `version BIGINT NOT NULL DEFAULT 0` to `company_stores`, `store_areas`, `store_zones`, `store_locations`; `CompanyStore.java:39-41`, `StoreArea.java:40-42`, `StoreZone.java:42-44`, `StoreLocation.java:41-43` |
| No migration is needed | Flyway head is `V15__inventory_balance_reset.sql`; `V8` is the only migration touching those columns |
| The 409 infrastructure exists | `GlobalExceptionHandler.java:73-78` (`ObjectOptimisticLockingFailureException` → 409) and `:51-54` (`ConflictException` → 409) |
| The 412 infrastructure exists | added by D10 in this work: `VersionPreconditionException` → **412**, through the same error envelope |
| The Angular 409 copy exists | `src/shared/data/http-error-message.ts:27-28` |
| The pattern to copy exists | `StoreInventorySettingsService.java:60` (message constant), `:196` (`saveAndFlush` with its rationale comment), `:211-217` (`assertVersionPrecondition`), `:60` vs `StoreInventorySettingsRequest.java:20-26` (`Long version`, plus the backward-compatible 2-arg constructor) |
| The repositories can flush | all four extend `JpaRepository` (e.g. `CompanyStoreRepository.java:24`) |

## The defects this closes

1. **An edit form can silently overwrite another operator's save.** Four `PUT` endpoints accept an
   update with no precondition, so a form holding an old copy wins by being written last.
2. **The twelve write methods answer a stale `version`.** `create*`/`update*`/`enable*` × 4 map their
   response from the return of a non-flushed `save(...)`: `CompanyStoreService:148,199,236` ·
   `StoreAreaService:210,253,330` · `StoreZoneService:282,336,402` · `StoreLocationService:324,381,453`.
   Hibernate advances `@Version` **at flush**, so the response carries the pre-increment value. A
   client that echoes it is rejected with a false **412** on its next write (409 before D10).
3. **The same twelve answers are also stale on `updatedAt`** — and on `createdAt` for the four create
   paths, which map a null timestamp because `Auditable`'s `@PrePersist`/`@PreUpdate`
   (`Auditable.java:20-26`) has not run yet. Recorded as a live defect in
   `odd/tasks/inventory-settings-conflict-ux.md` (`## Follow-ups`) and never fixed. One
   `saveAndFlush` closes 2 and 3 together, which is why D4 folds them into one change.

## Decisions

| # | Decision | Rationale |
| --- | --- | --- |
| **D1** | **Four vertical slices, one per screen**, one PR each, order `stores` → `store-areas` → `store-zones` → `store-locations`. | User decision, chosen over two horizontal slices (backend/frontend) and over a single PR. Each slice is independently reviewable and independently shippable: after W1 the stores screen is protected even if W2–W4 never land. The four slices are independent, **not a chain** — no slice edits code another slice owns, so no slice's branch is another's base (`references/pr-chains.md` applies only if that changes). |
| **D2** | **`version` is REQUIRED on the four Angular read models** (`CompanyStore`, `StoreArea`, `StoreZone`, `StoreLocation`), matching the settings precedent (D7 there). | Fail-closed: if the API stops sending it, `tsc` fails instead of the screen quietly omitting the precondition. Accepted cost: every typed literal of those models gains a field — ~11 files for `CompanyStore` alone, several outside the `stores` feature. |
| **D3** | **`stores` takes its `version` from the list response through `history.state`.** No flat `GET /api/stores/{id}` is added. | User decision. `stores-edit.ts:83-89` already reads the entity from `globalThis.history.state` and redirects when it is absent, and `stores-page.ts` passes the whole entity (`state: { store }`), so the version rides along at no contract cost. |
| **D4** | **The stale `updatedAt`/`createdAt` is fixed in the same slice, with a pinning test per screen.** | The flush is mandatory for `version`, so the timestamp correction is free; leaving it unpinned would let a later refactor drop the flush and restore all three defects at once. |
| **D5** | **The precondition is enforced on `PUT` only.** `POST` (create) and `PATCH` (enable) receive the flush fix but no precondition. | Create has no prior version to assert. The enable endpoints take no request body at all (`CompanyStoreController.java:125-139`, `StoreAreaController.java:149-165`), so a precondition there would change the endpoint's shape — a separate contract decision, recorded as a follow-up. Enable also loads its entity fresh inside its own transaction, so it cannot hold a stale copy. |
| **D6** | **The request DTO's `version` is optional (`Long`, nullable), the response's is required (`long`).** The request record keeps a backward-compatible constructor at the old arity. | Mirrors `StoreInventorySettingsRequest` exactly. `null` means "no precondition, today's behaviour", so existing clients and existing test constructions keep working and stay green as the backward-compatibility coverage. |
| **D7** | **The `version` is merged into the write payload at the PAGE, never in the form component and never in the data service.** | Keeps the optimistic-lock concern out of the forms (`stores-form`, `store-area-form`, …), so the diff stays inside the page. The settings precedent does the same (`store-inventory-settings.ts:215`). |
| **D8** | **On 412, the three screens with a flat GET re-run their load — accepting that this discards the operator's draft; `stores` does not reload and gets a screen-specific copy.** | The three pages (`store-areas-edit.ts:245-251`, `store-zones-edit.ts:101-120`, `store-locations-edit.ts:114-135`) can fetch a fresh entity and therefore a fresh version in place, exactly like the settings precedent, which also re-seeds its controls and says so. The reload **does** replace a draft the operator had typed: deliberate and accepted, because re-seeding is what unsticks the version, and adopting only the fresh version while keeping the draft would let the operator silently re-apply edits made against a state that no longer exists. `stores` cannot reload (D3): `history.state` **is** the stale copy and survives a browser reload of the same history entry, so the shared copy's "recargá la página" would be a lie there; its copy names the real recovery instead — go back to the list and reopen. **Only `stores` stays fail-closed with no UI guard**, because only there the version signal is never advanced, so every retry re-sends the same stale version and the backend keeps rejecting it; the reloading screens advance it on purpose. This asymmetry is the one decision in this record that most deserves a reviewer's challenge. |
| **D9** | Each service carries its own private message constant, English, naming its own entity — mirroring `StoreInventorySettingsService.java:60`. | Consistent with the precedent and keeps each slice self-contained. Accepted cost: five near-identical strings. Follow-up, not this work. |
| **D10** | **The version precondition answers 412 Precondition Failed, not 409**, through a platform-level `VersionPreconditionException` beside `ConflictException`. | User decision, taken mid-feature after W2's verification found the overload: `DuplicateResourceException extends ConflictException`, and `GlobalExceptionHandler` maps that to 409 with the javadoc "ConflictException covers duplicates and invalid state transitions", so a 409 branch cannot tell a lost update from an operator renaming an entity to an existing code — it showed the version copy for a duplicate and, on W2's page, also reloaded and discarded the draft. Before the branch existed that 409 surfaced the server's real message, so the branch was a regression. 412 is the honest status for an `If-Match`-style precondition and leaves the duplicate path at 409. Applied to W1 as `c754671` and to W2 inside its slice commit, so the two branches do not diverge; W3/W4 inherit it. Chosen over a machine-readable code on `ErrorResponse`, which would have changed the error contract for every client. |

## Scope

**In**: the four edit `PUT` endpoints and their service methods; the four response contracts; the four
update-request contracts; the twelve flush sites; the four Angular read models and update payload
types; the four edit pages (version signal, payload merge, 412 branch); every fixture and spec the
`version` field forces to change; the integration tests that pin the preconditions, the version
increment and the timestamp freshness.

**Out** (each with its reason):

- **A migration.** `version` already exists on all four tables (D-none: no migration).
- **A new endpoint of any kind** — no flat `GET /api/stores/{id}` (D3), no version-aware `PATCH`.
- **A version precondition on create or enable** (D5).
- **The `stores` route/state contract.** The edit page keeps reading `history.state` and keeps
  redirecting when it is absent. No route change, no reload-on-error behavior.
- **`disableAreasOfStore` / `disableZonesOfAreas` / `disableLocationsOfZones`.** They are `void`
  cascades with no response mapping, so they carry no version or timestamp defect. Untouched.
- **The four `delete*` methods.** `void`, no response mapping. Their `save(...)` calls stay as they
  are.
- **The e2e mocks.** `e2e/mocks/api.ts` declares its own models without `version` and no e2e spec
  exercises these four screens, so a version regression stays invisible to e2e. Recorded as a
  follow-up rather than widening four slices.
- **A `version` precondition at the gateway.** `api-gateway/.../Routes.java:39,77-79` is a passthrough
  and does not inspect bodies. Nothing to change.
- **Centralizing the five message constants** (D9).
- **`life-control-app-angular/AGENTS.md` coverage table.** Its `Actual` column is not rewritten for a
  run's second decimal; the contract is the floor (`## Findings` F6 of
  `inventory-settings-conflict-ux.md`).

## Per-slice contract (identical shape in all four)

**Backend**

1. `Update<X>Request` gains a final `Long version` component, javadoc'd as an optional precondition,
   plus a constructor at the previous arity delegating `null`.
2. `<X>Response` gains a final `long version` component. The other component order is unchanged.
3. The service's `update<X>` asserts the precondition **after** the entity is loaded and **before**
   any mutation: `null` passes, non-null must equal the stored version, otherwise
   `throw new VersionPreconditionException(VERSION_CONFLICT_MESSAGE)` — **412, never 409** (D10).
4. `create<X>`, `update<X>` and `enable<X>` change `save(...)` → `saveAndFlush(...)`, with the
   flush rationale comment the settings precedent carries.
5. `toResponse` maps `entity.getVersion()`.

**Angular**

6. The read model gains `version: number;` (required, D2) and the **update** payload type gains
   `version?: number;`. For `stores`, `StoreRequest` is shared by create and update, so the optional
   field goes there; the page omits it in create mode.
7. The edit page holds `version` seeded from the entity it already loads, and merges it at the page
   boundary with the settings precedent's conditional spread, so create mode never sends the key.
8. The page's error handler gains a **412** branch (D8) and leaves **409** to the server's own message,
   so a duplicate keeps its own copy and its own recovery.

## Tasks

Projection rule: the visible `todo` list mirrors **W0–W5**, the slice-level tasks. The `T` rows are
the sub-tasks inside each slice and are tracked here, not in the `todo` projection.

| ID | Task | Files / evidence | Status |
| --- | --- | --- | --- |
| **W0-T1** | Record and decisions | this file + Engram mirror | done |
| **W1** | **Slice 1 — `stores`** | | |
| W1-T1 | Tests: integration test for the stale-version 412, the version increment, the absent-version pass-through and the fresh timestamps; controller test for the 412 envelope and for the duplicate 409; Angular specs for the payload merge, the 412 branch and the 409 fall-through | `CompanyStoreVersionPreconditionIntegrationTest` (5 tests), `CompanyStoreControllerTest`, `stores-edit.spec.ts` | done — written with the behaviour; the RED was measured retroactively by reverting the fix, see the evidence log |
| W1-T2 | Backend: `UpdateCompanyStoreRequest`, `CompanyStoreResponse`, `CompanyStoreService` (assert + 3 flushes + message constant) | 3 src files + the constructor sites in 2 controller tests | done |
| W1-T3 | Angular: `store.models.ts`, `stores-edit.ts`, and every `CompanyStore` fixture the compiler forces (incl. cross-feature specs) | 10 spec files, fixtures only | done |
| W1-T4 | Gates green on the committed tree: backend `spotlessCheck spotbugsMain` + `test`; frontend `lint` + `build` + `test:coverage:check` | evidence log rows | done — final numbers, after the 412 change: 610 suites / 2136 tests / 0 failures; 130 files / 2543 tests / 0 failures |
| W1-T5 | Work-unit commit + PR | evidence log row | done — 3 commits (`5a74b62`, `268f88d`, `c754671`) and PR #183, merged as `50826e0` |
| **W2** | **Slice 2 — `store-areas`** | | |
| W2-T1 | Tests: the same five integration behaviours, the controller 412 envelope **plus the duplicate-409 guard**, and Angular specs for the payload merge, the create key's absence, `version: 0`, the 412 recovery and the 409 fall-through | `StoreAreaVersionPreconditionIntegrationTest` (5 tests), `StoreAreaControllerTest`, `store-areas-edit.spec.ts` | done — the RED was measured retroactively by reverting the fix, see the evidence log |
| W2-T2 | Backend: `UpdateStoreAreaRequest`, `StoreAreaResponse`, `StoreAreaService` (assert + 3 flushes + message constant) | 3 src files + 4 constructor sites across 3 controller tests + `StoreAreaServiceTest`'s stub/verify rename | done |
| W2-T3 | Angular: `store-area.models.ts`, `store-areas-edit.ts` and every `StoreArea` fixture the compiler forces | 6 spec files, fixtures only | done |
| W2-T4 | Gates green on the committed tree | evidence log rows | done — 610 suites / 2135 tests / 0 failures; 130 files / 2544 tests / 0 failures |
| W2-T5 | Work-unit commit + PR | evidence log row | commit `5345a5b`; the pull request is the delivery still to open |
| **W3** | **Slice 3 — `store-zones`** | `StoreZoneService:282,336,402`, `UpdateStoreZoneRequest`, `StoreZoneResponse`, `store-zones-edit.ts`, fixtures | pending |
| **W4** | **Slice 4 — `store-locations`** | `StoreLocationService:324,381,453`, `UpdateStoreLocationRequest`, `StoreLocationResponse`, `store-locations-edit.ts`, fixtures | pending |
| **W5** | Close: terminal header with PR/merge evidence per slice, and the evidence log | this file | pending |

Each of W1–W4 repeats the same five sub-tasks (RED tests, backend, frontend, gates, commit + PR).

## Acceptance criteria (per slice)

1. `PUT` with a **stale** `version` answers **412** and writes nothing: the stored row keeps its
   previous `version`, its previous `updatedAt` and its previous field values.
2. `PUT` with the **current** `version` answers **200**, and the response's `version` equals the
   previous value **+ 1** (this is the assertion that proves the flush) and its `updatedAt` is
   **later** than the value the preceding `GET` returned (this is the assertion that pins D4).
3. `PUT` with **`version` absent** answers **200** — the old contract still works.
4. `POST` answers with `createdAt` and `updatedAt` **non-null** and `version` present.
5. `PATCH` (enable) answers with the post-flush `version` and a fresh `updatedAt`.
6. The response contract change is **additive only**: every pre-existing field keeps its name, type
   and position, so no consumer breaks.
7. Angular: the update request body carries the version read from the entity; the create request body
   **does not carry the key at all**.
8. Angular: a **412** sets the conflict copy in the page's error signal; the three flat-GET pages
   refresh their entity and version and clear their dirty guard, and `stores` does not (D8). A **409**
   — a duplicate — keeps showing the server's message on every screen and does not reload.
9. Gates green **on the committed tree** — the pre-commit hook reformats after the gate, so the
   numbers reported must come from a run on the commit, not from before it
   (`inventory-settings-conflict-ux.md` F9).

## Checks

| Control | Command | Note |
| --- | --- | --- |
| Backend format + static analysis | `cd life-control-api && ./gradlew spotlessCheck spotbugsMain --no-daemon` | |
| Backend tests | `cd life-control-api && ./gradlew test --no-daemon` | integration tests need a Docker daemon |
| Frontend lint | `cd life-control-app-angular && npm run lint` | |
| Frontend build | `cd life-control-app-angular && npm run build` | |
| Frontend tests + coverage floor | `cd life-control-app-angular && npm run test:coverage:check` | do not run two suites at once; see the timeouts note in the component `AGENTS.md` |
| E2E | `npm run test:e2e` | **N/A**: no e2e spec exercises these four screens and the mock declares no `version`, so this run cannot observe the change. Recorded as a follow-up, not claimed as a PASS |
| Security review | — | **N/A**: no auth, role, privilege, secret or data-exposure change. The endpoint role sets are untouched |
| Contract gate | — | **PASS by construction**: additive response field + optional request field, verified by criterion 6 |
| Rollback plan | — | Revert the slice's commit. No schema, no data migration, no irreversible step |

## Constraints

- **No strict-TDD mode is configured for this repository and none is discoverable** (checked
  `gentle-ai` config and the repo skills; recorded as a GAP rather than asserted). The working rule is
  the repository's own: write the test with the behaviour, and report a claim's RED honestly when it
  has none — the previous slice recorded exactly that situation as F2 instead of claiming a RED it
  did not have.
- Tests live with the code they verify, in the same commit (`work-unit-commits`).
- One writer at a time. One worktree per slice, created from a clean anchor on `main`
  (`references/worktrees.md`), cleaned in the order `herdr workspace close` → `git worktree remove` →
  `git worktree prune`.
- The pre-commit hook runs `eslint --fix` and `prettier --write` and re-stages; `*.md` is excluded
  from prettier by `.prettierignore` on purpose, so this record is hand-formatted.
- Worktree trust: `~/workspace/LifeControl-worktrees` must be the trusted parent entry for
  `.agents/skills/` to load inside a slice worktree.

## Follow-ups (not this work)

1. **Version-aware `PATCH`/enable** on the four screens, or a decision that enable never needs a
   precondition (D5).
2. **A flat `GET /api/stores/{id}`**, which would let the stores page refresh in place on 412 and
   remove the D8 asymmetry.
3. **Centralize the five per-service message constants** (D9).
4. **`e2e/mocks/api.ts` lacks `version`** on its `StoreLocationMock` and
   `StoreInventorySettingsMock`, and no e2e spec covers these screens: e2e cannot catch a version
   regression.
5. **The stale-`updatedAt` defect recorded in `odd/tasks/inventory-settings-conflict-ux.md`** is
   closed by this work for the twelve store-tree methods; the settings service's own create branch
   (`StoreInventorySettingsService.java:184` uses `save`, not `saveAndFlush`) is a fifth site and is
   **not** in this scope.
6. **The fail-closed retry of D8 is not pinned.** W1's 412 spec proves the conflict copy is set and
   that no navigation happens, but nothing re-submits after the 412 to prove the page's version signal
   stayed where it was and the retry is rejected again. It holds by static inspection only.
7. **`assertVersionPrecondition(Long, long)` is safe only because the second parameter is primitive.**
   A later refactor of the parameter to `Long storedVersion` would silently turn the comparison into
   reference equality. A primitive-typed signature removes the trap.
8. **The settings precedent still answers 409 for its version precondition.** `StoreInventorySettingsService`
   throws `ConflictException`, so that screen keeps exactly the overload D10 removed from the store
   tree: a duplicate on it would show the version copy. It predates this feature (PR #172) and is not
   in this scope; the honest fix is the same `VersionPreconditionException`, which already exists. Its
   page also uses the shared copy in `http-error-message.ts`, which carries a 409 entry and no 412 one.

## Evidence log

| Date | Event | Evidence |
| --- | --- | --- |
| 2026-09-26 | W0 closed: record created from a read-only scouting round over the four screens, the settings precedent and the repo conventions | this file; scouting report enumerated the 12 flush sites, the 4 `@Version` entities, the 16 backend test constructor sites and the ~20 Angular fixture files. No source byte was read unverified from memory |
| 2026-09-26 | Decisions D1–D9 taken by the user (D1–D4) and by the orchestrator with recorded rationale (D5–D9) | this table |
| 2026-09-26 | **W1 committed as `5a74b62`** (`fix(stores): enforce the version precondition on the store write path`), 20 files, +541 −26: 3 backend src files, 5 backend test files, `store.models.ts`, `stores-edit.ts` and 10 spec fixtures. The pre-commit hook ran `eslint --fix` + `prettier --write` over the 13 staged `.ts` files and **changed no byte**: `sha256sum -c` over the 20 files, taken before the gate run, still matches after the commit, so the gate evidence below belongs to exactly the committed tree | `git log -1`; the sha256 comparison before/after the commit |
| 2026-09-26 | **W1 gates green on those bytes.** Backend `./gradlew spotlessCheck spotbugsMain cleanTest test --no-daemon` → BUILD SUCCESSFUL in 1m16s; aggregated JUnit XML **610 suites / 2135 tests / 0 failures / 0 errors / 0 skipped**. Frontend `lint` exit 0, `build` exit 0, `test:coverage:check` → **130 files / 2542 tests / 0 failures / 0 errors / 0 skipped**, coverage **94.08 / 76.00 / 89.32 / 94.08** against the 80 / 60 / 75 / 80 thresholds | the five runs; XML under `life-control-api/build/test-results/test/` |
| 2026-09-26 | **Independent read-only verification round** (delegated, over the uncommitted diff and the nine acceptance criteria): 0 blocking findings, all nine criteria met, contract items 1–8 met, D7 and D8 confirmed. It found a real defect in the new test itself — `staleVersionPutAnswers409AndWritesNothing` (renamed to `…412…` when D10 moved the status) asserted the absolute literal `version == 1`, but the PostgreSQL container is a singleton with no per-test reset and the seed is find-or-create, so it passed by execution order rather than by behaviour. Fixed in the same change, together with the unpinned criterion 5 (the enable `PATCH`, now a fifth integration test) and the flush rationale missing from the create path | the verification report; `CompanyStoreVersionPreconditionIntegrationTest` |
| 2026-09-26 | **RED reconstructed, not observed in order.** This slice's tests and its fix were written in the same uncommitted sitting before this session, so no failing run existed. Measured by reverting the fix and restoring it byte for byte (`sha256sum -c` identical afterwards). Backend, with `CompanyStoreService` + `CompanyStoreResponse` + the two controller tests at their pre-fix state → **5 tests completed, 4 failed** (`staleVersionPut…`, `currentVersionPut…`, `postAnswers…`, `enablePatch…`), the absent-version pass-through correctly still green because it pins the old contract. Frontend, with only `stores-edit.ts` reverted → **3 failed / 2539 passed of 2542**, and one of the three is a **pre-existing** spec the change makes stricter (`should call updateStore on save when in edit mode`), which is the evidence that the new body assertion is load-bearing and not merely additive | the two runs; the tree restored clean |
| 2026-09-26 | **W1 delivered: PR #183 merged as `50826e0`** (parents `acf6060` + `c754671`), branch and worktree removed. Post-merge CI on `main`: Gateway CI and Docker Build Integrity green, API CI and Angular CI still in flight when this row was written. **The tree-identity check used in earlier slices does not apply here and was corrected**: `main^{tree}` ≠ `c754671^{tree}` is *expected*, because the branch was cut before `main` advanced by #181/#182, so `c754671` does not contain those two doc changes. The right check is file-level: `main` is identical to `c754671` in **all 23 files the PR touched** (0 differing), the files it did *not* touch are byte-identical to `acf6060`, and `8757c85..c754671` and `acf6060..main` are the same 23 files / +865 −26 | `gh pr view 183`; `git diff --name-only c754671 main`; `gh run list --branch main` |
| 2026-09-26 | **D10 taken by the user**: the precondition moves from 409 to 412. Applied to both written slices in the same session — W1 as `c754671`, pushed onto the open PR so its CI re-ran green (API 2m45s, Angular 4m15s), and W2 inside its slice commit — with a controller test per slice pinning that a duplicate **still** answers 409 with its own message | D10; the two commits |
| 2026-09-26 | **W2 committed as `5345a5b`** (`fix(store-areas): enforce the version precondition with its own 412`), 18 files, +671 −40: 3 backend src files, the new exception, the handler, 5 backend test files, `store-area.models.ts`, `store-areas-edit.ts` and 6 spec fixtures. The pre-commit hook changed no byte (`sha256sum -c` over the 18 files, taken before the gate run, identical after the commit) | `git log -1`; the sha256 comparison |
| 2026-09-26 | **W2 gates green on those bytes.** Backend `spotlessCheck spotbugsMain cleanTest test` → BUILD SUCCESSFUL, **610 suites / 2135 tests / 0 failures / 0 errors / 0 skipped**. Frontend `lint` exit 0, `build` exit 0, `test:coverage:check` → **130 files / 2544 tests / 0 failures / 0 errors / 0 skipped**, coverage **94.08 / 76.00 / 89.33 / 94.08** | the five runs; XML under `life-control-api/build/test-results/test/` |
| 2026-09-26 | **W2 independent read-only verification round** (delegated, over the uncommitted diff): 0 blocking findings, all nine criteria met, contract items 1–8 met, D7 confirmed, D8 confirmed as a reload that **does** replace the operator's draft. It found, and the slice fixed in the same commit: the overloaded 409 (escalated and decided as D10), the form staying `dirty` after the recovery reload so the route guard offered to discard values that already matched the server, the unpinned `version: 0` boundary (the sentinel is `null`, not falsiness), and the `before + 1` assertions that depended on a name literal not being reused by a sibling method — each accepted write now changes a UUID-suffixed name, because Hibernate only bumps `@Version` when it issues an UPDATE | the verification report; the specs it named |
| 2026-09-26 | **W2 RED reconstructed, not observed in order.** Backend, with `StoreAreaResponse` + `StoreAreaService` + the 4 controller/service tests at their `main` state (keeping the new request DTO, which the test needs in order to compile) → **5 tests completed, 4 failed**, the absent-version pass-through correctly still green. Frontend, with only `store-areas-edit.ts` reverted → **4 failed / 2540 passed of 2544**, and two of the four are **pre-existing** specs the change makes stricter (`should update using the chain from the response` and the update-body expectation) | the two runs; the tree restored byte for byte |
