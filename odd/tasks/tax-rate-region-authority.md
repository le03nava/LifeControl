# Tax rate: a region can give its authority back

Issue **#1030** (`status:approved`). `V95` made the region a second axis of `tax_rate` and gave it authority: a
region that states any row for a code is authoritative for that code, and a document whose date no region row
covers is **refused** instead of falling back (`TaxRateService.java:130-132`). The check reads the existence of
rows and ignores `enabled`, while `enabled` is the only switch the product has — so authority is **open-only**.
Retiring every row a region declared for a code withdraws every statement the region made and leaves it
authoritative with nothing to charge: step 1 finds no enabled row, step 2 finds the region, step 3 is never
reached, and every sales (`SalesOrderService.java:1201`) and purchase (`PurchaseOrderService.java:1241`)
document in that region and code is refused **400** for good. This unit makes authority follow the enabled rows.

**Status**: implemented — the repository derivation, the resolver that reads it, the suites for the twelve
criteria and the `AGENTS.md` correction are the branch's content. Nothing is deferred; the same-day axis was
#888 and already landed (`odd/tasks/tax-rate-same-day-correction.md`).
**Created**: 2026-11-23 · **Risk**: item 3 — the region-authority rule (`V95`'s `D2`) is a resolution invariant
that both document writers already consume.

## Specs

Quoted verbatim from the approved issue body (#1030, `### Acceptance criteria`), with the fixture it shares:
company-wide `IVA` at 16% enabled, and `IVA` at 8% enabled in region `F` from `2026-01-01`.

- **S1.** `D2` still refuses before the region's first day. A sales order for a store in `F` dated `2025-12-15`
  answers **400** naming the code and the region, and stores nothing — today's behaviour, unchanged.
- **S2.** Retiring the region's only row gives the region back. Retire the `F` row with
  `PATCH …/tax-rates/{id}/enable=false`, then create the same order for `2025-12-15`: it answers **201** with
  `tax_rate = 0.160000` from the company-wide row, instead of the 400 the region produced a moment earlier.
- **S3.** The same is true for a date the retired row used to cover. An order dated `2026-02-01` in `F`
  resolves `0.080000` while the row is enabled and `0.160000` after it is retired.
- **S4.** A region that states only another code is not authoritative for this one. With an enabled row in `F`
  for a different code and none for `IVA`, an `IVA` order in `F` resolves the company-wide 16% — pinned,
  because the rule is per `(region, code)` and this change must not widen it.
- **S5.** A region with no rows at all is unchanged. No row in `F` for the code, before and after the change:
  the company-wide rate applies.
- **S6.** The refusal returns as soon as the region speaks again. Re-enabling a regional row for the code
  restores the 400 for documents dated before its `effective_from`, so authority is a state the region can
  enter and leave, not a one-way flag.
- **S7.** Purchases follow the same path. A purchase order line in a store of `F` for the same code and dates
  produces the same 400 and the same 16% after the retirement, proving `PurchaseOrderService.java:1241` was not
  left behind.
- **S8.** An empty answer is not zero tax. With no company-wide rate either, the retired region's document is
  refused by the caller's existing 400 rather than charged `0` — the `Optional.empty()` contract
  `TaxRateService.java:55` states.
- **S9.** The rated document that already exists is untouched. A sales order priced at 8% while the row was
  enabled keeps `tax_rate = 0.080000` after the retirement; `git diff` shows no migration and no data statement.
- **S10.** No schema and no contract moves. `git diff --stat` shows no migration, no endpoint, no request field
  and no response field: the change is the repository derivation and the guard that reads it.
- **S11.** The suites that pin the rule are updated only where the rule changed, and the new cases above are
  added to them: `TaxRateServiceTest`, `TaxRateSaleAcceptanceTest`, and the region-gap cases of
  `TaxRateMigrationIntegrationTest` if it pins the retired-row behaviour.
- **S12.** `./gradlew test spotlessCheck` passes.

Non-goals, verbatim: no hard `DELETE` and no in-place edit; no new table, column or endpoint for a "not ours
yet" / "no longer ours" statement; no change to resolution step 1 (a retired row still never resolves); no
retroactive rewrite and no recalculation on deploy; no screen work (#887 owns the surface); no answer for a
region that wants to stay authoritative through a **period** with no rate.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1–S6, S8 | `TaxRateRepository`: the region-authority existence query becomes `…AndEnabledTrue` (still ignoring the date, which is what keeps `D2` intact); `TaxRateService.resolve` reads it, and the javadocs that state "a retired row still counts as a statement" are corrected — those in `resolve`, in `TaxRateRepository`, and in `setTaxRateEnabled` | C1 |
| T2 | S1–S8, S11 | Tests: the resolver cases for the six behaviours, the sales acceptance for S2/S3, the purchase path for S7, and the empty-answer case for S8 | C1 |
| T3 | S9–S12 | `AGENTS.md` (the tax-rate section states authority as open-only), the record's evidence log, `./gradlew spotlessCheck test` | C2 |

## Log

- **L1** (verbatim) «has el push y PR» — #888 shipped as PR #1031 (merge commit `ef92d74c`).
- **L2** (verbatim) «has el merge» — merged, and #888 closed `COMPLETED` through `Closes #888`.
- **L3** (verbatim) «has la limpieza y inicia con 1030» — the `feat-tax-rate-same-day-correction` worktree and
  both branches removed per the convention (`git worktree remove` → `prune` → `branch -d` →
  `push --delete`); this unit starts on `feat/tax-rate-region-authority` from `origin/main` `ef92d74c`.
- **L4** The change is one predicate: authority asks whether the region holds an **enabled** row for the code,
  and still ignores the date — a region whose rows are all in the future keeps refusing documents dated before
  them, which is exactly what `D2` was written for.
- **L5** Implemented against this spec: the enabled-only authority query replaces the any-row one, `resolve`
  step 2 reads it, and the three javadocs that stated the open-only rule (`resolve`'s step 2, the repository
  query, `setTaxRateEnabled`) are corrected. RED first and for the intended reason — after
  `PATCH …/enable=false` the resolver still threw `TaxRateRegionGapException` and both document writers still
  answered 400 — then GREEN: `test --tests '*TaxRate*' --tests '*TaxRegion*'` → **55 tests, 0 failures, 0 errors,
  0 skipped**.
- **L6** S3 cannot be pinned end to end, and the reason is structural rather than a gap in the work: neither
  document request carries a date (a sale uses `LocalDateTime.now()`, a purchase order uses `created_at`), so no
  API-level case can target a past day the retired row used to cover. It is pinned at the resolver level
  (`aRetiredRowStopsCoveringTheDayItUsedToAnswer`), and that limit is stated here instead of being papered over
  with a case that would pass for the wrong reason.
- **L7** `TaxRateRegionGapException`'s javadoc still described the open-only rule after T1 landed; corrected in
  T3. A class that documents the old contract is how the next reader re-introduces it, and this is the second
  time in this family of units that a javadoc, not a test, was the thing left behind.
- **Evidence (2026-11-23)**: `test --tests '*TaxRate*' --tests '*TaxRegion*'` → **55 tests, 0 failures**; the
  module gate `spotlessApply spotlessCheck spotbugsMain test` → **5284 tests, 0 failures, 0 errors, 0 skipped**,
  BUILD SUCCESSFUL (17m47s); `.github/scripts/tests/odd-status-headers.test.sh` 78 passed;
  `odd-citations.test.sh` 29 passed. S10 holds by `git diff --stat`: no migration, no endpoint, no request field
  and no response field — two classes of code, one javadoc-only exception class, three suites and `AGENTS.md`.
  Slices: **C1** the predicate, the resolver that reads it, the corrected javadocs and the suites that pin
  S1–S8, carrying risk item 3 alone; **C2** this record and the `AGENTS.md` correction. C1 is past the
  ~400-line budget and the boundary is stated rather than hidden, for the same reason as the previous unit: a
  guard separated from the test that proves it is the one split this repository's budget avoids.
