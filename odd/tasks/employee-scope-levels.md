# Employee scope levels

**Status**: implemented — T1–T13 are on this branch; nothing is blocked, and the follow-ups the log
names (renaming `EmployeeStoreScope`, restricting an HR assigner to their own scope) are separate work
by decision.

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

**S11 — Docs follow the behavior.** The frontend and backend component guides describe the merged tab,
the four assignable levels and the derived company root; no guide keeps naming a `Tiendas` tab.

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | `V32__employee_scope_assignments_store_level.sql`: `company_store_id` + FK, four-node `num_nonnulls` check, fourth per-level exclusion constraint, index, backfill with a fail-closed row-count guard | S2, S6 | inline | |
| T2 | `EmployeeScopeAssignment` entity `companyStore`, store-aware repository finders | S2, S5 | inline | |
| T3 | `ScopeAssignmentRequest.companyStoreId`; resolve/close/`levelOf`/`nodeName`/response for the store level | S2, S7 | inline | |
| T4 | `ScopeAssignmentDerivation` emits `companyStoreIds` for a `STORE` row from its full chain, and the union folds to this one source | S2, S5 | inline | |
| T5 | Store surface as a façade over the scope table (`/store-assignments` unchanged) and `ProfileService` reading it | S8, S10 | inline | |
| T6 | Backend tests: migration schema, derivation per level, service create/close/overlap, controller gate, profile | S5, S6, S8, S10 | inline | |
| T7 | Frontend models and service: five-level model, store payload, one row shape for the merged table | S2, S3 | inline | |
| T8 | Merged presentational section: `Nivel` column, derived `Empresa` root row, one CTA, empty state that explains scope ≠ permission | S1, S3, S4 | inline | |
| T9 | One assign dialog: level selector plus the four cascades | S7 | inline | |
| T10 | `employee-detail`: one tab, one read, `?tab=tiendas` compatibility, partial-activation copy | S1, S9 | inline | |
| T11 | `contract-dialog` through the scope service; delete the store section, dialog, service and models | S1, S9 | inline | |
| T12 | Cleanup: `V33` drops `employee_store_assignments`, delete the store entity/repository/service/controller/DTOs, `StoreScopeDerivation` and the union | S2, S5, S8 | inline | |
| T13 | Docs and this record | S11 | inline | |

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
