# ODD feature: catalog-identity

**Repository**: LifeControl — `life-control-api/` (migrations, product and variant modules), `life-control-app-angular/` (the product form and the variant dialog), `docs/adr/`, `odd/tasks/`
**Branch**: `feat/catalog-identity` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-catalog-identity`
**Status**: implemented — T1 is the mode column of the accepted ADR-0006 (`V54`) and T2 is the mandatory
measure unit (`V55`) with the write path that states it. T3 (the optional barcode and the mode trigger) and
T5 (the single creation transaction and the Angular form) are the remainder this record still owes, in that
order, each with the write-path change that keeps the branch green; T4 is **blocked** on ADR-0002 and is
named as such in its own row.
**Created**: 2026-11-01
**Risk**: items 1, 3 and 6 — item 1 because `measure_unit_id SET NOT NULL` and the `variant_mode`
backfill rewrite stored catalogue facts, item 3 because `POST /products` changes shape under an Angular
form that already calls it, and item 6 because the mode trigger is the only thing standing between a
`SIMPLE` product and a second variant no service path would catch.

## Objective

Implement accepted ADR-0006 (`docs/adr/0006-catalog-identity.md`) in `life-control-api` and the Angular
product surface: a product is a family with at least one variant, a variant declares a unit, and price
becomes a listing. This unit delivers the first two of those three.

## Specs

S1. `docs/adr/0006-catalog-identity.md`, **accepted 2026-11-01**, is the specification of this unit. Its
    decisions 1–7, the `## Schema` block, invariants C1–C7 and `## Migration from the current tree`
    steps 1–6 are authoritative, in that order.

S2. **Decision 2, verbatim**: «**A product always has at least one variant, and creating one is one
    transaction.** `SIMPLE` means exactly one, written by the backend with the product and never shown
    as a step; `MULTI` means the variants are declared in the creation request and at least one is
    required — none is refused.»

S3. **Decision 4, verbatim**: «**`variant.bar_code` is required for `MULTI` and optional for
    `SIMPLE`**, unique per tenant when present. A single-variant article has no barcode to give.» The
    requirement therefore stops being a DTO annotation in T2: which mode the product is decides it, and
    `@NotBlank` on `barCode` cannot ask that question.

S4. **The trigger's two messages are quoted, not paraphrased**, because they are what an operator reads:
    `'product % is SIMPLE and already has a variant'` and `'bar_code is required for a variant of MULTI
    product %'`.

S5. **Migration step 2, verbatim**: «The migration runs a report first, not a write: … It then **aborts**,
    listing those rows, and guesses nothing.» The report is part of the migration, so a refused run is
    atomic with it — a half-applied V54 would leave the next run colliding with a column it already added.

S6. **What this unit does not deliver, and the dependency that holds it**: decision 5 and steps 4–5
    (`variant_listing`, the `product_variant_store_stock` split, the read paths) cannot run yet, because
    the accepted `## Schema` block declares `currency CHAR(3) NOT NULL REFERENCES currency (code)` and
    `tax_rate_id UUID REFERENCES tax_rate (id)` and **no migration in the tree provides either table** —
    they are ADR-0002's T1 and T4. Step 6 (mode immutability) is stated by the ADR as landing with
    ADR-0001's ledger. Recorded at acceptance in the ADR's `Accepted:` line and in `docs/adr/README.md`.

S7. **T1 does not change a single read path or write path.** `product_variant_store_stock` keeps holding price and
    stock together until step 4, so the two store-scoped `INNER JOIN` queries
    (`ProductVariantRepository.java:157` and `:181`) and every screen that reads them stay exactly as
    they are: this unit adds a mode, a required unit and an optional barcode, and nothing else moves.

## Tasks

