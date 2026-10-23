# Scope assignments per level

**Status**: implemented — T1–T9 are on the branch in ten commits; nothing is blocked, and the three
follow-ups the log names (migrating the store table, restricting an HR assigner to their own scope, an
HTTP-level test of the new surface) are separate work by decision.

**Created**: 2026-10-21 · **Risk**: high — item 1 (a new table whose rows feed an authorization
decision) and item 2 (a new capability that grants tenancy scope, on an endpoint HR self-service can
reach); the component guide's RBAC section changes under item 3, while the countries allow-list gap it
documents is **not** widened here.

## Specs

**S1 — The per-level view already works and must not regress.** The user's requirement:

> si tu rol es lc_company puedas ver todo el arbol de tus compañias asignadas paises de tienda,
> regiones, zonas y tiendas y uno con el rol de lc_company_country pueda ver todo el subarbol de los
> company contries asignados y asi hasta la tienda

This is satisfied today by five independent service branches — `CompanyService.findCompaniesForCurrentUser:82`
(`company_id` for every non-admin), `CompanyCountryService:58-68`, `CompanyRegionService:74-84`,
`CompanyZoneService:81-91`, `CompanyStoreService:104-115` — where each list filters by the claim of
**its own** level when the caller holds that level's role, and returns the whole subtree when the role
is a level above. No list service, no guard and no `ScopeLevel` entry changes in this unit.

**S2 — A level can be assigned, and it produces the claim.** The question this unit answers:

> como o donde se configura las regiones a las que tiene acceso?

Today the five `company_*` claims have exactly one source: `employee_store_assignments`, through
`StoreScopeDerivation` (which resolves `store → zone → region → country`). An employee with no store
assignment therefore has no `company_country_id`, `company_region_id` or `company_zone_id` at all, so a
holder of `lc-company-country`, `lc-company-region` or `lc-company-zone` sees nothing and has nowhere
to be assigned. The unit adds an employee scope assignment at the role's own level (country, region,
zone) whose derived chain feeds the same five attributes through the existing
`AccessProvisioningMembershipService` → Keycloak attribute → realm mapper → token path.

**S3 — One person belongs to one company; the company level is not assignable.** Verbatim:

> una persona solo pertenece a una empresa

Consequence: `company_id` keeps deriving from `employees.company_id` and keeps being **exactly one**
(`CompanyScopeInvariantException` stays as it is), `lc-company` needs no assignment row because the
company claim already bounds it, and no level `COMPANY` row exists in the new table. Multi-company is
**not** in scope and is not a scoping change: it would be a change to the employee model itself.

**S4 — Admin and HR assign.** Verbatim:

> la asignacion la hace el admin y el de HR

So the write gate is `lc-admin` + `lc-employee`, the same pair `EmployeeStoreAssignmentController:66`
already carries. The accepted consequence is recorded in the log (L8): the scope write is not
approval-gated, while the role grant it complements is.

