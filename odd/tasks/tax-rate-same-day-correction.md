# Tax rate: a retired row stops occupying its day

Issue **#888** (`status:approved`). The uniqueness over `(company, tax_code, scope, effective_from)` is
stated as two partial unique indexes (`V95:89`, `V95:94`) and a service guard
(`TaxRateService.requireNoRateForTheSameDay`), and neither considers `enabled` — so retiring a row leaves it
owning its day and a mistyped **value** can never be re-declared on that same day. This unit moves the
invariant onto **enabled** rows, which is the state the resolver already answers
(`TaxRateRepository` `findFirst…EnabledTrue…`).

**Status**: implemented — the migration, the two repository derivations, the retire→enable guard, their
tests and the `AGENTS.md` correction are the branch's content. Nothing is deferred; the sibling axis
(a misspelled **scope**) is #1030 and is not this unit's work.
**Created**: 2026-11-19 · **Risk**: item 1 and item 3 — a migration over a fiscal table, and a uniqueness
invariant that both document writers already consume.

## Specs

Quoted verbatim from the approved issue body (#888, `### Acceptance criteria`). Each `S#` is one check the
verification reads back against.

- **S1.** Declaring `IVA` at 16% for a past day, retiring it with `PATCH …/enable=false`, then declaring
  `IVA` at 8% for that same day succeeds and returns 201; the list shows both rows, the first one retired.
- **S2.** A sales line for a document dated that day resolves `tax_rate = 0.080000`; a document dated
  before the first row keeps resolving what it resolved before.
- **S3.** While an **enabled** row occupies `(company, code, scope, day)`, declaring another enabled row
  for it answers 409 and writes nothing. Once that row is retired, the same declaration succeeds. The
  refusal is a function of the occupying row's `enabled`, and of nothing else.
- **S4.** Declaring an enabled company-wide row for the same day as an enabled region-scoped row still
  succeeds, and declaring two enabled company-wide rows for that day still answers 409.
- **S5.** Declaring two **retired** rows for the same day and scope succeeds, and neither is ever returned
  by either resolution query. This is the state the new predicate makes representable, so it is pinned
  rather than left to be discovered.
- **S6.** `PATCH …/{id}/enable=true` on a retired row whose day now holds an enabled row in the same scope
  answers 409 naming the tax code and the scope, and writes nothing — the named refusal
  `requireNoRateForTheSameDay` already produces, not the generic constraint sentence of
  `GlobalExceptionHandler.java:88-94`.
- **S7.** The two partial unique indexes still exist after the migration, each with the predicate that now
  includes `enabled`, and the migration's self-check asserts the **predicate** and not only the index's
  existence — a key that kept `company_region_id IS NULL` without `enabled` would leave the whole fix
  unusable while reading as applied.
- **S8.** The migration asserts its own result and aborts with a message naming the offending
  `(company, code, scope, day)` if any exists with more than one enabled row. **That audit has to read
  rows, so it must open a `NO FORCE ROW LEVEL SECURITY` window on `tax_rate` for its duration and re-arm it
  before the file ends**, asserting that it is re-armed (`V93__purchase_orders_money.sql:77-78` and
  `:231-252` are the pattern). `tax_rate` is armed with `ENABLE` + `FORCE` (`V82`) and Flyway runs as its
  owner, whom the policy also filters, so the same `SELECT` without the window reads **zero rows** and the
  `RAISE` never fires — the exact failure `V95` documents in its own step 3. An audit that cannot
  distinguish "no duplicates" from "read nothing" is not an assertion.
- **S9.** `git diff --stat` proves no endpoint, request field or response field moved: the fix is a
  migration, the two repository derivations, and the one same-day guard in `setTaxRateEnabled`.
- **S10.** `./gradlew test spotlessCheck` passes, including the suites that pin the resolution rule, the
  region gap and the migration's own indexes.

Non-goals, also verbatim: no in-place editing; no hard `DELETE`; no change to the resolution rule; no
retroactive rewrite of stored documents; no re-opening of a retired row's value; no screen work (#887 owns
the surface); no bulk correction path. And **no repair of a misspelled scope** — that is #1030.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S7, S8 | `V114__tax_rate_unique_enabled_only.sql`: drop and re-state both indexes with the `enabled` predicate, the RLS-window audit naming duplicates, and a catalog self-check of each predicate + the re-armed window | C1 |
| T2 | S3, S4, S5 | `TaxRateRepository`: the two same-day derivations become `…AndEnabledTrue`; `requireNoRateForTheSameDay` reads them; their javadocs stop claiming a retired row occupies its day | C2 |
| T3 | S6 | `TaxRateService.setTaxRateEnabled`: on the retired→enabled transition only, run the same guard so the refusal is the named 409 rather than a `DataIntegrityViolation` | C2 |
| T4 | S1–S6, S10 | Tests: the service cases for the six behaviours, the migration's predicate assertions, the sales acceptance for S2, and the resolver never answering a retired row | C2 |
| T5 | S9, S10 | `AGENTS.md` tax-rate row (it says the uniqueness ignores `enabled` and names #888 as the open hole), the record's evidence log, `./gradlew test spotlessCheck` | C3 |

## Log

- **L1** (verbatim) «puedes revisr el issue 888» — the review that produced the four defects below.
- **L2** (verbatim) «ok modifica los puntos antes» — the issue body was rewritten, not the code: the
  RLS audit (S8), the re-enable path (S6), the representable retired pairs (S5) and the contradictory 409
  sentence all entered the approved text first.
- **L3** (verbatim) «ok crea el issue hermano» — #1030 filed for the misspelled **scope**, which retiring a
  row cannot repair because a region's authority is keyed on the existence of rows
  (`TaxRateService.java:130-132`).
- **L4** (verbatim) «apruebalos y continua» — both issues labelled `status:approved` by the maintainer;
  this unit starts on `feat/tax-rate-same-day-correction`.
- **L5** Measured while writing T1: the tree's highest migration is `V113`, so this unit's file is `V114`.
  `tax_rate` is `ENABLE` + `FORCE` row-level security (`V82`), so the S8 audit reads **zero rows** unless
  the window is open — the reason S8 exists in this shape rather than as a bare `SELECT`.
- **L6** The first run of T4's migration case failed with `expected: 2 but was: 0`, and the cause was the same
  trap S8 exists for, one layer out: the assertion read through the suite's `count(...)` helper, which opens an
  **unscoped** connection, and `FORCE` RLS answered `0` over a table that had both rows. A `0` that should be
  `2` reads as «the insert did not land», not as «this read is blind». The count now goes through
  `readScoped(company, …)`, and the comment in the case states why.
- **Evidence (2026-11-23)**: `./gradlew test --tests '*TaxRate*'` → **45 tests, 0 failures**. The module gate
  `./gradlew spotlessCheck spotbugsMain test` → **5278 tests, 0 failures, 0 errors, 0 skipped**, BUILD
  SUCCESSFUL. S9 holds by `git diff --stat`: five modified files and two new ones, no DTO, no controller, no
  route and no response field. Slices on `feat/tax-rate-same-day-correction`: **C1** `V114` alone, because a
  migration over a fiscal table is risk item 1; **C2** the two repository derivations, both guards and the
  suites that pin S1–S7, risk item 3; **C3** this record and the `AGENTS.md` correction.
- **L7 — slice size, stated rather than hidden.** Measured: C1 `192` authored lines, C2 `180`, C3 `94` —
  `466` in total, past the ~400-line review budget. The boundary is deliberate and each commit is one cohesive
  unit: C1 is the schema change, which cannot be reviewed with a behaviour change in the same diff because the
  two carry different risks; C2 is the code plus the suites that pin it, because a behaviour slice without its
  tests is not reviewable; C3 is the record and the doc row that the same change falsifies. Re-cutting C2 in
  half would separate a guard from the test that proves it — the one split this repository's budget explicitly
  avoids — so the overshoot is recorded instead.