| ID | S# | Work | Depends on | Route |
| --- | --- | --- | --- | --- |
| T1 | S1, S5, S7 | `V54`: `products.variant_mode` (`SIMPLE`/`MULTI`, CHECK, derived backfill) plus the self-check that refuses a derivation which read nothing, inside the `NO FORCE` window ADR-0005's `FORCE` makes mandatory | — | inline; done |
| T2 | S1, S6 | `V55`: `product_variants.measure_unit_id SET NOT NULL` behind the report that aborts, and the write path that agrees with it — `ProductVariant` maps the column as required, `ProductVariantRequest` demands it, and the suites that build a variant today declare a unit | T1 | inline; done |
| T3 | S1, S3, S4 | `V56`: `bar_code DROP NOT NULL`, the partial `uq_variant_bar_code` replacing `uq_product_variants_company_bar_code`, and `trg_variant_matches_mode` — with the **minimum** write path that keeps the trigger truthful: `Product.variantMode` mapped, `POST /products` accepting it with `SIMPLE` as the creation default, and the barcode requirement decided by the product's mode in the service instead of `@NotBlank`. The response exposure, the `variants[]` list and the Angular form stay in T5 | T2 | inline |
| T4 | S1, S6 | `variant_listing` and the price split out of `product_variant_store_stock` (decision 5, steps 4–5), armed in `tenant_table` in the migration that creates it | **blocked** — `currency` is ADR-0002's T1 and `tax_rate` its T4, which D-d still gates; step 5 also needs ADR-0001's position projection | — |
| T5 | S1, S2 | Decision 2, the rest of it: `variants[]` on the creation request, product and variants written in **one** transaction, `variantMode` on the response, C1's delete guard, and the Angular form split into "the article" and "its variants" with the barcode required only for `MULTI` | T3 | inline |
| T6 | S1, S6 | Decision 1's immutability: refuse a `variant_mode` change once a variant holds stock | **blocked** — ADR migration step 6 states this lands with ADR-0001's ledger, and today the only "movement" is the per-store row | — |

## Log

**L1** — «que esta pendiente de los adr y con que conviene continuar»

**L2** — «si acepto ADR-0006»

**2026-11-01 — the acceptance, and where it was recorded.** The maintainer accepted ADR-0006 in this
session. The status line, the `Accepted:` line and `docs/adr/README.md` carry it; the header of
`odd/tasks/architecture-redesign-adrs.md` had its count of `proposed` ADRs repaired because accepting one
made it false. The acceptance states the fact that this record's S6 repeats: the accepted `## Schema`
block references two tables of ADR-0002, so the listing slice sequences after that catalog while the
mode, unit and barcode slices do not.

**2026-11-01 — the finding that shaped T1: ADR-0005's `FORCE` makes ADR-0006's migration step 2 blind.**
`V52:154-155` calls `ENABLE` **and `FORCE`** on every table it arms and `V53:108-114` arms `products` and
`product_variants`. `FORCE` applies the policy to the table **owner**, and the owner is the role Flyway
runs as (`docker-compose.yml:529`), so from `V53` on a migration that reads or writes tenant rows matches
**zero** of them unless it sets `app.company_id` first — the trap ADR-0005 recorded as its own correction
3, met here for the first time. Two consequences, both in `V54`: the backfill and the report run inside an
`ALTER TABLE … NO FORCE` window restored before the trigger is created, and the mode trigger sets the
scope from `NEW.company_id` before it reads the product, because otherwise it would read no product, get
a NULL mode and validate nothing in silence. The suite migrates a scratch database **as
`lifecontrol_owner`** rather than under the container's superuser, which bypasses the policies and would
report both mechanisms working while delivery's `NOSUPERUSER` owner saw nothing.

**2026-11-01 — why T1 is the mode alone, and not the ADR's steps 1–3 in one migration.** The first draft
was one `V54` carrying all three: the mode with its backfill, `measure_unit_id SET NOT NULL`, the optional
barcode and the trigger. It ran green on its own two cases and would have reddened the tree on contact,
measured rather than guessed: `measure_unit_id` becoming required refuses every suite that builds a
`ProductVariant` without a unit (`ProductVariantStoreFilterIntegrationTest.java:255-266` is the shape, and
nine other PostgreSQL suites build variants the same way), and the trigger refuses the second variant of a
product whose mode nobody states yet — `ProductService.createProduct` writes no variant at all
(`ProductService.java:65-91`), so every product a suite creates is `SIMPLE`. Three decisions that each
need their own write-path change became three slices, in the order the ADR's own steps already have:
schema-then-contract, once per decision. The ADR's steps are ordered, and splitting them across files
changes no decision — it changes which review unit carries each consequence.

