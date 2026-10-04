# ODD feature: employee-store-assignments

**Status**: planned, nothing delivered — the remaining work is **W1a and W1b** (W1 split on
2026-10-04, see the evidence log) and **W2–W3** below. **D1–D4 are closed**
(2026-10-01): several stores at once, the profile's store as a **derived default**, **no** row for a
company-wide person, and the **store** as the only granularity. **G2 is closed too** (2026-10-02, by
`company-scope-local-fallback`'s **D4 = no** — one company per person; that is that record's D4, a
different one from this record's own D1–D4 numbering — so the same-company rule is an **invariant**,
not a gap). What that unblocks is the **design**, not the number: `V21` was taken by
`employee-registry` on 2026-10-03, so this record takes the next free number when it lands — the
migration note below already says so, and the 2026-10-04 row of the evidence log records this repair.
This header claims no branch, push or PR state; see the evidence log.
**Created**: 2026-10-01 · **Risk**: **high** — this table becomes the **fact** that feeds the token
claims, so a wrong row is a wrong authorization; and it is the first table in the schema whose invariant
admits **many rows per person** for the same level.
**Repository**: LifeControl — spans `life-control-api/**` (Spring Boot, Java 21, PostgreSQL 18.1 +
Flyway) and `life-control-app-angular/**` (Angular 20.3 + Material/CDK 20). No gateway change.
**Migration**: **the next free number when this lands — not `V21` by right** (corrected 2026-10-03).
`V19` (`hr-org-structure`) and `V20` (`employee-registry` W1a) are merged, and this record depends on
one of `employee-registry`'s side effects (see the schema note). **`V21` is taken**:
`employee-registry` settled its **W2a** on 2026-10-03 as `V21` (`D10` there), because
`spring.flyway.out-of-order` is unset (default `false`) with `validate-on-migrate` defaulting to
`true`, so a gap is not a free slot — a `V21` arriving after an applied `V22` fails validation on
every environment that ran it. This record is **unwritten**, so renaming it costs one line here.
**Base**: `main` @ `274c67f` · **Branch**: `feat/hr-org-structure` · **Worktree**:
`~/workspace/LifeControl-worktrees/feat-hr-org-structure` (herdr `wM`). It shares the branch with the
other two employee records deliberately: the three are one domain, designed together, and this is the
record the other two named.
**Requested by**: the user — the membership flow of 2026-10-01 ("cuando se active [el contrato] se debe
de asignar la tienda y la posición …"), which turned this record from a neighbour into the load-bearing
row of the whole access chain.

## Origin

Three records declare this one, and each for a different reason:

- `hr-org-structure` (**G7**): "No store assignment here — `employee-store-assignments` owns it, and it is
  what gives the store-scoped roles their scope". Its own D3 finding makes the point sharper: the platform
  roles (`lc-sales`, `lc-receiving`, `lc-scheduling*`, the `lc-company-store*` pair) are **store-scoped**,
  so without an assignment the role's broadest granted scope has nothing to verify against.
- `employee-registry` (**G8**): the same gap from the personnel side: an employee's store is not a fact
  about the person, so it never belonged in `employees` or in the contract.
- `company-scope-local-fallback` (**D3 closed**): the store is the fact that a contract activation
  asserts, and its **T9** says what the fact must produce: the **complete ancestor chain** of the
  assigned store, top-level and multivalued, because `company_id` and `company_country_id` are required
  levels and a lone store id would leave the caller denied at the company level.

So this record owns one question and must answer it precisely: **which stores does a person work in, and
since when** — with the ancestors derived, never stored.

## Decisions (user-owned — **all closed**, 2026-10-01)

| # | Decision | Decision taken, and its consequence |
| --- | --- | --- |
| **D1** | Can a person be assigned to **several stores at once**? — **CLOSED: yes** | Three independent reasons: the claim parser accepts a list, so the token can express it; covering two stores is ordinary (a supervisor, a relief cashier); and forbidding it would force an exclusion on `employee_id` alone, which the store tree does not justify. **Consequence**: the invariant is per `(employee, store)`, never per employee (T2), and the derived chain can legitimately hold several stores in several countries at once — but **within one company** (`company_countries` admits several countries per company, `V1:76-86`), so the multi-country chain is **not** the multi-company case; that case is `company-scope-local-fallback`'s **D4, closed on 2026-10-02 as no — one company per person** — and it is **not** answered by this table's cardinality, because `employees.company_id` is singular and the same-company rule in T8/G2 is what keeps the derivation coherent. **This is the decision the migration's constraint depends on** |
| **D2** | What happens to the profile screen's **store selector**? — **CLOSED: it becomes a derived default** | Today `user_preferences.company_store_id` is a free-form choice with no validation and two Angular features read it back as "the active store" (F8). With this table that field is a **second answer** to a question the assignment already answers, and it can point at a store the person is not assigned to. **Consequence**: the field stops being free-form — it narrows to the **assigned stores**, and the backend refuses a store the caller is not assigned to (T11). It still never feeds the claim, so a wrong value cannot grant anything; what the constraint removes is a UI that lies about where you work |
| **D3** | Does a **company-wide** person (no store) need a row here? — **CLOSED: no** | `employees.company_id` already answers "belongs to this company" (it is `NOT NULL`), so the company-level fact stays on the record that owns it and this table stays purely about stores. **Consequence**: a holder of a company-scoped role with no store — the HR clerk, accounting — derives `company_id` and no store/zone/region/country, and that is correct, because only the deeper checks require a country. What must **not** happen is a nullable `company_store_id` meaning "all of them": ambiguity in a column that feeds an authorization input |
| **D4** | Is the granularity the **store**, or does anything need a zone/region row? — **CLOSED: store only** | A zone- or region-wide grant is expressible by assigning the stores inside it, and the ancestors are derived anyway. A coarser row would add a second way to say the same thing, which is the shape that ends in divergence — and it is also why **G1** stays declared rather than worked around |

## Decisions (mine, technical — challenge them if you disagree)

| # | Decision | Why |
| --- | --- | --- |
| T1 | `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `Auditable`, `enabled BOOLEAN NOT NULL DEFAULT true`, and **no `version`** | T1 of `hr-org-structure` and T2 of `employee-registry`: `version` sits on mutable edited aggregates and on no history table. A contract has none because its only mutation is being closed; an assignment is the same shape, so it gets none either — and therefore **no 412 precondition** on this form |
| T2 | The invariant is **"no two *enabled* assignments of the same `(employee, company_store)` may overlap"**, expressed as a **partial exclusion constraint** (`EXCLUDE USING gist (employee_id WITH =, company_store_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&) WHERE (enabled)`) | The same idiom V20 introduces for contracts, reused because it is the right one: the `WHERE (enabled)` predicate makes it *partial*, so a revoked assignment stops blocking its replacement — which a partial index would not do. **Deliberately not an exclusion on `employee_id` alone**: that would forbid the multiple stores D1 recommends. Note the idempotent dependency: it needs the `btree_gist` extension, which **`employee-registry`'s W2a creates in its own migration (`V21` as of 2026-10-03)** — **not** `V20`, which creates neither the extension nor `employee_contracts`; if this migration ever lands without that one, it must create the extension itself |
| T3 | `valid_from`/`valid_to` are **`DATE`**, not timestamps | A store is a day-granular fact, and the schema's only `DATE` precedent is V17's range (F7). Timestamps would invent a time-of-day for a fact that has none, and would make the overlap arithmetic lie about "today" |
| T4 | Opening a new assignment for the **same store** closes the previous one **the day before**, in the same transaction, and the database is the one that proves the overlap is refused | Mirrors `employee_contracts`' T13 exactly. Note what it does **not** do: opening an assignment for a *different* store closes nothing, because that is how a second concurrent store is created (D1) |
| T5 | **No `PUT` and no `DELETE`**: a transfer is a new assignment and a mistake is closed | Consistent with `employee-registry`'s "no contract PUT": the history is the point, and rewriting a row would erase when someone stopped working somewhere. A wrong row is closed with the right date, which is also what removes it from the derivation |
| T6 | The **derivation is a pure function with its own spec**: given the assignments valid today and the store tree, it returns the five id **sets** in the shape the claim parser reads (F4), and it **never emits a level it cannot derive** | This is the risky piece of the whole feature, so it is the one that gets tested hardest: one store; two stores in **different countries** (the list case); a store that was later disabled; a closed assignment excluded; a **future** assignment excluded; and a person with no assignment returning empty sets rather than a partial chain |
| T7 | The table stores **no ancestors**: no `company_id`, no country/region/zone, no `company_id` copy, no `version` | T7 of `hr-org-structure` states the repository's habit — derive the chain rather than denormalize it. Here it also protects the projection: a stored ancestor could disagree with the store tree after a reorganisation, and the disagreement would be invisible |
| T8 | The **same-company** rule (the store must belong to the employee's company) is a **service-level** check, and that check **is** the guarantee | **Decided invariant, not a gap**: it holds because `company-scope-local-fallback`'s D4 closed on 2026-10-02 as **no** — one company per person — so this is not a provisional mitigation a future multi-company answer would retire. Its residual is an **accepted** database limitation, the same limitation `hr-org-structure` gives its G6 — accepted rather than open — because the database cannot express a fact two hops away (`store → zone → region → country → company`). Because the rule is now permanent, the check on write and the test that proves another company's store is refused are **load-bearing** and must not be moved or weakened |
| T9 | This record **produces the fact and never writes the projection**: it does not touch Keycloak, and it does not know the mapper exists | One truth, one materialisation: the assignment is the truth, Keycloak holds a projection, and the diff between them is how divergence is detected (`company-scope-local-fallback`'s T12/`employee-access-provisioning`'s job). A table that also wrote the IdP would be the second source the whole design round refused |
| T10 | **No cache** anywhere on these reads | The read feeds authorization, and `hr-org-structure`'s **G12** is a live example in this repository of what caching an authorization input does when the cache outlives the decision |
| T11 | **The profile's store preference is display-only and constrained to the assigned set.** The field stays — the UI needs a default when several stores are assigned — but it must resolve to a store the caller is **currently assigned to**, and the claim never reads it (T9) | D2. Two unconstrained answers to "which store am I in" is the divergence `company-scope-local-fallback`'s D3 was about; this makes the preference a **pointer into** the assignment set instead of an assertion of its own. Because it is not an authorization input, the harm it prevents is not an escalation but a UI that shows a store the person cannot work in, and an API call that fails confusingly later |

## Verified exploration evidence

Read-only exploration ran on 2026-10-01 against `main @ 274c67f` plus this branch's own two records. Every
F-number below is an anchor; where the fact was inherited from a sibling record rather than read again in
this pass, the record is named.

| # | Fact | Anchor |
| --- | --- | --- |
| F1 | The store chain is `company_stores → company_zones → company_regions → company_countries → companies`, and the repository already walks it: `resolveStore(companyId, companyCountryId, regionId, zoneId, storeId)` verifies access and then resolves each hop before returning the store | `store/service/StoreLocationService.java:107-131`; the same shape in `CompanyRegionService.resolveCompanyCountry` and `CompanyZoneService` |
| F2 | The roles that need this table are the store-scoped ones: `ScopeLevel.STORE.roleNames()` is exactly `lc-company-store`, `lc-company-store-read`, `lc-receiving`, `lc-sales`, `lc-scheduling`, `lc-scheduling-read` | `common/security/ScopeLevel.java:58-68`; pinned by `CurrentUserContextTest.storeScopeListsStoreRoles` |
| F3 | The claim path accepts lists: `extractUuidSetFromClaim` reads the JWT from the security context and accepts a list, an array or a comma-separated string, collapsing malformed/missing/blank to an **empty set** | `common/auth/CurrentUserContext.java:372-374`, `:408-457`; pinned per level by the `missingClaim`/`blankClaim` getters |
| F4 | Level requirements decide what the derivation must emit: `company_id` and `company_country_id` are **required**, `company_region_id`/`company_zone_id`/`company_store_id` are optional (denied only on a non-null mismatch) | `common/security/ScopeLevel.java:26,29,32,35,58`; `CurrentUserContext.java:349-357` |
| F5 | `employees.company_id` is `NOT NULL` and `employees.keycloak_user_id` is a nullable `UNIQUE VARCHAR(36)` — the company-level fact already has a home, and the link to the account already has its natural key | `employee-registry`'s V20 schema block (same branch), F13 there |
| F6 | `employee_contracts` carries **no `version`**, and its overlap invariant is a **partial exclusion constraint** over `daterange(start_date, end_date, '[)')` with `WHERE (enabled)`, which needs the `btree_gist` extension that **`employee-registry`'s W2a creates in its own migration** (corrected 2026-10-03: this row said `V20`, which creates neither the extension nor `employee_contracts`) | `employee-registry`'s T2/T12 and its V20/V21 blocks; F8 there records that the repository had no `CREATE EXTENSION`, no exclusion constraint and no partial index before it |
| F7 | `DATE` columns map to `LocalDate` and the schema's only precedent is V17's `valid_from`/`valid_to`, which that migration documents as the first `DATE` columns | `employee-registry`'s F10; `V17:36-37, 14-17` |
| F8 | **A second source already exists for "which store am I in"**: `user_preferences.company_store_id` is written by the profile screen with no validation, and two Angular services read it back as the active store (`variant-store-context.service.ts`, `scheduling-store-context.service.ts`) | `company-scope-local-fallback`'s E20; `V1__baseline_schema.sql:371`; `features/user/products…`, `features/scheduling/data/scheduling-store-context.service.ts:24-27,46-49` |
| F9 | Employment status is a `statuses` row of the seeded `EMPLOYEE_STATUS` family (`Active`, `Inactive`, `OnLeave`, `Terminated`), and `Terminated` requires a `termination_date` | `employee-registry`'s T3/T5 and its V20 seed block |
| F10 | **Nothing in the backend can yet consume this table's output**: `IdentityProvider` has `createGroup` and **no** group-membership operation, and no protocol mapper exists for any `company_*` claim | `company-scope-local-fallback`'s E13/E14; `usersadmin/identity/IdentityProvider.java:104` |

## Schema — `V21`

> The `V21` in this heading is **the number the design was written against, not a reservation**: see
the migration note in the header. `employee-registry` settled its **W2a** as `V21` on 2026-10-03, so
this record takes the next free number when it lands. The SQL below is left **byte-identical** as the
design it was written against.

```sql
-- ============================================
-- V21 — Employee store assignments
-- ============================================
-- The fact this table records: since when and until when a person works in a store. The store tree
-- answers everything else (company, country, region, zone) and is therefore NEVER duplicated here:
-- a stored ancestor could disagree with the tree after a reorganisation, and the disagreement would
-- be invisible in a column that feeds an authorization decision.
-- Several stores at once are legal (a supervisor, a relief cashier), so the invariant is per
-- (employee, store) and not per employee — see T2. It reuses V20's partial-exclusion idiom, and
-- therefore depends on the `btree_gist` extension employee-registry's W2a creates (not `V20`).
-- `employee_contracts`' rules apply unchanged: no `version` (the only mutation is closing), and
-- opening a new row for the same store closes the previous one the day before, in the same
-- transaction. Foreign keys are unnamed, matching the baseline and the V9..V20 style; the CHECK and
-- the exclusion constraint are named so a violation identifies the rule it broke.
-- ============================================

CREATE TABLE employee_store_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    valid_from DATE NOT NULL,
    valid_to DATE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_employee_store_assignments_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ex_employee_store_assignments_no_overlap
        EXCLUDE USING gist (employee_id WITH =, company_store_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
        WHERE (enabled)
);
CREATE INDEX idx_employee_store_assignments_employee_id ON employee_store_assignments(employee_id);
CREATE INDEX idx_employee_store_assignments_company_store_id ON employee_store_assignments(company_store_id);
```

No seed data and no `CREATE EXTENSION` of its own (`employee-registry`'s W2a creates `btree_gist` in its
own migration; see T2 for the case where this migration could land without it).

## API surface

| Method | Path | Roles |
| --- | --- | --- |
| `GET` | `/api/companies/{companyId}/employees/{employeeId}/store-assignments?includeDisabled=` | `isAuthenticated()` |
| `POST` | `/api/companies/{companyId}/employees/{employeeId}/store-assignments` | `lc-admin`, `lc-employee` |
| `PATCH` | `/api/companies/{companyId}/employees/{employeeId}/store-assignments/{id}/close` | `lc-admin`, `lc-employee` |

`PATCH …/close` takes an optional end date and defaults to today, exactly like the contract's close. There is
**no `PUT` and no `DELETE`** (T5), and every method resolves the company first — `verifyCompanyAccess` before
the load, the pattern E12 of `hr-org-structure` records — then the employee, then the store, each scoped to
that company so another company's row is a **404**, never a 200.

## The derivation (this record's real output)

The table is inert without it, and it is the bridge to `company-scope-local-fallback`'s T9. Given the
assignments **valid today** (`valid_from <= today AND (valid_to IS NULL OR valid_to > today)`, `enabled`)
for an employee, a **pure function** produces:

| Claim | Where it comes from | When it is empty |
| --- | --- | --- |
| `company_id` | the employee's own `company_id` (D3) | never, while the employment is current |
| `company_country_id` | the country of each assigned store, walking the chain (F1) | when the person has no assignment |
| `company_region_id` | the region of each assigned store | same |
| `company_zone_id` | the zone of each assigned store | same |
| `company_store_id` | the assigned stores | same |

Three rules ride with it, and each one has a failure mode it prevents: **lists, not scalars** (a person may
work in two stores in two countries, and the parser reads a list — F3); **never a partial chain** (emitting a
store without its country leaves the caller denied at a required level, which is the bug T9 of the other
record names); and **nothing derived from a disabled or closed row** (a revoked assignment must disappear from
the token at the next issuance, which is the revocation path the other record's worker owns).

## Screens

| Route | Content |
| --- | --- |
| `/hr/employees/:id` (the `EmployeeDetail` of `employee-registry`'s W4) | A new **Tiendas asignadas** section: store, validity, status, with "Asignar tienda" and "Cerrar" (confirm). The list shows the **derived** company/country/region/zone per row, because that is what the token will carry and the operator should see what they are granting |
| the contract activation flow of `employee-registry`'s W3/W4 | The store picker, because the maintainer's flow puts the assignment in that act. **Same act, two rows**: the activation creates the contract and the assignment |

The profile screen's store selector is **D2, closed**: it narrows to the assigned stores (T11). That is the one existing surface this record changes, and it is why the change is not backend-only.

## Work units

| | Content | Sizing note |
| --- | --- | --- |
| **W1a** | The migration (**`V23`**, the number measured free on 2026-10-04) + the entity and the repository, plus the **four Flyway-head pins** it moves from `"22"` to `"23"` and the schema-migration test that proves the table, both indexes, the CHECK and the partial exclusion constraint against real PostgreSQL | **Under the 400-line budget** (the split's first half, decided 2026-10-04) |
| **W1b** | The DTOs, the service (the same-company refusal of T8, closing the predecessor the day before per T4, the close path per T5) and the **derivation as a pure helper with its own spec** (T6) | **The split's second half.** The derivation is the part that must not be rushed, because everything downstream trusts it |
| **W2** | The three endpoints and their tests: the same-company refusal (T8), the overlap refusal **proven against real PostgreSQL** (T2/T4, using the `ddl-auto=validate` + Flyway integration stack), the close path, and the 404 for another company's row | Under budget if the tests reuse the existing integration base class |
| **W3** | The frontend: the employee detail's section and the store picker in the activation flow | **Over budget**, and separable into the two halves above |

Dependency order: **W1a → W1b → W2 → W3** (W1 was split into W1a/W1b on 2026-10-04; see the evidence log). D1–D4 are closed, so the migration's shape is settled and W1a can start. Nothing here depends on the projection: this record can be delivered
and tested with no Keycloak involvement at all (T9), which is what makes it safe to land before
`employee-access-provisioning` exists.

## Gaps

| # | Gap | Note |
| --- | --- | --- |
| G1 | **Per-store role scoping is not expressible.** The roles are person-global client roles and the claim is a *set* of ids, so the guard asks "is this store in the set?" — it cannot ask "with which role". A person holding `lc-sales` **and** `lc-receiving` with assignments in two stores has both capabilities in both | A real ceiling of the existing model, not of this table: expressing it would need a per-store role mapping **and** a guard that reads claims per role, which is a redesign of `ScopeLevel`'s assumptions. Declared, not attempted |
| G2 | The same-company rule is service-level (T8) | It stopped being an open gap when `company-scope-local-fallback`'s **D4** answered **no** — one company per person — so the rule became a **decided invariant**. **Closed by D4 on 2026-10-02**: the enforcing check lives at this table's single write path (T8), and the residual is an **accepted** database limitation — the database cannot express a fact two hops away (`store → zone → region → country → company`) — the same disposition `hr-org-structure` gives its G6. Accepted, not fixed: that is the shape of the model, not a hole in it |
| G3 | **Disabling a company store does not close its assignments** | Nothing listens to the store tree. A disabled store with a live assignment would keep feeding the derivation, and the fix is either a listener (with the durability problem `company-scope-local-fallback`'s G9 measures) or a rule in the derivation that filters disabled stores |
| G4 | `user_preferences.company_store_id` duplicates the concept | **D2**. Until it is implemented, the repository has two answers to "which store am I in", and the free-form one has no validation (F8) |
| G5 | No hours and no workload | `employee-registry`'s G9. A half-day assignment and a full-time one look the same here, which is fine until payroll or scheduling needs the difference |
| G6 | A company-wide employee derives `company_id` and nothing deeper | Correct for the company-scoped endpoints they use (D3), and declared because a future endpoint that requires a country would fail closed on them. The alternative — inventing a country for someone who has no store — would be a lie |
| G7 | No **primary** or responsible store | With several assignments there is no way to say which one the UI should default to, and no notion of who is accountable for a store |

## Cross-record dependencies

| Record | Relation |
| --- | --- |
| `employee-registry` | **Prerequisite**: `employees` must exist (its `V20`), and this migration depends on the `btree_gist` extension that same migration creates (T2/F6). Its **G8** is the gap this record closes, and its **T17** (`keycloak_user_id` is never operator-typable) is a precondition of the auto-apply policy the projection implements |
| `hr-org-structure` | **Prerequisite** for the roles that will use the derived scope (its `V19`), and the source of **G7** naming this record. Its **G12** is the reason T10 forbids caching here |
| `company-scope-local-fallback` | The consumer of the derivation: its **D3** makes the store the asserted fact, its **T9** specifies that the claim carries the complete chain top-level and multivalued, and its **D6** decides who triggers and who approves. It declares, in turn, that under **D1 = A** this record is the fact its projection materialises |
| `employee-access-provisioning` | **Consumer** (written later the same day, not implemented): it owns the outbox worker that reconciles Keycloak to this table's derivation, the diff, and the revocation path. This record must not write the projection (T9) |
| Migration numbering | `V19` (`hr-org-structure`) and `V20` (`employee-registry`) are on this same branch, so `V21` is safe **on this branch only**. If either record is split into its own branch, the numbering must be re-coordinated before merge — `life-control-api/AGENTS.md:874` forbids editing an applied migration |
| Store tree records (`store-locations-backend`, `store-areas-backend`, `store-zones-backend`, `store-locations-frontend`) | Merged, and the source of the chain this record walks. Nothing in them changes |

## Non-goals

- **Not** writing the projection: no Keycloak call, no group membership, no mapper knowledge (T9).
- **Not** expressing roles per store (**G1**), workload (**G5**), or a primary store (**G7**).
- **Not** moving the store into the contract: a transfer must not close a legal contract
  (`employee-registry` T13), so the assignment keeps its own row and its own dates.
- **Not** turning the profile's store field into an authorization input: it becomes a display default
  constrained to the assigned stores (**D2**, T11).

## Task log

- [x] D1–D4 decided by the user (2026-10-01): several stores at once, the store profile field as a derived
  default, no row for company-wide people, store-only granularity
- [ ] W1a — the migration (**`V23`**), the entity, the repository, the **five** Flyway-head pins moved to
  `"23"`, and the schema-migration test. *Implemented on `feat/employee-store-assignments-w1a` and
  independently verified twice on 2026-10-04; not delivered yet — see the evidence log.*
- [ ] W1b — the DTOs, the service (T4/T5/T8) and the derivation with its spec (T6)
- [ ] W2 — the three endpoints and their tests
- [ ] W3 — the employee-detail section and the store picker in the activation flow

## Deferred and blocking

- **Nothing blocks the work** (repaired 2026-10-04 — the bullet read "Nothing blocks `V21`"): D1–D4 are
  closed and D1's answer settles the exclusion constraint's shape (per `(employee, store)`, T2). The
  **number** is open by design: the next free one when it lands.
- **Blocked for its purpose, not for its delivery**: this record can be built and tested today (T9), but
  it changes nothing observable until `employee-access-provisioning` projects the derivation into Keycloak
  and the mapper (`company-scope-local-fallback`'s W0) makes it reachable in a token.
- **Deferred**: per-store roles (G1), workload hours (G5), the primary store (G7), and the store-tree
  listener that would close assignments when a store is disabled (G3).

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-10-01 | Record written on `feat/hr-org-structure`, after the membership flow was decided in `company-scope-local-fallback` (its D3) and the mechanism was closed as **A** (its D1). F1–F10 are anchors: F1–F4 were read again in this worktree for this record; F5–F10 are inherited from the sibling records named in each row, and they are the ones this design leans on. **Nothing is implemented.** |
| 2026-10-01 | **D1–D4 decided by the user in one pass**: **D1 yes** (several stores at once, which settles T2's per-`(employee, store)` constraint and closes `V21`'s only blocker), **D2** the profile's store becomes a **derived default constrained to the assigned stores** (T11, and it closes the store half of `company-scope-local-fallback`'s D3, which had left it open), **D3 no** (a company-wide person has no row; the company-level fact stays on `employees.company_id`), **D4 store-only**. **No source line was written**, and the record now has no open decision: `V21` can be written. |
| 2026-10-02 | **D1's clause corrected, T8 reframed from gap to decided invariant, and G2 closed — done while closing `company-scope-local-fallback`'s D4 = no.** D1's rationale claimed the multi-country chain was "the multi-company case `company-scope-local-fallback`'s D4 left open, answered here by the data rather than by policy", which conflates several **countries** with several **companies**: `company_countries` carries `UNIQUE (company_id, country_id)` (`V1__baseline_schema.sql:76-86`), so several stores in several countries of **one** company are ordinary, and the multi-company case is that record's **D4, closed on 2026-10-02 as no — one company per person** — not something this table's cardinality answers, because `employees.company_id` is singular. T8 no longer reads as "a gap, not a guarantee": the same-company rule is a **decided invariant** whose residual is an **accepted** database limitation, and its write-path check plus its refusal test are load-bearing. **G2 is closed** with it, as that accepted limitation. **No source line was written** |
| 2026-10-03 | **The migration number stopped being available and one of this record's premises was already false; both corrected, no source written.** Taken while `employee-registry` settled its **W2** against `main @ a7ac439`: that record closed **`D10`** as **`V21`** for its W2a, because `spring.flyway.out-of-order` is unset in `life-control-api/src/main/resources/application.properties` (default `false`) and `validate-on-migrate` is unset (default `true`), which makes a numbered hole a **failing validation** on any environment that applied the later migration. This record's header therefore moves from "**`V21`**" to **the next free number when it lands**, and the `## Schema — `V21`` block keeps its SQL byte-identical with a note at its head saying the number was the design's and not a reservation — the same treatment `employee-access-provisioning` gave its `V22` on 2026-10-02, for the same reason. **Second, and older**: this record's **F6**, its **T2** and its schema note all said the `btree_gist` extension was created by **`V20`**, which was true when the design was written on 2026-10-01 but **stopped being true on 2026-10-02**, when the extension and `employee_contracts` moved out of `V20` into `employee-registry`'s own W2 migration; `V20` creates neither. The three mentions now point at that migration, and this is the second premise in this chain that was asserted rather than measured — the other being the projection record's claim that `V19`–`V21` preceded it. **No source line was written, no decision was reversed, and this record's cardinality rules, its T2 constraint shape and its derivation are untouched** |
| 2026-10-04 | **Every remaining claim that this record's migration is `V21`, repaired; no source line was written and no decision was reversed.** The 2026-10-03 row above moved the header's migration note to "the next free number when it lands" and annotated the schema section's heading, but five other places in this same file still sent the reader to `V21`: the header's D1–D4 sentence ("`V21` is therefore unblocked"), the **D1** row's closing sentence, the task log's **W1**, the first bullet of "Deferred and blocking" ("Nothing blocks `V21`"), the **W1** row of the work-units table, and the dependency-order line. All six were falsified on 2026-10-03, when `employee-registry` settled its W2a as `V21` (`D10` there), and they sat contradicting the corrected note in the same file. Each now states what is actually unblocked — the **design** — and the number as the next free one when it lands. **Left as written and named here rather than erased**: the cross-record row that reasons "`V21` is safe **on this branch only**", the dated 2026-10-01 and 2026-10-03 rows, and the schema block's SQL and its comment header, which the 2026-10-03 note already declares byte-identical on purpose. Those are dated rationale and design provenance — the branch this design was written on, now merged — not claims about current work; rewriting them would destroy the evidence that the drift existed. |
| 2026-10-04 | **W1 split into W1a/W1b, and the migration number measured rather than assumed.** The user was shown that W1 is declared over the 400-line budget and chose the two-unit cut: **W1a** = migration + entity + repository + the four Flyway-head pins + the schema-migration test; **W1b** = DTOs + service + the derivation with its own spec. The number was measured in the worktree, not inferred: `V22__employee_access_provisioning.sql` is on `main` (`62706b0`), so the next free number is **`V23`**, and four suites pin the head at `"22"` (`hr/HrSchemaMigrationIntegrationTest:66`, `hr/EmployeeSchemaMigrationIntegrationTest:89`, `hr/EmployeeContractSchemaMigrationIntegrationTest:83`, `goodsreceipt/GoodsReceiptIntegrationTest:578`) so all four move to `"23"` in W1a. The record's `V21` mentions in the dated 2026-10-01/2026-10-03 rows and inside the schema block stay as written provenance, per the row below. **No source line is written by this row** |
| 2026-10-04 | **W1a implemented and independently verified — and the "four pins" this record's own split row declared were five.** On `feat/employee-store-assignments-w1a` (worktree `w1D`, base `main @ 0e8533e`, tracking commit `f71d7ef`): `V23__employee_store_assignments.sql` (SQL structurally identical to the frozen block above — the verifier's mechanical diff over the statements reported no divergence; only the comment header was renumbered to `V23` and its stale `btree_gist`/`V20` claims corrected), `EmployeeStoreAssignment` (no `version`, no stored ancestor, `LocalDate` range, lazy `@ManyToOne` to `Employee` and `CompanyStore`), `EmployeeStoreAssignmentRepository` with two finders (`valid_to > :date`, the half-open reading of T2's own range), and `EmployeeStoreAssignmentSchemaMigrationIntegrationTest` (10 tests: columns/types/nullability by `containsExactlyInAnyOrder`, both indexes by name, the named CHECK, and the exclusion constraint proven functionally — overlap refused naming `ex_employee_store_assignments_no_overlap`, soft-deleted overlap allowed, adjacent half-open ranges allowed, same employee/different store allowed, **two employees/same store allowed** so the designed key is pinned against a store-only key). **The pin count was wrong in the split row above: there are five, not four** — the fifth is `provisioning/AccessProvisioningSchemaMigrationIntegrationTest` (the V22 suite), caught by the independent verifier as the only failure of a 2972-test run (`expected: "22" but was: "23"`) and fixed with one bounded round, after which the full suite reported **2973 tests / 0 failures / 0 errors / 0 skipped** and `spotlessCheck spotbugsMain --rerun-tasks` ran fresh green. This is the third premise in this record's chain that was asserted rather than measured, and the first that a *verification* caught instead of the author. Two repairs and three declarations ride with it: the RED the writer first reported as "9 of 9 failed" was **wrong and is recorded as 8 of 9** (`btreeGistExtensionExists` queries only `pg_extension`, which `V21` populates, so it passes without `V23`); the derivation's predicate above was repaired from `valid_to >= today` to `valid_to > today`, because `valid_to` is the first day **not** covered by T2's `'[)'` range — and the **operator-facing** close semantics stay a W1b decision, since `employee-registry`'s D14 closed "API inclusiva, columna exclusiva" for contracts and consistency would extend it here; declared follow-ups: the schema test does not pin the column defaults, the FK targets, the CHECK's definition text, the exclusion key's expression text, or the absence of seed rows (redundant with the mechanical SQL diff the verifier ran), and the order-dependent FK collision between the four pre-existing unconditional `DELETE FROM employees` suites and the provisioning suite's `@BeforeEach`-only cleanup is **pre-existing on `main`**, declared rather than fixed here. **No source line was written by this row** |
| 2026-10-04 | **The native review of the W1a candidate ran and closed `approved` — the first approval in this chain, after the two declines W1a's own sibling records had to live with.** Candidate: the committed range `main @ 0e8533e` → `826b4dc` (10 files, **794 changed lines**), lineage `review-c788395e04866a28`, tier **medium**, one lens (`review-reliability`), correction budget 200, and **zero corrections opened**. The materialize forecast was relayed before anything ran (one model run, `pi_host_relay`), the reviewer ran once, and the exact acknowledgement burned the approval authority (`gentle-ai.review-acknowledged/v1`). Two **informational** findings rode with the approval and neither reopens the review: `R3-001` (`hr/repository/EmployeeStoreAssignmentRepository.java:45-52`, WARNING/informational) and `R3-002` (`hr/EmployeeStoreAssignmentSchemaMigrationIntegrationTest.java:100-101`, WARNING/informational) — declared later work, and they land on the same two behaviours this record's W1a row already declared (the finders are unused until W1b; the unconditional `DELETE FROM employees`), which is an independent corroboration of that row's follow-up list rather than a new defect. **Delivery stays ordinary repository policy**: nothing is pushed, no PR exists, and the evidence row that carries this text is documentation-only, so the reviewed code bytes are unchanged (`git diff 826b4dc..HEAD -- life-control-api/` is empty). **No source line was written by this row** |
