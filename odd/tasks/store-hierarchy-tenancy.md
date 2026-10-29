# ODD feature: store-hierarchy-tenancy

**Repository**: LifeControl — `life-control-api/` only: the migrations of the store hierarchy's three
levels plus the purchase-order key they make expressible, the three entities that own the derivation, and
the schema suite that pins them. No `life-control-app-angular` change (the API already answers the
`companyId` each screen reads, and no URL shape moves) and no `api-gateway` change (it declares no route
for these tables).
**Status**: implemented — every level of the store hierarchy stores its owner, the database derives that owner
and refuses one that disagrees with the parent's, and `purchase_orders` is now tied to its store by the
schema and not only by `PurchaseOrderService`. Nothing is deferred. What this unit did **not** do: discard a
row, and that is the difference between it and `V40`/`V42` — every row here already carried an owner through
a `NOT NULL` parent key.
**Created**: 2026-10-29 · **Issue**: **#673** (`feat: give the store hierarchy an owner so a store's
company is expressible`, `enhancement` + `status:approved`) · **Branch**: `feat/store-hierarchy-tenancy`

## Specs

**S1** — *the request, verbatim*: «aprueba el issue y arranca con la unidad» (2026-10-29). Read together
with «qué está pendiente?» of the same session, the unit is issue **#673** and nothing else.

**S2** — *the requirement set is #673's, not this file's*: the acceptance criteria live in the issue and
are not restated here. Where this record and #673 disagree, **#673 wins and the record is corrected** —
the same rule `product-catalog-tenancy` used for #660 and `supplier-catalog-tenancy` for #672.

**S3** — *the deferral this unit discharges, verbatim from the two records that named it*: «No, unidad
aparte» (`odd/tasks/product-catalog-tenancy.md`, S4 — the maintainer's answer of 2026-10-27) and, from
`odd/tasks/supplier-catalog-tenancy.md`'s header, «One piece is **deferred, with its reason**». Nothing new
is decided here: this is that unit, opened as #673 from that record's L12.

**S4** — *the shape is the precedent's, not invented here* — #673's own acceptance criterion, verbatim:
«The migration is two steps per level and the suite is green after each: an EXPAND that adds the nullable
column and the unique target, then a CONTRACT that derives the owner from the parent, sets `NOT NULL` and
adds the composite key — the `V39`/`V40` and `V41`/`V42` shape, never one migration, because a `NOT NULL`
column cannot arrive before the write surface carries it.» Three levels (region, zone, store) × two steps,
plus the purchase-order key the store level makes expressible.

**S5** — *the data consequence, in #673's own words*: «The data consequence is stated in the migration
header the way `V40` and `V42` state theirs, with no backfill inventing an owner: a row that cannot be
attributed to a company through its parent is discarded, and the header says so in its first lines.» This
unit is the honest middle case those two headers do not have: every row **can** be attributed, because
`company_countries.company_id` is `NOT NULL` since V1 and every level reaches it through a `NOT NULL`
parent key — so the `UPDATE`s are derivations of a fact the row already carries, the shape `V43`'s header
argues for, and no row is discarded because an unattributable row is unrepresentable.

**S6** — *non-goals, from #673*: no URL shape and no guard changes for the store/region/zone endpoints;
`CompanyStoreOwnerResolver` stays, because its walk is still the readable statement of the rule; and
`companies.rfc` stays globally unique (a company has no owner company).

**S7** — *what must keep working, from #673*, each pinned by its existing suite: the `company_*` claims
guard (`ScopeLevel`), the employee store assignments (`V32`), and the store-scoped reads that hang off
`company_stores` — inventory (`V10`), store inventory settings (`V11`), goods receipts (`V12`), product
variant store stock (`V13`) and the scheduling tables (`V16`, `V18`).

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | The region level: `V44` adds `uq_company_countries_company_id` plus the nullable `company_regions.company_id` and its `UNIQUE (company_id, id)`; `V45` derives it from the country, sets `NOT NULL` and adds `fk_company_regions_country_same_company`. The one head-level raw-`INSERT` fixture that writes a region gains the column. | S1, S2, S4, S5 | inline | `228943a0` |
| T2 | The zone level: `V46` (nullable column + `uq_company_zones_company_id`) and `V47` (derive from the region, `NOT NULL`, `fk_company_zones_region_same_company`), with its fixtures and its half of the schema suite. | S2, S4, S5 | delegated (`gentle-ai-worker`) | `2ccf4d27` |
| T3 | The store level: `V48` (nullable column + `uq_company_stores_company_id`) and `V49` (derive from the zone, `NOT NULL`, `fk_company_stores_zone_same_company`), with its fixtures and its half of the schema suite. | S2, S4, S5 | delegated (`gentle-ai-worker`) | `1fb045f8` |
| T4 | The derivation moves into the database (`V50`: three `BEFORE INSERT` triggers, the three mappings become read-only) and the purchase-order key lands (`V51`: the pre-check guard plus `fk_purchase_orders_store_same_company`, no `UPDATE`) — the row `V43`'s header records as the one it could not write. | S2, S4, S5 | delegated (`gentle-ai-worker`) | pending |
| T5 | The final gates, run in this worktree: the whole `life-control-api` suite plus `spotlessCheck` and `spotbugsMain`, and this record closed with the observed numbers. | S7 | inline | pending |

## Delivery

Three stacked pull requests, because each slice is a work unit a reviewer can hold at once and T1 already
measured 703 authored lines on its own: **PR 1** = T1 (the region level), **PR 2** = T2 (the zone level),
**PR 3** = T3 with T4 and T5 (the store level, the derivation moving into the database, the purchase-order
key and the closing gates). Bases are retargeted on merge, never deleted while a dependent is open
(`references/pr-chains.md`). The unit's own numbers are in the Log: two measured corrections (L9, L15) are
why the last slice carries more than the level it names.

## Log

**L1** — the request, verbatim: «aprueba el issue y arranca con la unidad» (2026-10-29), after «qué está
pendiente?» in the same session and this record's answer that the only open issue was #673.

**L2** — the approval is an action, not an intention: `gh issue edit 673 --add-label status:approved`
observed back as `#673 OPEN labels: enhancement,status:approved`. Without that label the repository's
issue-first rule forbids the branch, so the label is the first piece of evidence of this unit.