**2026-11-01 — T1's evidence and the slice boundary.** `V54` and its suite are the branch's second
commit; the first is the acceptance itself. Measured by the harness on `origin/main`: **499 authored
lines across those two commits** (37 for the acceptance, the rest for the migration and its suite), which
is past the ~400-line review budget, so this unit is delivered as **chained pull requests**: the
acceptance alone first — it is documentation and can merge on its own — and `V54` with its suite second.
The next slice (`V55`, the mandatory unit) starts from whichever of the two is the head by then and is
not stacked on an unmerged branch. Verification observed on T1: `ProductVariantModeMigrationIntegrationTest`
runs 2 cases green — the derivation with one, two and three-variant products and the `CHECK` refusing a
third value, plus the premise case that the owner reads zero rows without the tenant scope — and
`./gradlew test --tests 'com.lifecontrol.api.product.*'` ran **458 cases across 95 classes, 0 failures**, so
the new column reddens nothing that already existed. `odd-status-headers.test.sh` audits 134 records with
0 findings.

**2026-11-01 — T2, delegated to a writer, and what it changed.** `V55__variant_measure_unit_required.sql`
opens the same `NO FORCE` window `V54` needed, runs ADR-0006 step 2 as a report that aborts naming every
unitless variant as `<sku> / <variant_name> (variant <id>)`, then applies `SET NOT NULL`, closes the window
and refuses to finish if either table is not `ENABLE`+`FORCE` again. The write path moved with it: a
missing unit is now a 400 that names `measureUnitId` (`MeasureUnitRequiredException`) and it is thrown
before the catalog is read, so no caller reaches the column; `verifyMeasureUnitChangeAllowed`'s
first-declaration allowance is untouched, and it is now the only way a row the report names becomes valid.
Nine PostgreSQL suites that build a `ProductVariant` directly declare a real catalog unit (`H87`, "Pieza"),
and the service case that asserted a variant could carry no unit became the refusal it now is.
`ProductVariantStoreFilterIntegrationTest`'s `usedMeasureUnitTypes_ReadRealSql` kept every assertion it
had and re-expressed "no constraint at all" with a single-variant product, which is the only shape that can
still produce it — checked by the parent, because a rewritten assertion is where a slice goes quiet.

**2026-11-01 — the consequence T2 does not fix, recorded where the next slice reads it.** The Angular
variant dialog still initialises `measureUnitId` to `null` and does not require it
(`product-variant-dialog.ts:324`), so the form now receives `400 measureUnitId is required` for a variant
created without a unit. That is the intended refusal — a silent bad row is worse — and the form's own
requirement belongs to the Angular slice, which is T5. Nothing in `life-control-app-angular` was changed or
run by T2.

**2026-11-01 — T2's size, and why it is one pull request and not two.** Measured on the branch: **~726
authored lines across 17 modified and 3 new files**, past the ~400-line budget. It is deliberately not
chained: the migration, the entity/DTO/service rule and the nine repaired fixtures are one fact stated
three times, and either half alone is either red (the column refuses what the fixtures still write) or
meaningless (a report with nothing to refuse). The slice after it, T3, is a separate pull request and
starts from this one's head only once both are merged.

**2026-11-01 — T3, and the defect the review caught: a trigger that widened the policy it ran under.**
`V56__variant_barcode_optional.sql` lands decision 4 — `bar_code` nullable, the partial
`uq_variant_bar_code` replacing `uq_product_variants_company_bar_code`, and `trg_variant_matches_mode`
with the ADR's two messages verbatim — plus the minimum write path that keeps the trigger truthful:
`Product.variantMode` mapped with a `SIMPLE` default, `ProductRequest.variantMode` closed to the two
values, and the barcode requirement asked of the product's mode in the service (400 naming `barCode`,
`BarcodeRequiredException`) instead of by `@NotBlank`.

