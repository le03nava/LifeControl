# ODD feature: v64-vacuous-self-check

**Repository**: LifeControl — `life-control-api` (migrations, `document`), `odd/tasks/`
**Branch**: `fix/v64-vacuous-self-check` · **Worktree**: `~/workspace/LifeControl-worktrees/fix-v64-vacuous-self-check`
**Status**: implemented — `V72` re-asks, inside its own `NO FORCE` window and before it re-arms `FORCE`, the
two questions `V64` answers too late to fire, and a scratch-database test proves each one aborts the
migration naming its table and reason. `V64` is not edited, which is the shape the issue's constraint
allows without a maintainer decision on the Flyway checksum; the in-place edit stays undecided and is not
needed for coverage.
**Created**: 2026-11-03 · **Risk**: item 2 — the fix opens a `NO FORCE ROW LEVEL SECURITY` window on three
armed tenant tables and must re-arm them, so a mistake leaves a tenant table whose owner is exempt from its
own policy; item 6 does not apply because a test reproduces the abort, and no stored data changes because
the file writes none.
**Issue**: #756 (`bug`, `status:approved`)

## Objective

Make the guard fire. `V64` re-arms `FORCE ROW LEVEL SECURITY` at `:265-266` and asks its row question at
`:268-304`, so the predicate of the forced policy — `company_id = nullif(current_setting('app.company_id',
true), '')::uuid`, with that setting unset in a migration, hence `NULL` — empties the question and the
`RAISE` cannot fire. The file's own step 0 states the consequence: "the backfill reached every row" and "the
backfill reached no row" print the same green.

## Specs

Quoted from issue #756, verbatim.

- **S1** — "`V64`'s row question runs inside its `NO FORCE` window, before the `FORCE` ALTERs of step 7, so
  that a backfill which reached no row (or left a row NULL) aborts the migration instead of reporting
  success."
- **S2** — "The constraint the fix must respect: `V64` is a merged migration. Re-editing it changes what a
  database that already ran it holds, so the shape has to be decided rather than assumed — a checksum-breaking
  edit needs the maintainer's explicit decision, and the alternative is a follow-up migration that re-asks the
  same question inside its own window against the current data."
- **S3** — "A test proves the guard can fire: with the window left closed, or with a row deliberately left
  without its parts, the migration (or its follow-up) aborts and names the table and the reason."
- **S4** — "A migrated database still passes the same assertions the delivery claims (no document row without
  a company, a sequence value, or its typed status)."
- **S5** — "`./gradlew test --tests '*MigrationIntegrationTest' --tests '*SchemaMigrationIntegrationTest'` is
  green."

## Tasks

| ID | S# | Work | Route |
| --- | --- | --- | --- |
| T1 | S1, S2 | `V72`: the `NO FORCE` window on `sales_orders`, `purchase_orders` and `document_sequence`, its own "the window took" check, the two questions with their `RAISE EXCEPTION` naming table and reason — a `sales_orders.seq_period` that is not the period its own `order_date` states, and a `document_sequence.next_value` that is not ahead of every value its bucket's documents hold — the `FORCE` re-arm and the "the window closed" check. `V64` untouched | inline; done (this commit) |
| T2 | S3, S4 | The three cases in `DocumentIdentityMigrationIntegrationTest`: a seeded history migrates to head green and leaves the three tables armed and `unprotected_tenant_tables()` empty; and two deliberate corruptions at `V71` — a `seq_period` that disagrees with its own `order_date`, and a counter not ahead of its own documents — each aborts naming the table and the reason | inline; done (this commit) |
| T3 | S5 | `./gradlew test --tests '*MigrationIntegrationTest' --tests '*SchemaMigrationIntegrationTest'` green, plus the PR for #756 with `type:bug` | inline; done (this commit) |

## Log

**L1 — the request, verbatim.** «ok dale al 756», after the pending-work report that listed issue #756 as
the one approved unit that waits on nobody.

**L2 — why the follow-up, and why the checksum question stays unconsumed.** S2 offers two shapes and reserves
the first to the maintainer. The follow-up turns out to cover **both** databases a fix has to cover, which is
what made the choice cheap rather than merely cautious: Flyway applies `V1`→head, so a fresh database runs
`V64` and then `V72` in the same run, against the data `V64` just produced. The in-place edit would only
change *which* file asks the question, never *whether* it is asked — so it is not needed for coverage and is
left undecided instead of being smuggled in under a bug fix. `V66`'s header had already recorded the defect
against `V64` and said it "belongs to `V64`'s own follow-up"; this is that follow-up.

