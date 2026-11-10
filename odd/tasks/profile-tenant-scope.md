# profile-tenant-scope

Self-service profile reads the tenant tables it needs. `GET /api/profile` is a tenant-agnostic path,
so `TenantScope` carries no company on it, and the two policy-armed reads it performs (`employees`,
`company_stores`) returned zero rows: the saved store preference never came back as a preselected
chain, `assignedStores` was `null` for every caller, and the store-assignment guard never refused.

**Status**: implemented — the fix, the integration suite that pins it and this record are the unit;
nothing is deferred.
**Created**: 2026-11-10 · **Risk**: high — item 2 (tenancy scope and row-level security) and item 3
(`GET`/`PUT /api/profile` behavior other screens read).

## Specs

- **S1** — Re-entering `/profile?edit=true` shows the saved chain preselected. The user's report,
  verbatim: «segunya se guardo la configuracion en /profile?edit=true pero cuando vuelvo a entrar no
  aparecen los campos preseleccionados». `GET /api/profile` must therefore report the resolved store
  chain in `location`, not `null`, for a caller whose token claims the company that owns the stored
  store preference.
- **S2** — `assignedStores` reports the caller's current store assignments, so a caller with an
  employee row and a covering store assignment is in the constrained mode the screen already renders
  (`null` = free mode stays the answer for a caller with no employee row). Derived from the chosen
  route, quoted verbatim: «B — servicio, completo de una» — «Cubre todo, toca el mecanismo de tenancy
  y es más trabajo».
- **S3** — Fail-closed is preserved: a caller whose token claims no company reads the profile
  successfully with `location: null` and `assignedStores: null`. No 403, no 500, and nothing invented.
- **S4** — No widening: `location` resolves only through a company the token claims, and a store
  preference outside the caller's assignments is refused by `PUT /api/profile` again with the
  existing 404 — the guard `ProfileService.verifyStoreIsAssigned` exists for.
- **S5** — A caller whose `company_id` claim carries more than one company still resolves, whichever
  of the claimed companies owns the store. This is the case the filter shortcut cannot answer and the
  reason the scope is stated by the profile path itself.

## Tasks

| ID | What | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | RED: integration suite over `/api/profile` with a real token, real PostgreSQL and the armed policies | S1–S5 | inline | `5799329b` |
| T2 | `ProfileTenantReads`: state each claimed company around its own transaction, first hit wins | S1, S2, S5 | inline | `5799329b` |
| T3 | `ProfileLocationResolver` derives the chain through T2 and keeps its degrade-not-fail rule | S1, S3, S4 | inline | `5799329b` |
| T4 | `ProfileService.resolveAssignedStores` reads the employee row through T2, restoring the guard | S2, S4 | inline | `5799329b` |
| T5 | Suite green: focused class, then `./gradlew test` | S1–S5 | inline | `5799329b` |
| T6 | This record and its evidence | — | inline | `docs(odd)` |

**Slice**: `5799329b` is 745 authored lines in one commit, past the ~400-line review budget. It is not
split because the 539 lines of `ProfileTenantScopeIntegrationTest` are the proof of the single mechanism
the other 206 lines implement — a fix and its only RED-able evidence, not two units. The reviewer-facing
cost is that suite; the production change is 108 new lines plus 74 changed.

## Evidence log

- **2026-11-10 · RED** (`./gradlew test --tests 'com.lifecontrol.api.profile.*'`, 61 tests, 4 failed):
  S1 and S5 `No value at JSON path "$.location.companyStoreId"` (location was `null`), S2
  `JSON path "$.assignedStores.length()" expected:<1> but was:<null>`, S4b
  `Status expected:<404> but was:<200>` — the guard never ran because the assignment set was always empty.
- **2026-11-10 · GREEN** (`--rerun-tasks`): the six HTTP acceptance cases pass, whole focused set 61
  tests 0 failures. `spotlessCheck spotbugsMain` green; `spotlessApply` reformatted nothing outside the
  profile package.
- **2026-11-10 · independent verification** (`gentle-ai-verify`, high risk): PASS on no widening, on
  the `setRollbackOnly` necessity (removing it turns S4a into a 500 — `UnexpectedRollbackException`
  escaped from the participating store read), on the boundary order and on the restored guard. Two
  coverage gaps it flagged were closed as tests: a deterministic continue-after-a-miss case (S5) and the
  present-but-empty assignment set over the real request path (S4).
- **2026-11-10 · two more cases**, closing what the verification flagged: the continue-after-a-miss
  branch now has a deterministic case (two literal company ids whose sorted order puts the miss first,
  S5) and the present-but-empty assignment set is pinned over the real request path instead of only
  through the unit stub (S4). `ProfileTenantScopeIntegrationTest` is 8 HTTP cases; the focused set is 63
  tests, 0 failures.
- **2026-11-10 · full suite**: `cd life-control-api && ./gradlew test --no-daemon` → BUILD SUCCESSFUL in
  17m19s, exit 0, on the production code this record ships. The two cases added afterwards are tests of
  the same class the focused run covers; no production line changed after that run.
- Advisories kept open on purpose, each documented where it lives: `TenantScope.with` states the company
  half only, so a future read of a subject-armed table inside an attempt would see nothing
  (`ProfileTenantReads` javadoc); `underEachClaimedCompany` is for reads only and an attempt that answers
  nothing is rolled back; a multi-claim `GET` opens up to one transaction per claimed company per read.

## Log

- **L1**: «segunya se guardo la configuracion en /profile?edit=true pero cuando vuelvo a entrar no
  aparecen los campos preseleccionados puedes revisar eso y luego volvemos con lo del stock por tienda
  o puede que vaya por ahi»
- **L2**: «B — servicio, completo de una» — the full route, chosen over the filter shortcut.
- **L3** (diagnosis, measured): the preference row persisted
  (`user_preferences.company_store_id`, `updated_at 2026-11-09 22:10`), the API logged
  `Store preference d5b78f25-… is no longer readable; reporting no location` on every read, and the
  policy was proven directly on the dev database — as `lifecontrol_app`, `set_user_scope(sub)` alone
  sees **0** `company_stores` rows for the store, `set_tenant_scope(company)` sees **1**.
- **L4** (why no test caught it): `TenantScopeBoundaryIntegrationTest` pins the boundary hook with the
  scope **already stated** (`TenantScope.with(...)`), so the machinery was verified and the real
  request path — the tenant-agnostic `/api/profile`, which states the subject alone — was not.