**S5 — The store level keeps its own table.** `employee_store_assignments` (V23) is not migrated,
renamed or dropped by this unit. A store assignment keeps deriving store+zone+region+country exactly
as today, and the derivation **unions** the two sources. The trade-off (two tables for the same class
of fact) is logged as a deferral, not as a gap.

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | `V26__employee_scope_assignments.sql`: three nullable node columns + `num_nonnulls(...) = 1` + three real FKs + **three** per-level partial `daterange '[)'` exclusions, in V23's shape (see L17: the level column the plan sketched cannot carry a real FK, and one exclusion cannot span three alternative columns) | S2, S5 | inline | `a71e4d2` |
| T2 | `EmployeeScopeAssignment` entity + repository (`findEnabled…CoveringDate`) + `EmployeeScopeAssignmentService` (create closes the previous row for the same node, close-only mutation, company-ownership check → 404) | S2, S4 | inline | `a71e4d2` `f7b8c35` |
| T3 | `EmployeeScopeAssignmentController`: `GET` / `POST` / `PATCH …/{id}/close` under `/api/companies/{companyId}/employees/{employeeId}/scope-assignments`, `@PreAuthorize` `lc-admin`/`lc-employee`, DTOs with the derived chain | S2, S4 | inline | `f7b8c35` |
| T4 | Derivation: a new pure function for one scope row (node + its ancestors, never a partial chain) plus the union with `StoreScopeDerivation`; the existing function is not modified | S2, S5 | inline | `e414762` |
| T5 | `AccessProvisioningMembershipService.derivedScope` reads both sources; the read path used by the Access section keeps sharing the write path's derivation | S2 | inline | `8998ca5` |
| T6 | Trigger: the new service calls `AccessProvisioningProducer.onMembershipChanged`, the same hook `EmployeeStoreAssignmentService:241,287` uses | S2 | inline | `f7b8c35` |
| T7 | Backend tests: derivation (each level, ancestor chain, union, empty case, terminated employee), service (overlap, close-once, foreign company → 404), controller (`@PreAuthorize` sets pinned) | S2, S3, S4 | inline | `c55c1d7` `e414762` `f7b8c35` |
| T8 | Frontend: wire models + service + a presentational section and its dialog in the employee detail, with the level preselected and ordered from the employee's scoped role (an affordance, **never** a gate), and the same derived-chain column `store-assignments-section` already shows | S2 | inline | `86ce5c0` `334b855` `735bbc0` |
| T9 | Docs: the RBAC matrix (`life-control-app-angular/AGENTS.md:904`) states that a level's role sees its whole subtree and that the scope is assigned per level | S1, S2 | inline | `735bbc0` |

**Invariants carried, not invented** (each one is an existing rule this unit must extend and not
break): exactly one `company_id` in the derivation; a row contributes only while enabled and covering
today; a chain that does not resolve contributes nothing rather than a partial chain; an empty level
**deletes** its Keycloak attribute instead of writing it empty; a `Terminated` employee has all five
keys deleted; nothing is duplicated in the new table that the tree can answer.

**Deferred, on purpose**: migrating `employee_store_assignments` into the new table as level `STORE`
(S5); restricting an `lc-employee` assigner to nodes inside their own derived scope (L8); the
`GET /companies/{id}/countries` allow-list gap for `lc-company-region`, which is pre-existing and stays
a separate work unit because it changes who may read an existing endpoint.

## Log

- **L1** — 2026-10-21, verbatim: *"si a un usuario se le da por ejemplo el rol d lc-company-region como
  o donde se configura las regiones a las que tiene acceso?"* — the question that opened the unit. The
  answer measured in the tree: nowhere. The claim can only come from a store assignment.
- **L2** — verbatim: *"si evalua que implica"* — the evaluation that produced the option set: five
  design decisions (re-parenting, write gate, subtree semantics, the countries selector, union versus
  exclusivity) and the finding that no Keycloak role, mapper or claim is needed.
- **L3** — verbatim: *"explicame como quedaria la funcionalidad,como se asignarian las regiones a las
  que tenga acceso?"* — the end-to-end flow: section in the employee detail, cascade with the company
  fixed, derived-chain column, `RECONCILE` → attribute → token.
- **L4** — verbatim, and the correction that reframed the unit: *"la idea seria aplicar los permisos en
  cada nivel ahorita solo hablo de regiones, pero la idea es que si tu rol es lc_company puedas ver
  todo el arbol de tus compañias asignadas paises de tienda, regiones, zonas y tiendas y uno con el rol
  de lc_company_country pueda ver todo el subarbol de los company contries asignados y asi hasta la
  tienda"* — so the read scoping is **already** the desired behaviour (S1) and only the assignment side
  is missing (S2). An earlier evaluation in this session had flagged that behaviour as an over-grant;
  with this definition it is the rule, and the correction is recorded rather than silently dropped.
- **L5** — verbatim: *"una persona solo pertenece a una empresa y la asignacion la hace el admin y el
  de HR"* — settles both open decisions: S3 (no company level, no multi-company) and S4 (`lc-admin` +
  `lc-employee`).
- **L6** — decision, S3: the `COMPANY` level is absent from the new table. `employees.company_id` is the
  company fact and `CompanyScopeInvariantException` requires exactly one company id, so an assignable
  company level would either duplicate that fact or force the invariant open. Multi-company is left
  out of the unit as a model change, not a scoping change.
