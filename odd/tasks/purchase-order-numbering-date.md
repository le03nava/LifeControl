# ODD feature: purchase-order-numbering-date

**Repository**: LifeControl — `life-control-api`: `purchaseorder/service/PurchaseOrderService`, `purchaseorder/model/PurchaseOrder`, and the two suites that pin them. Issue #772.
**Status**: **implemented** — a purchase order's number is allocated in the period of the instant its own row records: the service reads the clock once and the entity keeps that instant across persist, while an entity that froze nothing is still stamped by `Auditable`. Nothing is deferred; the two ideas the issue puts out of scope stay out of scope.
**Created**: 2026-11-04 · **Risk**: item **6** — the second half of the fix lives in a JPA lifecycle callback, and no assertion that does not write a row can see whether it runs; it is pinned by an integration case that reloads the row, plus the case that freezes nothing. Item 3 was considered and does not apply: readers of `created_at` now see the numbering instant instead of the flush time, which is the requested behavior, and no response shape changed.

## Specs

S1 — **"the period of the number and the `created_at` the row records come from one instant."** `PurchaseOrderService.createPurchaseOrder` reads the clock once, and that single value produces both the period (`DocumentNumberRenderer.periodOf(numberedAt.toLocalDate())`) and the order's audit columns.

S2 — **"an order numbered at `2026-10-31T23:59:59.999` carries `seq_period = 202610` and `created_at` in October, so the two cannot disagree."** Persisting an order that carries that instant keeps it, so the period of the date the row records is the period the number was allocated in.

S3 — **"`Auditable` keeps its behavior for every entity that does not set its own creation instant: it is still stamped at persist."**

S4 — the two non-goals, verbatim: **"Adding `purchase_orders.order_date`"** and **"Changing `Auditable`"** — the second because `AccessProvisioningTaskService:277` sets `createdAt` while `onCreate()` overwrites it, so making the base class preserve a provided value would change a path nobody asked about.

## Tasks

| # | Task | S# | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | Issue #772 measured from the tree and approved by the maintainer; worktree `fix/purchase-order-numbering-date` through `new-unit.sh`; this record | S1-S4 | done — `status:approved` applied over `bug` by direct instruction, and read back | this record's commit |
| T2 | RED for the service half: a case that captures the entity the service hands the repository and asserts the period is the period of its own `created_at` | S1 | done — `AssertionError: [the order carries the instant its number was allocated in, not a later reading at flush] Expecting actual not to be null`, 73 tests / 1 failed | this record's commit |
| T3 | RED for the persistence half: `PurchaseOrderNumberingIntegrationTest` over real PostgreSQL, with the `V69` creation row a document owes | S2, S3 | done — `expected: 2026-10-31T23:59:59.999 but was: 2026-11-04T07:07:10.665907`: `seq_period` 202610 on a row stamped at flush. The freeze-nothing case passed before the fix, as designed | this record's commit |
| T4 | The fix: one clock read in the service, the same instant through the existing builder, and a `PurchaseOrder` override that calls `super.onCreate()` and restores the frozen instant | S1-S3 | done — `Auditable` untouched, no second `@PrePersist`, rendering/allocation/events/status recording unchanged | this record's commit |
| T5 | Checks: the two new suites, every `PurchaseOrder` suite, then the full API gate | S1-S3 | done — 75 tests focused, 142 in the `PurchaseOrder` suites, then `cleanTest test spotlessCheck spotbugsMain` full | this record's commit |

## Log

L1 — Request that opened the work, verbatim, after the previous session's report named this debt: *"si abrelo y apruebalo y has la correccion"*.

L2 — 2026-11-04, cause measured before any edit. `PurchaseOrderService:196` froze the period from `LocalDate.now()`; `Auditable:19-23` (`@PrePersist onCreate()`) stamped `created_at` from a second `LocalDateTime.now()` at flush; and `purchase_orders` has no `order_date` (`V1__baseline_schema.sql:317-328` declares `created_at`/`updated_at` only), so `created_at` is the document's only recorded date and ADR-0003 decision 4's one-date rule has to hold for it. Two readings of one clock is how a number for October lands on a row dated November.

L3 — 2026-11-04, RED observed on the untouched implementation, both halves: the service case failed on `getCreatedAt()` being null (the entity carried no instant at all), and the integration case failed with `expected: 2026-10-31T23:59:59.999 but was: 2026-11-04T07:07:10.665907` — `seq_period` 202610 while the row's own date had moved on. The companion case that freezes nothing passed before and after, which is what makes it a regression pin rather than a copy of the fix.

L4 — 2026-11-04, design decision. The instant is frozen in the service and carried through the builder the entity already had; `PurchaseOrder` **overrides** the inherited lifecycle callback instead of adding a second `@PrePersist`, because the override replaces it (one body runs, `super.onCreate()` is called explicitly) while a second callback would hand the outcome to hierarchy ordering. Overriding without calling `super.onCreate()` is the failure mode `GoodsReceiptIntegrationTest:1012` records — both audit columns null and their `NOT NULL` constraints refusing the insert. `Auditable` was not touched, for S4's reason.

L5 — 2026-11-04, GREEN and checks. Focused: 75 tests, 0 failures (`PurchaseOrderServiceTest` + `PurchaseOrderNumberingIntegrationTest`). Suites: 142 tests across 45 classes in the `PurchaseOrder` selection, 0 failures. Full gate `./gradlew cleanTest test spotlessCheck spotbugsMain`: 4500 tests in 1067 classes, 0 failures / 0 errors / 0 skipped, spotless and spotbugs green. One formatting pass was needed on the new integration test, applied with `spotlessApply` and re-run; no assertion changed.