**L3** — the worktree was created with the convention's own script,
`./.github/scripts/new-unit.sh feat/store-hierarchy-tenancy` →
`/home/leo3nava/workspace/LifeControl-worktrees/feat-store-hierarchy-tenancy` at `origin/main` =
`185e5ce9`. The anchor stays what it must be: `main`, `git status --short` empty, no edit.

**L4** — where the owner is written, and why not in the three services. The obvious place for the
derivation is `CompanyRegionService#createRegion` and its two siblings — each already resolves the parent
inside the company — but that leaves every other writer (the raw-`INSERT` fixtures, any future repository
path, a test builder) one forgotten line away from a `NOT NULL` violation, which is the failure V41's
header describes one level up. The derivation lives in the mapping instead: a `@PrePersist` callback on
each entity reads the company from the parent association the caller already set —
`companyCountry.getCompany()`, `companyRegion.getCompany()`, `companyZone.getCompany()`. That callback is
the established idiom here (`GoodsReceipt`, `InventoryMovement`, `ActivityLog`, `ActivityProcess`,
`AccessProvisioningTask` all own a rule that way). One hop per level, which composes: the store's answer
is the zone's stored answer, which was the region's.

**L5** — the read path is deliberately untouched (S6). Every response still derives `companyId` by
walking store → zone → region → country → company, and `CompanyStoreOwnerResolver` keeps its consumers.
Whether those reads should now trust the stored column is a follow-up decision the issue puts out of
scope; switching them on a guess is the change that would let the guard and the row's predicate disagree.

**L6** — the composite keys are what make the stored owner unable to go stale, which is why a
`@PrePersist`-only derivation is sound rather than a cached guess. A store cannot be moved to another
company's zone, a zone cannot be moved to another company's region, a region cannot be moved to another
company's country, and a purchase order cannot name another company's store — so the only way to change a
row's owner is to change its parent, and the database refuses that. Nothing re-derives on update because
nothing can move.

