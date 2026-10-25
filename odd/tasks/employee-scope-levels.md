# Employee scope levels

**Status**: implemented — T1–T14 are on this branch; nothing is blocked, and the follow-ups the log
names (renaming `EmployeeStoreScope`, restricting an HR assigner to their own scope) are separate work
by decision. The work ships as three reviewable slices and three chained PRs (L5).

**Created**: 2026-10-23 · **Risk**: high — item 1 and item 2: the unit migrates rows that feed an
authorization decision (the five `company_*` claims) into another table and changes the write surface
that grants tenancy scope.

## Specs

**S1 — One tab, named `Alcance`, replaces `Tiendas` and `Alcance`.** The user's requirement:

> se pueden fusionar las pestañas de Tiendas y Alcance para que quede solo alcance

The tab list becomes `Detalle`, `Contratos`, `Alcance`, `Accesos`. `?tab=` keeps its contract: `tiendas`
now resolves to `alcance` instead of falling back to `detalle`, so every existing deep link and the
partial-activation message that names the store tab keep landing on a screen that can act.

**S2 — `Tienda` is a level of `Alcance`, not a separate table.** Verbatim:

> dentro de alcance agregar el nivel alcance de Tienda

The store fact moves into `employee_scope_assignments` as a fourth node column (`company_store_id`):
one row per assigned store, the row's own `enabled` and the `[)` date range deciding whether it
contributes, a store assigned at its own level and closed the same way. `employee_store_assignments`
stops being a source: every live row is copied and the table is dropped.

**S3 — `Empresa` is the derived root of the hierarchy, never an assignable row.** Verbatim, the
requirement:

> adicional tambien quiero agregar el nivel de alcance por Empresa