- **L7** — decision, S5: the new table covers `COUNTRY`, `REGION` and `ZONE` only, and the store
  assignment keeps its table. This avoids a migration of authorization data (risk items 1 and 2) at the
  cost of two tables carrying comparable facts; the union lives in the derivation, so the projection and
  the token contract stay single-sourced.
- **L8** — decision, S4, with its accepted consequence stated: `lc-employee` may assign scope without a
  second pair of eyes, while the role that makes the scope usable is approval-gated by
  `AccessProvisioningApprovalPolicy`. A holder of both `lc-employee` and a scoped role can therefore
  widen their own scope horizontally. The mitigation considered and deferred: require the assigner to
  hold the node in their own derived scope (`lc-admin` exempt), which is one `verifyLevelsUpTo` reuse and
  a follow-up work unit of its own.
- **L9** — evidence, read-only recon before the first write: `ScopeLevel.java` declares
  `COMPANY(required=true)` and `COUNTRY(required=true)`, and `CurrentUserContext.verifyLevel:363-371`
  denies a required level whose id is absent from the claim set — so a region-derived scope that omits
  its country is a guaranteed 403 at the country level, not a partially working state. The derivation
  must therefore emit each row's full ancestor chain.
- **L10** — evidence: the write gate to copy is `EmployeeStoreAssignmentController:65-66`
  (`hasAnyRole('lc-admin','lc-employee')`), the trigger hook to copy is
  `AccessProvisioningProducer.onMembershipChanged` (`:227`), and the projection that already writes the
  five attributes is `AccessProvisioningMembershipService:207`. No Keycloak script, realm mapper, role
  or `keycloak-setup.sh` change is needed by this unit.
- **L11** — ruling, T4, after the backend slice reported that the derivation consulted no node's
  `enabled` flag: **the assigned node's own flag decides and no ancestor's flag may be**. It is
  `StoreScopeDerivation`'s own split (T12: the assigned store's flag is checked, the ancestors' are
  not) applied to the new levels — the assigned node is the deciding input, its ancestors are context,
  and a soft-deleted region or zone must never reach an authorization decision. Verified as a schema
  fact before the code relied on it: `company_countries` has **no** `enabled` column at all (V1, and no
  `ALTER TABLE` for it anywhere in `db/migration`), while `company_regions` and `company_zones` both
  carry one. RED observed: "23 tests completed, 2 failed" — a disabled region row derived its country
  and a disabled zone row derived its region, both `AssertionError: Expecting empty but was:`. The
  third case of that group pins the opposite direction (a zone row whose region is disabled still
  contributes) and passed at birth on purpose, which is what stops a future "helpful" ancestor check.
- **L12** — evidence, backend gates (the slice plus the L11 ruling), from `life-control-api/`:
  `./gradlew spotlessApply spotlessCheck spotbugsMain --no-daemon` → BUILD SUCCESSFUL, 0 BugInstance
  with `ignoreFailures = false`; `./gradlew test --no-daemon` → BUILD SUCCESSFUL, 3799 tests, 0
  failures/errors/skips, with the PostgreSQL-backed suites running against Testcontainers and not H2.
  Differential against the base commit over the shared store-assignment and provisioning suites: 125
  tests on the base vs 131 on the change, so the only delta is added coverage. Six pre-existing schema
  suites pinned the Flyway head literal at `V25` and were moved to `V26` in the same change.
- **L13** — evidence, T8/T9 gates, both by real exit code and not through a pipe: `npm run lint` →
  "All files pass linting", exit 0; `npm test` (Vitest) → 3818 passed, 0 failed, exit 0. The first full run reported **1 failed**: `employee-detail.spec.ts` asserted that no role
  hint reaches the dialog when the caller cannot read the access overview, but its own `setup` hands
  the page a successful overview by default, so the assertion would have passed for the wrong reason
  had the fixture not carried a role. The fix is in the test, not the code: the case now fails the
  access read (`accessError`) as its name already claimed. The behaviour it guards — no overview, no
  hint — was already correct.