**L7** — the plain parent foreign key is dropped at each level, in the shape `V43` argues for: each
constraint is checked on its own, so a plain `FOREIGN KEY (company_zone_id)` beside
`FOREIGN KEY (company_id, company_zone_id)` would still admit the pair the composite one refuses. V1's
`idx_*` indexes on those columns stay: the composite key leads with `company_id`, so a lookup by parent
still needs its own index — the argument V37 and V40 make about second structures is about answering the
same read twice, which this is not.

**L8** — the migrations derive instead of discarding, and their headers say so in their first lines (S5).
`V40` and `V42` truncated because no existing row carried an owner at all and none could be invented. Here
every row carries one through a `NOT NULL` parent key, so the `UPDATE … FROM` statements are derivations —
the same fact `CompanyStoreOwnerResolver` reads at runtime — and the `NOT NULL` that follows cannot find a
row it must reject. `V43`'s header makes the same argument for the same reason, and this unit reuses that
passage instead of writing a third variant of it.

**L9** — the mapping of each level lands with its **own** migrations, and that is a mechanical constraint
rather than a tidier split: `AbstractPostgresIntegrationTest` runs `ddl-auto=validate`, so an entity that
maps a column the migrated schema does not have yet kills the Spring context for the whole JVM. Mapping
all three levels up front was tried and measured — `Schema validation: missing column [company_id] in
table [company_stores]`, and not one test method ran. So T1 carries `CompanyRegion`'s mapping with
`V44`/`V45`, T2 carries `CompanyZone`'s with `V46`/`V47`, and T3 carries `CompanyStore`'s with
`V48`/`V49`. The derivation is unchanged; only the moment it appears moved.

**L10** — which fixtures had to change, and which must not. A raw `INSERT` into `company_regions` that runs
at head gains `company_id`: exactly one place, `SupplierSchemaMigrationIntegrationTest#insertStore`. The
`V32` backfill suite's fixture does **not**: it migrates to `V31` and seeds the pre-migration shape on
purpose, so the column would make the fixture illegal at the version it runs at — measured, `column
"company_id" does not exist`, and that edit was reverted. That is the difference between a fixture about
head and a fixture about a version, and it is the trap this unit's scratch-database suite avoids by
seeding only what the target version could hold.

**L11** — T1's observation, in both directions. RED: with `V44`/`V45` removed from the migration classpath,
the four suites fail **24 of 24** and no test method runs — the context cannot build the SessionFactory
without the mapped column. GREEN with them: **24 tests, 0 failed, 0 skipped** —
`SupplierSchemaMigrationIntegrationTest` 15, `StoreHierarchyCompanyScopeSchemaMigrationIntegrationTest` 5,
`EmployeeStoreScopeBackfillMigrationIntegrationTest` 3,
`StoreHierarchyCompanyBackfillMigrationIntegrationTest` 1 — and `spotlessCheck` plus `spotbugsMain` clean.

**L12** — the first run of these gates reported a green that was false, and the record keeps it rather than
tidying it away: the runner was `./gradlew test … 2>&1 | tail -40`, so the observed exit code was `tail`'s
and every failure still printed `exit=0`. The second runner captures Gradle's own status and prints it —
`set -uo pipefail`, redirect to a file, then read the file. A pipeline that reports on a build is not
evidence about the build.

**L13** — T2 observed, delegated to a writer with the six surfaces named and the drafts as its input, and
the two hazards L9 and L12 restated in the brief because both had already bitten this unit. RED: with the
mapping, both suites and the supplier fixture in place and `V46`/`V47` absent, the command exits **1** with
**26 of 30 failed** — the context dies on `SchemaManagementException` for the mapped
`company_zones.company_id`, and the backfill suite's zone seed fails at its own `INSERT`. GREEN with the
migrations: **30 tests, 0 failed, 0 skipped** — scope 10, backfill 2, supplier 15, V32 backfill 3 —
`spotlessCheck` and `spotbugsMain` clean. The supplier suite's 15 and the region level's 5 pass unchanged
in the same run, which is the preserve check for the one shared fixture the slice touched.