The delegated writer's first version of the trigger opened with `PERFORM set_tenant_scope(NEW.company_id)`
so that a writer with no scope would still read the mode — the same instinct that makes `V54` and `V55`
open their windows. Reviewed against the execution order it was a **tenant-isolation hole**: a BEFORE ROW
trigger runs **before** the policy's `WITH CHECK`, and the check is evaluated against the row the trigger
left behind, with the session setting the trigger had just changed. Setting the scope from `NEW.company_id`
therefore makes the policy compare the row to its own claim, and an insert naming any tenant passes: the
barrier ADR-0005 exists for answers "yes" to whatever the row says. Measured, not argued — the writer's own
case showed the trigger firing before the tenancy refusal on a cross-company insert.

The fix is subtraction: the trigger reads under whatever scope its writer has, and a mode that comes back
NULL `RAISE`s instead of skipping the check — "the product could not be read, so the rules it must match
are unknown; a variant was NOT written". That is the honest answer, and it is the same refusal the policy
would produce one statement later. A migration that must insert variants uses the `NO FORCE` window `V54`
and `V55` document; no third door was added. The case that used to pin the scope call now pins the refusal.
Verification after the fix: **979 cases across 241 classes, 0 failures** over `product`, `goodsreceipt`,
`inventory`, `salesorder` and `purchaseorder`.

**2026-11-01 — T5's backend half: the creation transaction, and the reading of decision 2 it forced.** The
delegated writer delivered `ProductRequest.variants` (required, non-empty), `ProductService.createProduct`
writing the product and every variant in one transaction through the existing
`ProductVariantService.createVariant` — so the unit rule, the barcode-by-mode rule and the duplicate keys are
the ones `V55` and the trigger already prove, not a second copy — `variantMode` on the response, and C1's
guard: deleting the last live variant of a product is a 409, and `ProductRepository.countProductsWithoutVariants`
is the query the ADR's own C1 row asks for.

The ADR's «`SIMPLE` means exactly one, written by the backend with the product» can be read two ways, and the
tree settles it: `product_variants.variant_name` is `NOT NULL`, so nothing may invent a variant the caller did
not name. The reading taken is therefore the **count**: the request always declares its variants, `SIMPLE`
accepts exactly one and refuses two with a 400 naming the mode (not the trigger's message), and zero is
refused for both modes with a 400 naming `variants`. The other reading would have needed a value the schema
forbids. Recorded here because it is the one place this slice decided rather than transcribed.

Verification observed by the parent: **994 cases across 242 classes, 0 failures** over `product`,
`goodsreceipt`, `inventory`, `salesorder` and `purchaseorder`. The writer's own RED was 12 failing cases over
the three suites it wrote, all behavioural (`WantedButNotInvoked`, no exception where the rule demands one,
rows written that must not exist), and its integration suite observes the rollbacks through a separate
transaction instead of through an interaction — which is what makes "nothing is written" a measurement.

**2026-11-01 — what the Angular half still owes, named before it starts.** `POST /products` now requires
`variantMode` and `variants`, so the product form receives `400 variants is required` until it sends them, and
the variant dialog still does not require a unit (`V55`) or relax the barcode for a `SIMPLE` product (`V56`).
That is the next slice, and it is why this record does not call T5 done.

**2026-11-01 — T5's Angular half: the form splits by mode, and T5 closes.** The delegated writer delivered
the branch ADR-0006 decision 2 needs on the screen: `SIMPLE` collects its one variant inline — name, unit,
optional barcode — as part of the article and never as a step; `MULTI` collects a list with add/remove and
one required barcode each; the create call posts `variantMode` and `variants` and the update posts neither.
The variant dialog requires a unit (the `400 measureUnitId is required` the screen received after `V55`) and
requires a barcode only for a `MULTI` product, reading the mode from the product the dialog already loads —
no new request. The response's new `variantMode` is respected in the models.

Verification observed by the parent: **193 files, 4063 Angular cases, 0 failures**; lint and
Prettier clean. The writer's RED was taken against a scratch copy of the base (`git archive`, outside the
worktree, removed afterwards) so the four cases that state the new rules were observed failing on the old
behaviour rather than asserted after the fact.