- **L14** — independent verification pass (Risk item 2), read-only, over the uncommitted diff:
  **S1–S5 all PASS** with `path:line` evidence and no blocker. It confirmed by reading that no file of
  the five per-level read scopes is in the diff, that the three `EXCLUDE` constraints are per
  `(employee, node)` and together admit several concurrent nodes at different levels, that
  `company_countries` really has no `enabled` column, and that the five `AGENTS.md` citations land on
  the filter branches. Four advisories: the hook's log text still said "store-assignment change"; the
  predecessor finder was only ever Mockito-stubbed; no test pinned that a foreign chain contributes no
  company id (S3's direction); and no HTTP-level test of the new surface. The first three were closed
  in this unit (the hook's prose is source-neutral, and two probes were added); the fourth is recorded
  as a deferral below. The pass found this record's own `:225` citation off by two lines, corrected in
  place — a log that cites a line is worth exactly what its last reader verified.
- **L15** — delivery boundary, recorded because the unit is far past the review slice: nine commits,
  **6890 authored changed lines** measured against `origin/main` plus this record's own amendment. The commits are the review units and
  the measured sizes are `a71e4d2` 460, `c55c1d7` 643 (schema proof only), `e414762` 794, `8998ca5`
  247, `f7b8c35` 1828, `86ce5c0` 684, `334b855` 1485, `735bbc0` 585, `a869080` 164 (ten commits). That is
  seventeen times the ~400-line budget, and saying so is worth more than a header that pretends
  otherwise: **this does not ship as one pull request.** The layer split that keeps each pull request
  reviewable as a behaviour is the schema plus the write surface (`a71e4d2`, `c55c1d7`, `f7b8c35`),
  then the claims (`e414762`, `8998ca5`), then the screens (`86ce5c0`, `334b855`, `735bbc0`), with this
  record's commit riding the last one. The split order matters: the claims pull request must land
  before the screens, because until the projection reads the new table an assignment changes nothing in
  the token, and a reviewer of the screens would be reviewing a feature that does not yet work.
- **L16** — incident, recorded because it outlives the session: `npm ci` in the frontend ran the
  package's `prepare` script, which sets the clone-wide `core.hooksPath` to `life-control-app-angular/.husky/_`
  and displaced the repository's own `.githooks` guard — shared by every worktree, including the
  anchor, not just this one. Restored to `.githooks` before the first commit. The same `npm ci` also
  means `lint-staged` rewrote frontend files during the commits, so the gates were re-run on the
  committed tree rather than trusting the run that preceded it.
- **L17** — two corrections to this record's own plan, made after the code proved the plan wrong, and
  left visible rather than silently rewritten. **(1)** The Tasks table sketched "a level column plus a
  node id"; that shape cannot exist here, because a single `scope_id` column cannot carry a real foreign
  key to four tables, and one `EXCLUDE` cannot span three alternative node columns. What shipped is
  three nullable node columns, each with its own foreign key and its own partial `(employee, node)`
  window exclusion — the same referential integrity the store table has, which a generic id column
  would have given up. **(2)** T8 said the dialog's level would be "fixed by the position's role"; what
  shipped preselects and orders the level from the employee's scoped role and still offers all three.
  Fixing it would have put an authorization rule in the UI that no endpoint enforces, and a node at a
  level the employee's roles do not read is visible in the Access screen anyway, which is where the
  effective scope is read.
- **L18** — evidence, native assessment at close (RDD is on, `review mode status` reports "on (decided
  by default)", both scopes unset). The first `assess` was **wrong and worth recording**: it returned 17
  changed paths and 475 lines naming `store-areas-edit.html`, a file this branch never touches, because
  without an explicit root it read the session's anchor and its registered worktrees instead of this
  one. Re-run with `workspaceRoot` pointing at the worktree it returned 60 changed paths and 7402
  changed lines, `nativeRisk: medium`, `reviewDue: false` with reason `under_budget`, so **no native
  review lineage was started** — the contract starts one only when `reviewDue` is true or the human asks
  for it. The tier the provider returns wins over this record's own reading, which is why the header
  still declares item 1 and item 2 as the risks: the tier decides whether a review runs, not whether the
  change is risky. An independent verifier ran anyway (L14) and is not replaced by any of this.