**L14** — T3 observed, same delegation shape and the same two hazards restated in the brief. RED: with the
mapping and every test edit in place and `V48`/`V49` absent, the command exits **1** with **31 of 36
failed**, the report naming `missing column [company_id] in table [company_stores]`. GREEN with them:
**36 tests, 0 failed, 0 skipped** — scope 15, backfill 3, supplier 15, V32 backfill 3 — `spotlessCheck` and
`spotbugsMain` clean. This slice is the first one whose callback sits on a heavily written insert path, so
it was also run against every suite that persists a store: `com.lifecontrol.api.store.*` plus the
inventory, store-inventory-settings and goods-receipt integration suites, **131 suites, 605 tests, 0
failed** — which is the evidence that `Auditable`'s timestamps and the `@Version` handling in
`CompanyStoreService` still work with a second `@PrePersist` callback in the class.

**L15** — a regression this unit introduced, found by measurement rather than by reasoning, and the reason
the derivation moved. The `@PrePersist` callback design (L4) reads the parent association, so
`PurchaseOrderDetailVariantIntegrationTest` — which saves a store while holding a *detached* `CompanyZone`
reference, `findById(...).getCompanyZone()` outside a transaction — threw `LazyInitializationException`. The
test was run in a copy of `origin/main` under `mktemp -d` (`git archive origin/main | tar -x`): **BUILD
SUCCESSFUL**, so the failure was ours and not pre-existing. Saving a child that names a detached parent is
legitimate JPA — the child needs the parent's identity, which the reference carries — so the fix was not to
make the caller load the parent's associations; it was to stop reading them at all.

**L16** — the decision that follows: the derivation moves into the database, in `V50`, as one `BEFORE
INSERT` trigger per level, and the three entities map `company_id` as `insertable = false, updatable =
false`. The alternatives were each worse, and the header of `V50` records them: a callback reads
associations on paths that must not read them (measured in L15), a `DEFAULT` cannot reference another row,
and a generated column cannot reference another table. The trigger runs before the `NOT NULL` it must
satisfy, needs no session, and covers every writer the same way — the service, the repository, a fixture, an
operator's script — which is the property `V40`'s header asks for when it says a later code change must not
be able to reopen the gap by forgetting a line. It fills an absence and never overrules a disagreement: an
explicit `company_id` is left alone and a disagreeing one is still refused by the composite keys, which is
what keeps the raw-`INSERT` fixtures valid.

**L17** — the purchase-order key lost its `UPDATE`, and the reason is a contradiction the worker measured
while trying to prove the update load-bearing: `V43` pins `purchase_orders.company_id` to the SUPPLIER's
company as well, and a supplier belongs to exactly one company, so for a row whose store and supplier belong
to different companies there is **no** value of `company_id` that satisfies both keys. Setting it from the
store produces `violates foreign key constraint "fk_purchase_orders_supplier_same_company"` — the
re-derivation aborts the migration on exactly the row it claims to repair, and a database this codebase
wrote needs no repair at all because `V43` already derived the column from the same walk. So `V51` adds a
key and a guard: it counts the rows that name two tenants and stops with a message naming the count, instead
of guessing which side is wrong. Discarding them is not available either — `V40` and `V42` could discard
because their rows carried no owner and #672 authorized it; #673 authorizes nothing of the kind, and a
purchase order that disagrees with itself is contradictory, not unattributable.

**L18** — the design's one platform cost, also measured: two suites that persist the hierarchy through an
`EntityManager` ran `@SpringBootTest` on H2 (`ddl-auto=create-drop`, Flyway off), where a database-owned
column has no trigger and `NOT NULL` rejects the insert — `NULL not allowed for column "COMPANY_ID"`. They
are not pure slices, so the fix follows the base class's own rule rather than working around it:
`CompanyStoreScopeProjectionTest` and `CompanyStoreScopeAgreementTest` now extend
`AbstractPostgresIntegrationTest`, with their `@SpringBootTest`, `@Transactional`, seeds and assertions
untouched (the diff is two lines each). The unit's own scope suite also had to change with the design: its
three "a row with no company is refused" cases became "the trigger derives it", one per level, which is now
the only per-level test of the trigger — RED without `V50` (3 of 18 failed) and GREEN with it.