Decision (L2, the user's own answer): the level is **shown**, never assigned. There is no company node
column, no company level row and no company option in the assign dialog, because `company_id` is
required, exactly one, and comes from `employees.company_id` — a row would be a second copy of a fact
the employee already carries, and the derivation's `CompanyScopeInvariantException` refuses anything
but exactly one company id. The screen renders one derived row per employee naming the company, so
"the tree starts at the company" and "nobody assigned anything" stop looking the same.

**S4 — Scope is not permission, and the screen must not imply otherwise.** The tab shows the tenancy a
person is bounded by. What enables a role is the role itself (`lc-company`, `lc-company-country`,
`lc-company-region`, `lc-company-zone`, `lc-company-store`, …), granted by a position template and an
approval, and `lc-company` needs no assignment at all because the company claim already bounds it. The
copy on the section says so; the derived-chain column keeps its positional meaning and renders an
unresolved level as an em dash **in place**, never dropped.

**S5 — The claim surface does not change.** The five claims (`company_id`, `company_country_id`,
`company_region_id`, `company_zone_id`, `company_store_id`) keep their names, their mapper, their
`ScopeLevel` entries and their guard. One derivation feeds all five, with the rules it already has: a
row contributes only while its own `enabled` is true and it covers today; a chain that does not resolve
contributes nothing rather than a partial chain; a revoked (`Terminated` or soft-deleted) employee
derives five empty sets; the derivation fails closed when the company set is not exactly one id.

**S6 — The migration preserves every store row or aborts.** The copy runs inside the migration and a
row-count guard turns a partial copy into a failed migration instead of a silent loss of tenancy; a
store row that cannot be copied aborts for the same reason. Nothing else about a copied row changes:
its dates, its `enabled` flag and its close state survive verbatim.

**S7 — One call to action and one dialog.** `Asignar alcance` opens a single dialog whose level
selector offers `País`, `Región`, `Zona` and `Tienda`; choosing a level hides and clears the levels
below it, so the form can never submit two nodes. The level the dialog preselects is an affordance
taken from the employee's own scoped roles, never a gate: the role that suggests `Tienda` is
`lc-company-store`, the three existing hints stay, and the server remains the only authority.

**S8 — `GET`/`POST`/`PATCH /store-assignments` keep answering unchanged** until the frontend slice
lands, so the branch is deployable at every commit; they answer from the scope table and close rows the
same way. Once the app no longer calls them, the surface is removed.

**S9 — The contract dialog's optional store assignment keeps working.** A `Nuevo contrato` with a
chosen store still writes the contract and then the store, still reports the partial outcome when the
second write fails, and still points at the tab that can retry it — now `Alcance`.

**S10 — The profile's assigned-store selector keeps working.** `ProfileService` reads the employee's
current stores from the scope table; the response shape the profile renders does not change.

**S11 — Docs follow the behavior.** The guide that documents this surface is the frontend one
(`life-control-app-angular/AGENTS.md`), which carries the RBAC matrix and the scope section: after this
unit it describes the merged tab, the four assignable levels and the derived company root, and it no
longer names a `Tiendas` tab. The backend component guide has **no** section for this surface and gains
none — measured, a grep for the tab, the route, the table and the retired classes over
`life-control-api/AGENTS.md` returns nothing, so there is nothing to correct there, and writing a new
section would be a claim rather than a correction (L8).

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | `V32__employee_scope_assignments_store_level.sql`: `company_store_id` + FK, four-node `num_nonnulls` check, fourth per-level exclusion constraint, index, backfill with a fail-closed row-count guard | S2, S6 | inline | `f5f88158` |
| T2 | `EmployeeScopeAssignment` entity `companyStore`, store-aware repository finders | S2, S5 | inline | `f5f88158` |
| T3 | `ScopeAssignmentRequest.companyStoreId`; resolve/close/`levelOf`/`nodeName`/response for the store level | S2, S7 | inline | `c59f98a2` |
| T4 | `ScopeAssignmentDerivation` emits `companyStoreIds` for a `STORE` row from its full chain, and the union folds to this one source | S2, S5 | inline | `c59f98a2` |
| T5 | Store surface as a façade over the scope table (`/store-assignments` unchanged) and `ProfileService` reading it | S8, S10 | inline | `78b3fb62` |
| T6 | Backend tests: migration schema, derivation per level, service create/close/overlap, controller gate, profile | S5, S6, S8, S10 | inline | `f5f88158` `c59f98a2` `78b3fb62` |
| T7 | Frontend models and service: five-level model, store payload, one row shape for the merged table | S2, S3 | inline | `e3e41d1c` |
| T8 | Merged presentational section: `Nivel` column, derived `Empresa` root row, one CTA, empty state that explains scope ≠ permission | S1, S3, S4 | inline | `e3e41d1c` |
| T9 | One assign dialog: level selector plus the four cascades | S7 | inline | `e3e41d1c` |
| T10 | `employee-detail`: one tab, one read, `?tab=tiendas` compatibility, partial-activation copy | S1, S9 | inline | `e3e41d1c` |
| T11 | `contract-dialog` through the scope service; delete the store section, dialog, service and models | S1, S9 | inline | `e3e41d1c` |
| T12 | Cleanup: `V33` drops `employee_store_assignments`, delete the store entity/repository/service/controller/DTOs, `StoreScopeDerivation` and the union | S2, S5, S8 | inline | `03a655a1` |
| T13 | Docs and this record | S11 | inline | `7b02efb7` |
| T14 | Restore the two coverage rules the retired suites had and nothing covered after them (a store row whose chain does not resolve; the store level over HTTP), plus the disabled-duplicate backfill case and the store create/close service cases | S2, S5, S6, S7 | inline | `a215472a` |

**Invariants carried, not invented**: exactly one `company_id` in the derivation; a row contributes
only while enabled and covering today; a chain that does not resolve contributes nothing; an empty
level deletes its Keycloak attribute instead of writing it empty; a revoked employee has all five keys
deleted; a move is a new assignment and a mistake is closed, never rewritten.

**Deferred, on purpose**: renaming `EmployeeStoreScope` to a name that does not say "store" (mechanical
but wide); restricting an `lc-employee` assigner to nodes inside their own derived scope (the accepted
consequence of `scope-assignments-per-level` L8).

## Log

- **L1** — 2026-10-23, verbatim: *"en la parte de administracion de empleados /hr/employees/ se pueden
  fusionar las pestañas de Tiendas y Alcance para que quede solo alcance y dentro de alcance agregar el
  nivel alcance de Tienda y adicional tambien quiero agregar el nivel de alcance por Empresa, que
  sugieres"* — the request that opened the unit.
- **L2** — decision, the user's own answers to the two design questions this unit could not settle:
  **Empresa = raíz derivada, no asignable** (no row, no column: `company_id` is the employee's and
  exactly one) and **Tienda = migrar a la tabla de alcance** (the follow-up `scope-assignments-per-level`
  deferred as S5), instead of merging the two reads in the UI only. The measured cost of the second
  choice is what makes this unit high risk.
- **L3** — decision, S8: the store HTTP surface stays until the app stops calling it. The alternative —
  removing it in the backend slice — would leave `main` with a frontend calling a route that no longer
  exists between two chained PRs, and this repository deploys the stack as a whole.
- **L4** — decision, S3: the derived company row is presentation, computed in the page from the company
  the employee already belongs to. It changes no DTO: the read models keep describing assigned rows
  only, so no client can mistake the root for an assignment.

- **L5** — decision, delivery: the change is cut into three slices and three chained PRs, each one
  deployable on its own, because the whole unit is far past the review budget. **Slice 1** (backend,
  `f5f88158` + `c59f98a2` + `78b3fb62`) makes the store a level of the scope table while
  `/store-assignments` keeps answering as a façade, so `main` deploys with the app it has. **Slice 2**
  (frontend, `e3e41d1c`) merges the tab and stops calling the façade. **Slice 3** (cleanup,
  `03a655a1`) drops the old table and the retired stack, which is only safe after slice 2 merged.
  Inverting slices 1 and 3 would leave `main` with an app calling a route that no longer exists —
  this stack is deployed as a whole.
- **L6** — evidence, the checks that were actually run. Backend: `./gradlew test` → **3911 tests, 0
  failures** (the suite grew from 1140 for the `hr`/`profile`/`provisioning` slice alone), and
  `./gradlew spotlessCheck` → clean. The two middle backend slices were compiled **in isolation** in
  throwaway worktrees, so each commit in the chain builds on its own. Frontend: `npm test` → **192
  test files passed**, `npm run lint` → all files pass linting, `npm run build` → bundle generated.
  Not run: `docker/scripts/verify-provisioning-flow.sh` end to end — it repointed both `/store-
  assignments` calls to `/scope-assignments` and only `bash -n` could be checked without the dev
  stack, Keycloak, Mailpit and the worker running.
- **L7** — carry-over, recorded rather than fixed: two suites (`GoodsReceiptIntegrationTest`,
  `ProductCategorySchemaMigrationIntegrationTest`) still pinned the Flyway head at `"31"` and were
  already red behind V32; they now pin the head it is. `EmployeeStoreScope` still says "store" in its
  name while it carries all five levels; the rename is deferred on purpose, because it touches every
  call site and changes no behaviour.
- **L8** — requirement correction, S11: the spec as first written asked **both** component guides to
  describe the surface. Measured, the backend guide never documented it — no section names the scope
  table, the route or the tab — so the spec now states what the unit actually owes: the frontend guide
  corrected, the backend guide untouched. Recorded rather than silently dropped, and no new
  documentation section was invented to satisfy a spec that had overreached.
- **L9** — evidence, independent verification. The verifier returned PASS on S1–S10 and FAIL on S11's
  backend half (resolved in L8), and found two rules that the retired store suites had covered and that
  nothing covered afterwards: a store row whose chain does not resolve contributes nothing, and the
  store level over HTTP (201 with its level, name and derived chain; a store of another company is a
  404 that writes nothing; an overlap is a 409). `T14` restores both, plus the disabled-duplicate
  backfill case the row-count guard's own comment claims is safe and the store create/close service
  cases. It also reported, and this record keeps: no assertion anywhere in the diff was weakened; the
  frontend commit's `Risk: none` holds (no remaining importer, the URL alias resolves); the repointed
  provisioning driver's payload binds `ScopeAssignmentRequest` so the API would accept it, with its
  runtime still unproven; and its own `npm test` run showed a dozen files failing one test each while
  the backend suite ran concurrently, with `employee-detail.spec.ts` passing 79/79 in isolation —
  load-sensitive duration, not a reproducible defect, and the clean 192-file run recorded in L6 stands.
  Final counts after T14: backend `./gradlew test --tests 'com.lifecontrol.api.hr.*' --tests
  'com.lifecontrol.api.profile.*' --tests 'com.lifecontrol.api.provisioning.*'` → 1090 tests, 0
  failures, and `./gradlew spotlessCheck` → clean.
- **L10** — the branch was rebased onto `origin/main` after PR #616 merged
  (`fix/purchases`: a line price with more than two decimals, no migration involved), so every commit
  citation in this record is the hash the branch carries **now**, not the one it was written with. The
  rebase touched no file of this unit and every check was re-run afterwards rather than assumed to
  still hold: `./gradlew test spotlessCheck` on the new base → **3940 tests, 0 failures, 0 errors, 0
  skipped**, spotless clean. That count is higher than L6's 3911 for two honest reasons — `T14` added
  tests to this unit, and the merged purchases change added its own — so neither number contradicts the
  other; each is the measurement of the tree it was taken on. The frontend tree is byte-identical
  across the rebase (no incoming commit touched `life-control-app-angular/`), so its 192-file run and
  the lint/build results stand unchanged.
- **L11** — evidence, a defect the first CI run caught and this record keeps. Slice 1's `Static analysis`
  failed on `spotlessJavaCheck`: the formatting fix for `EmployeeScopeAssignmentService.resolveNode` was
  introduced by slice 1 and only applied in slice 3, where `spotlessApply` had swept it up — so the first
  slice did not pass its own CI even though the branch tip did. The fix was folded into the commit that
  introduced it and the two later slices were rebased onto it; the final tree is **byte-identical** to the
  one verified in L10 (`git diff` between the pre-rewrite and post-rewrite tips prints nothing), so no
  behaviour was re-verified by assertion, and the hashes cited above are the ones the branches carry
  after the rewrite. The lesson this unit pays for: `spotlessCheck` belongs to the slice that adds the
  line, so a slice boundary must be checked per slice, not only at the branch tip.