**L3 — the first question S1 names cannot be re-asked, and that is measured, not assumed.** S1 says the guard
must catch "a row NULL". From `V64` step 5 on, `sales_orders.company_id`/`seq_period`/`seq_value`,
`purchase_orders.seq_period`/`seq_value` and `goods_receipts.company_id` are all `NOT NULL`, so the state S1
wants to refuse is one the schema already refuses and no `WHERE … IS NULL` can match. Re-asking it in a
follow-up would have produced a second vacuous assertion wearing the first one's clothes. What `V72` asks
instead is what is still askable and was never enforced by anything: **the identity agrees with the document
it numbers** (decision 4: the period comes from the document's own date) and **every document's bucket has its
counter row, and the counter is ahead of the documents it numbers** (the seed of `V64` step 4's whole point,
and invariant N1's precondition). Neither is a constraint anywhere in the tree, and both can fire.

**L4 — `goods_receipts` needs no question, because `V64` step 6 already made it a foreign key.** The obvious
third question — a receipt whose `company_id` is not its order's — cannot fire: `fk_goods_receipts_order_same_company`
(`V64` step 6) refuses that row at write time. Asserting it would have been the same decoration this unit
exists to remove, so `V72` does not read `goods_receipts` at all and the window does not open on it.

**L5 — a finding the questions must not assert, and a defect recorded rather than fixed.**
`PurchaseOrderService:195` derives the period from `LocalDate.now()`, not from the document's own date, so a
purchase order created at a month boundary can carry a `seq_period` that disagrees with its own `created_at`
on legitimate data — which is the failure decision 4 exists to prevent, arriving through the writer instead of
the migration. `V72` therefore asserts the agreement for `sales_orders` only (its `order_date` is the request's
own value, a `TIMESTAMP`, so `to_char(order_date, 'YYYYMM')` equals the `periodOf(orderDate.toLocalDate())`
the service computes, with no clock involved), and the purchase side keeps the counter questions. Fixing the
writer is out of this unit's scope: it changes the numbering path of an existing document type, and this unit
is a migration plus a test. **Open for the maintainer: `PurchaseOrderService` numbers from the wall clock.**

**L6 — why the window and not a per-tenant scope.** The policy's predicate is `company_id =
nullif(current_setting('app.company_id', true), '')::uuid`, so setting `app.company_id` per company would make
rows visible without touching `FORCE` — and it was rejected: it depends on every armed table's predicate having
that exact shape, and it silently inspects only the companies a loop can enumerate, so a document naming a
company that no longer exists stays invisible in the check that exists to see it. The `NO FORCE` window is
what `V57:85`, `V64` and `V66` already use, it is transactional (a crash rolls it back), and it is closed by an
assertion rather than by good intentions.

**L7 — verified.** `./gradlew test --tests '*DocumentIdentityMigrationIntegrationTest'` green, **12 tests, 0
failures** (the 9 pre-existing cases plus the 3 new ones that remained after L8 dropped the fourth), and S5's
command — `./gradlew test --tests '*MigrationIntegrationTest' --tests '*SchemaMigrationIntegrationTest'` —
green, **233 tests across 30 result files, 0 failures, 0 errors** in `build/test-results/test/*.xml`, with
`DocumentIdentityMigrationIntegrationTest` (12), `DocumentStateSchemaMigrationIntegrationTest` (10),
`SalesOrderStatusHistoryMigrationIntegrationTest` (5), `PurchaseOrderStatusHistoryMigrationIntegrationTest` (5)
and `GoodsReceiptStatusHistoryMigrationIntegrationTest` (6) all clean. `./gradlew spotlessApply spotlessCheck`
green (that is the formatter CI runs).

**L8 — the third question was measured out, not argued out.** The first draft of `V72` asked three questions,
the third being that every document's `(company_id, doc_type, seq_period)` bucket have its `document_sequence`
row — the honest reading of "every bucket has its counter", and the question L3 had recorded as live. The full
migration suite then failed **four** schemas with exactly that question's message:
`DocumentStateSchemaMigrationIntegrationTest`, `SalesOrderStatusHistoryMigrationIntegrationTest`,
`PurchaseOrderStatusHistoryMigrationIntegrationTest` and `GoodsReceiptStatusHistoryMigrationIntegrationTest`.
The cause is the fixture convention, not the schema: those suites migrate to a pre-head, seed documents in the
old shape, and then write further documents directly at a post-`V64` head with an explicit
`seq_period`/`seq_value` and **no** counter row (`SalesOrderStatusHistoryMigrationIntegrationTest:322` and
`:337`). "Every document bucket has a counter" is therefore not a property this tree holds, and a guard that
refuses a shape the tree itself writes is a fixture fight rather than a fix — so the question was removed
rather than the fixtures rewritten, and the file now reads as two questions with one `UNION ALL` between them.
`V64`'s intent stays covered: a row without its parts cannot exist (`NOT NULL` since `V64` step 5), the period
agreement catches parts that came from the wrong place, and the counter-ahead question catches a seed that
under-counted. The `purchase_orders` and `goods_receipts` omissions (L4, L5) are untouched by this and stay
true; the `RAISE EXCEPTION` still names the relation and the reason of whichever question fired.
