# `variant_listing.enabled` leaves the schema (issue #777)

**Status**: implemented — the availability toggle is out of scope by decision: `V79` drops `variant_listing.enabled` behind a guard that refuses while a hand-written `false` exists, `VariantListing` no longer declares the field, and ADR-0006 decision 5 states that "sellable in this store" means the listing row exists and nothing else. `listed` keeps its current behaviour for every client. Nothing is deferred.

**Created**: 2026-11-05 · **Risk**: item 1 — the unit ships a migration, and a `DROP COLUMN` discards the only record of a value when one exists; the guard is what keeps that from happening silently.

## Specs

The requirement is issue #777, whose two branches it leaves to the maintainer. The maintainer chose
**branch 2, "the column goes"**; the acceptance criteria below are quoted verbatim from that branch.

- **S1**
  > Whichever branch is chosen is stated in `docs/adr/0006-catalog-identity.md` where decision 5/6 lives,
  > because it decides what `listed` means for every client.

- **S2**
  > If the column is dropped: `grep -rn "variant_listing" life-control-api/src/main/resources/db/migration/`
  > shows the drop, the projection no longer mentions the column, and the four-state read
  > (`ProductVariantResponse#listed`) keeps its current behaviour under the existing suite.

- **S3**
  > The comment at `ProductVariantRepository.java:197` no longer describes a column with no writer.

- **S4**
  > `product` suites green, and no client-visible field changes meaning without the ADR saying so.