**The contract wart this slice had to work around, named as the next unit.** The catalog endpoints' 400s for
`variants`, `barCode` and `measureUnitId` carry **no `errors` map**: `GlobalExceptionHandler` maps a bare
`IllegalArgumentException` to `{status, message, path, timestamp, correlationId}`, and `ProductRequest.variants`
is not `@Valid`-cascaded, so only `variantMode`'s `@Pattern` and the variant endpoint's `@NotNull` produce a
real field map. The form therefore attributes those refusals by the field name each message opens with
(`models/product-field-errors.ts`), which is a coupling to backend wording and a documented one: the module
says it is coupled, and the case fails loudly if a message is reworded. It is not fixed here because the fix
is in shared code — every `IllegalArgumentException` in the API maps through that handler — and a change there
is its own reviewed unit, not a rider on a UI slice. The fix is small and named: a common field-violation
exception carrying its field, mapped to `errors: {field: message}`, plus `@Valid` on `variants`.

**2026-11-02 — the field-error contract is fixed, on its own branch.** The wart T5 worked around is gone:
an abstract `FieldViolationException` carries the field it is about and gets its own `@ExceptionHandler`, so
the four catalog refusals answer `400` with `errors: {<field>: <sentence>}` next to the body they already
served — `variants`, `variantMode`, `barCode`, `measureUnitId` — while the ~105 other
`IllegalArgumentException` sites keep the message-only envelope **by type** and not by a condition a later
edit can forget. `ProductRequest.variants` is validated in cascade, so a nested violation is refused at the
boundary under bean validation's key (`variants[0].measureUnitId`), and a first-level key and a nested one
cannot lose each other. The client reads the map first, translates the bracket key to the control path
Angular's `get()` resolves, and keeps the wording match only for a body with no map; the screen copy is
selected structurally, so a duplicate-barcode `409` under the same key keeps its own sentence.

The mechanism is the part worth keeping: the advice now serves **two** 400 envelopes (`ErrorResponse` and
`ValidationErrorResponse`), and resolution by handler specificity is what keeps them apart. A branch inside
the shared `handleBadRequest` would have left every other refusal one `instanceof` away from a map it must
never have. Evidence: a differential probe over the base commit and the change, seven probes, differs in
exactly one line — the catalog refusal gained its `errors` entry. Product suite `493` cases green, company
`416` green, Angular `4076` green, lint and Prettier clean.

**2026-11-02 — the seam the chained branches opened, found and fixed before the slice committed.** The T5
integration suite was written before `V57` made `companies.currency` required, and it seeds its company
through the four-column insert that belongs to the suites holding a scratch database before that column
exists: six cases failed on `NOT NULL` the moment the two branches met. Measured on the unmodified base
first — it was already red there — and fixed in its own commit so the review sees the seam and not a
mystery inside the error-contract diff.

**2026-11-02 — T4's schema half: `variant_listing` exists, and the price is copied rather than moved.** `V58`
creates the table in decision 5's shape, copies every `product_variant_store_stock` row that carries a
`list_price` into it with the currency read from the company (`V57`), and arms it with
`apply_tenant_isolation('variant_listing')` **in the file that creates it** — which is what the startup check
requires and what makes the table the seventeenth armed tenant table. Two departures from the ADR's schema block
are deliberate and documented in the migration header: `tax_rate_id` is not created, because `tax_rate` is
ADR-0002's T4 and D-d still gates it — a column cannot reference a table that does not exist, so it arrives by
`ALTER` in the slice that creates that table; and `product_variant_store_stock` keeps `stock` and `cost_price`,
because the `ADJUSTMENT_IN` movement of step 4 needs ADR-0001. That makes the price a **transitional
two-representation state**, retired by the next slice for the reads and by ADR-0001 for the position.

The order inside the file is the substance: create, open the `NO FORCE` window on the two armed tables the copy
reads, copy, self-check the count still inside the window, close it, and arm last — an armed table refuses an
insert from its owner with no tenant scope, and the copy spans every tenant.

Arming a seventeenth table broke the armed-set pin in two suites, and the review of that break is its own
finding: `TenantPoliciesIntegrationTest` and `TenantScopeFunctionsIntegrationTest` freeze the same set in two
complementary shapes — an exhaustive list versus a per-table policy/kind/key check — so both were **grown, not
relaxed**: the entries added, the counts moved to seventeen (including the one a spot check found still in a
method name), and every assertion left byte-identical, so the new table runs through the existing checks instead
of beside them. Verification observed by the parent: **551 cases across 109 classes, 0 failures** over `product`
and `tenant`.
