# ODD feature: variant-listing-tax-rate

**Repository**: LifeControl — `life-control-api` (migrations, `product`, `tax`), `docs/adr/`, `odd/tasks/`
**Branch**: `feat/variant-listing-tax-rate` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-variant-listing-tax-rate`
**Status**: implemented — the pointer ADR-0006 decision 5 declares is on `variant_listing` since `V88`, with
a foreign key that cannot cross a tenant. What the ADR asks for and what a listing still owes are two
different things, and this record says both: the column is the schema half, and **no write path resolves a
rate for a listing yet** — that is a later slice, named here rather than implied. `tax_rate` itself arrived
with `V82` (ADR-0002's T4), which is the condition `V58:26-32` set for this `ALTER`.
**Created**: 2026-11-08
**Risk**: item 2 — the change is a tenancy boundary and nothing else: the composite foreign key is what stops
one company's listing naming another company's rate, and a single-column reference would look identical in
`information_schema` while silently pointing across tenants. Item 3 is argued and refused: the ADR declares
the column, so adding a nullable one with no writer removes no contract, and the `V58` column list that had to
change is a test, not a consumer.

## Specs

S1. `docs/adr/0006-catalog-identity.md`, decision 5 (`:57-59`), **verbatim**: the listing replaces the price
    half of `product_variant_store_stock` — «`(variant, store)` with `list_price`, `currency`,
    `tax_rate_id`, `enabled`, and **no stock and no cost**».

S2. The same ADR's `## Schema` block (`:109-123`), **verbatim for the column** (`:116`):
    `tax_rate_id UUID REFERENCES tax_rate (id)`.

S3. `V58__variant_listing.sql:26-32`, **verbatim**, is the reason this unit exists and the condition it
    awaited: «**`tax_rate_id` is NOT created.** … `tax_rate` belongs to ADR-0002's T4 … a column cannot
    reference a table that does not exist, so the column is added later, by an `ALTER`, in the slice that
    creates `tax_rate`.»

S4. `V58__variant_listing.sql:89-91`, **verbatim**: «Same column order, types and constraint names as
    `docs/adr/0006-catalog-identity.md:109-123`, so the `ALTER` that adds `tax_rate_id` later is an
    addition to this statement and not a rewrite of it.»

S5. `docs/adr/0005-tenant-isolation-rls.md` accepted, is the authority a single-column
    `REFERENCES tax_rate (id)` cannot satisfy alone: `tax_rate` is a tenant table armed with `ENABLE` and
    `FORCE` by `V82`, so an id-only reference lets one tenant point at another tenant's rate. The shape
    this repository uses for that case is the composite key `(company_id, id)`, exactly as
    `fk_listing_variant` and `fk_listing_store` do in `V58:114-119`.

S6. `V82__tax_rate.sql:41-55` — the condition S3 named is met: `tax_rate` exists, with the unique key
    `uq_tax_rate_company_code_from` and **no** unique key on `(company_id, id)`.

## Tasks

- [x] **T1** — `V88__variant_listing_tax_rate.sql`: `variant_listing.tax_rate_id` (nullable `UUID`), the
      composite foreign key `fk_listing_tax_rate` to `tax_rate (company_id, id)`, the unique key
      `uq_tax_rate_company_id` it needs as a target, and the self-checks. `feat/variant-listing-tax-rate`.
- [x] **T2** — `ProductVariantListingMigrationIntegrationTest`: the `V58` column list now names the column,
      and two cases prove the pointer — it is present, `uuid`, nullable and `NULL` on the row `V58` copied,
      and it refuses another tenant's rate by the constraint's name. Same commit as T1.
- [x] **T3** — the three prose sites that stated the column's absence as a fact (`VariantListing.java`,
      `TaxRate.java`, `TaxRateResolution.java`) and `docs/adr/README.md`'s stale `tax_rate` paragraph. Same
      commit. **`V58` itself is not edited**: it is an applied migration, and a changed file is a Flyway
      checksum failure on every database that already ran it. Its departure note stays as the history of
      what that file did; `V88`'s header is where the debt it named is closed.
- [ ] **T4** — *owed, not in this unit*: a writer. Nothing resolves a rate for a listing yet, so the column
      is `NULL` for every row this tree can write. It lands with the slice that resolves a rate at the
      store-price write path (`TaxRateService` already answers with the row id it would store).
- [ ] **T5** — *owed, not in this unit*: `sales_order_items.tax_code`'s copy of the rate onto the line, which
      is ADR-0002 T4's remaining debt and is independent of this pointer.

## Log

**L1** — the request, verbatim:

> que esta pendiente de los adr?

followed by

> abre la unidad de trabajo

**L2** — 2026-11-08 — the survey that produced T1. All six ADRs are `accepted` and their six records read
`**Status**: implemented` with zero open checkboxes; what remains is not a plan but three debts named in
prose. Two are code: ADR-0002 T4's `sales_order_items.tax_code` copy, and this pointer — `V58` excluded it
on a condition (`V82`) that has since been met, so the `ALTER` it promised was simply never written.
`docs/adr/README.md` still says «no migration in the tree creates that table», which stopped being true when
`V82` landed; that paragraph is corrected in T3 because a reader planning the next slice is exactly who it
misleads.

**L3** — 2026-11-08 — decision on the FK shape, taken here and not in the ADR: S2 writes `REFERENCES tax_rate
(id)` and this unit does not, and the departure is deliberate. `tax_rate` is armed `ENABLE`+`FORCE` by
`V82`, and PostgreSQL's referential checks bypass row security by design, so an id-only reference would
accept company A's listing pointing at company B's rate — a row A's own scope cannot then read, which the
resolver would see as "no rate" and tax at zero in silence. The composite key is refused by the constraint
instead, which is the property `fk_listing_store` already proves one table over. Rejected alternative: FK on
`tax_rate (id)` with a service-level company check — that is the call-site enforcement ADR-0005 exists to
replace, and it would sit on the one path that decides money.

**L4** — 2026-11-08 — RED observed before GREEN, on the suite the change touches
(`ProductVariantListingMigrationIntegrationTest`, at `V87`): the three new-or-changed cases failed and the
three pre-existing cases kept passing. The `V58` column list failed on the missing name, and the pointer case
failed at its first read — `information_schema.columns` returned no row for `tax_rate_id`, which is the
column itself being absent and not a broken query. GREEN after `V88`: six cases, zero failures.

**L5** — 2026-11-08 — `V58` is **not** edited, and that is a deliberate correction to the plan this record
started with. It is an applied migration: changing a byte of it is a Flyway checksum failure on every
database that already ran it. Its note about the missing column is the history of what that file did, and it
stays; `V88`'s header is where the debt is closed and where a reader looking for the ALTER is sent.

**L6** — 2026-11-08 — the checks, and what they cost. `spotlessCheck` green (the module formats with
palantir). The whole `life-control-api` suite ran green with `V88` applied by every integration container —
**4613 tests across 1085 suites, 0 failures**, 11m34s — which is the check that matters for a migration:
Flyway applies it on every suite's boot, so a broken file fails everything, not one class. The slice boundary
is the single commit `b5f4b9ca`, 407 authored changed lines: at the review budget rather than under it, and
not split because the migration, its test and the prose the new fact invalidates are one unit — a PR that
shipped the migration alone would leave three files asserting the column does not exist.