- **S5** (from the issue's own statement of the branch, verbatim)
  > a migration drops `variant_listing.enabled`, `V58`'s decision note says the availability toggle is out of
  > scope by decision, and no field name promises a state nothing can reach.

- **S6** (from the issue's non-goals, kept as a boundary)
  > Per-store price campaigns, availability windows or any scheduling. `product_variants.enabled` (the
  > variant-wide flag), which has its own writer and its own meaning. Touching the four-state
  > `listed`/`stock` contract beyond the flag's derivation.

## Decisions

- **D1 — the decision note lives in `V79`, not in `V58`.** S5 says "`V58`'s decision note says the toggle is
  out of scope", and editing an applied migration is not available: Flyway validates checksums on migrate
  by default, so a byte changed in `V58` makes every existing database refuse to start. `V77` already
  answered this shape — its header states the decision it carries instead of rewriting the file it
  retires — and `V76`'s header does the same for ADR-0006 step 6. The decision is recorded, in the file
  that carries it.
- **D2 — the drop refuses while a `false` exists.** Nothing in the tree can write `false`
  (`VariantListingRepository`'s upsert updates `list_price` only, and no endpoint offers the flag), so a
  row holding it was written by a hand: an operator, a psql session, an old client. That value is then the
  only record of an intent somebody expressed. `V74` and `V77` refuse for the same reason about a quantity
  and a price nobody could rebuild; here it is a decision nobody could see.
- **D3 — the guard needs the `NO FORCE` window.** `variant_listing` is armed by `apply_tenant_isolation`
  (`V58:189`) and `V52:152-153` installs every policy with `ENABLE` **and** `FORCE`, so a migration reading
  it as the owner sees zero rows and would pass on the exact database it exists to refuse. The window is
  opened, checked before it is relied on, and closed by an assertion — `V57`, `V62`, `V64`, `V66`, `V72`,
  `V74` and `V77` already use it.
- **D4 — the entity loses the field in the same commit as the drop.** The mapping and the migrations are
  compared by the PostgreSQL suites, which boot the context over a schema at head and assert the columns the
  entities name; a field mapped against a column the schema no longer has is a failure there, not a warning,
  and `spring.jpa.hibernate.ddl-auto` is `none` (`application.properties:27`), so Hibernate itself will not
  report it.

## Tasks

| ID | Specs | Route | Commit |
| --- | --- | --- | --- |
| T1 | S2, S5, S6 | The change is atomic and ships as one commit: `V79__variant_listing_drop_enabled.sql`, `VariantListingEnabledDropMigrationIntegrationTest` (6 cases), `VariantListing` losing the field, getter, setter and builder method, the three fixtures that called them, the column list in `ProductVariantListingMigrationIntegrationTest`, and the two repository comments. **It cannot be split**: the entity maps a column the migration drops, so a commit that carries only one of the two leaves a tree where a listing insert names a column the schema does not have (entity first) or where the column set is asserted against a schema that still has it (migration first) | `chore(db): drop variant_listing.enabled (V79)` |
| T2 | S1, S3 | ADR-0006 decision 5 and its `## Schema` block state the decision; this record | `docs(adr): the availability toggle is out of scope by decision` |

## Log

| Date | Entry |
| --- | --- |
| 2026-11-05 | **L1 — the maintainer's requests, verbatim**: *"que esta pendiente?"*, then *"ai 777"*. Asked which branch of the issue to take, the maintainer chose **B — borrar la columna (recomendada)**: the ADR already defines "sellable" as row existence (`VariantListing`'s own Javadoc, decision 6), so a writer would turn a dead column into an availability feature with open product questions the issue excludes as non-goals, and `V77` is the precedent for retiring a column nothing writes. |
| 2026-11-05 | **E1 — the map, measured**: `variant_listing.enabled` is created by `V58:99` and read by nothing. The two store-scoped projections derive `listed` as `CASE WHEN l.id IS NULL THEN FALSE ELSE TRUE END` (`ProductVariantRepository.java:208` and `:254`), the single listing writer is `PUT /api/variants/{variantId}/stores/{storeId}` → `ProductVariantStoreStockRequest` (no `enabled` field) → `VariantListingRepository.upsertListPrice` (updates `list_price` only), and ADR-0006 decision 6 defines "sellable in this store" without naming the flag. No Angular code reads `listed`. |
| 2026-11-05 | **E2 — the delivered shape**: `V79` follows `V77`'s five-part structure (window, guard, checked close, drop, self-check). The self-check reads all three tables that carry an `enabled` column, because `product_variants` and `company_stores` both have one and a drop written against the wrong table would take a flag with a writer and a meaning. |
| 2026-11-05 | **E3 — observation, not acted on**: ADR-0006 decision 5 still lists `tax_rate_id` among the listing's columns and `V58:26-31` states it was deliberately not created. That is pre-existing and outside #777's scope; fixing it here would widen a one-decision unit into an ADR correction. |
| 2026-11-05 | **E4 — RED, observed**: the two cases that carry the decision failed on the pre-unit tree for the stated reasons — `the flag nobody can set stops being promised` (`Expecting value to be false but was true`) and `refusing to drop variant_listing.enabled` (`Expecting code to raise a throwable`). The other two (the live `enabled` columns, the `FORCE`d table) passed before and after, which is what makes them guardrails rather than restatements. |
| 2026-11-05 | **E5 — GREEN, measured**: `./gradlew test --tests "com.lifecontrol.api.product.*" --tests "com.lifecontrol.api.inventory.StockLedgerBackfillMigrationIntegrationTest" --tests "com.lifecontrol.api.tenancy.*"` → BUILD SUCCESSFUL, 526 tests across 104 suites, 0 failures, 0 errors. `ProductVariantListingReadIntegrationTest` (the four-state `listed` contract) is 4/4 green with its assertions untouched. |
| 2026-11-05 | **E6 — independent verification**: every spec `met`, no blockers. Two probes it recommended are delivered as tests, because each one pins something the suite did not: a `false` in a **second tenant's** row proves the `NO FORCE` window is load-bearing (with the window broken the guard reads nothing and drops anyway), and clearing the offending value proves the migration's own stated next step actually works. Its three comment-level corrections are applied: `V58:99` not `:100`, `ProductVariantRepository.java:254` not `:253`, and `D4`'s claim about `ddl-auto`. |
| 2026-11-05 | **E7 — slice boundary**: T1 landed as one commit of **657 authored changed lines**, past the ~400-line review budget, and the budget is knowingly exceeded rather than silently. It cannot be split into reviewable halves: the migration without the entity leaves `VariantListing` inserting a column the schema no longer has, and the entity without the migration leaves `ProductVariantListingMigrationIntegrationTest` asserting a column set against a schema that still carries the column. A slice with a broken tree is not a smaller review, it is a review of something that does not run. Most of the count is prose: ~60 lines of SQL logic, ~150 of test logic, the rest the header the repository's migration convention requires (`V77`'s file has the same shape). |
