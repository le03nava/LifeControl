# ODD feature: typed-state-machine

**Repository**: LifeControl — `life-control-api` (migrations, `status`, `salesorder`, `purchaseorder`,
`goodsreceipt`), `life-control-app-angular` (the screens whose DTOs carry a state), `odd/tasks/`
**Branch**: `feat/typed-state-machine` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-typed-state-machine`
**Status**: implemented — this record is the delivery of accepted ADR-0004: the five domain enums with
their closed transition sets, the typed `status` column on the five document tables, `status_transition`
with its append-only pair and its two deferred constraint triggers, the writers that append through them,
and the drop of `status_id` and the two service maps. T1–T8 below are the order the ADR's `## Migration
from the current tree` prescribes; each task owns its own invariant from `## Invariants (each one
testable)` and none of them is dropped or deferred silently.
**Created**: 2026-11-03
**Risk**: items 1, 2, 3 and 6 — item 1 because the delivery backfills stored state on financial documents
and drops the column that holds today's authority, item 2 because a deferred trigger refuses commits and a
missing arm makes the process refuse to boot, item 3 because `SalesOrderResponse`, `PurchaseOrderResponse`,
`GoodsReceiptResponse` and the two status-request DTOs change shape under Angular screens that already call
them, and item 6 because the deferred triggers are the only thing that can catch a writer that forgets its
history row.

## Objective

Land accepted ADR-0004 (`docs/adr/0004-typed-state-machine.md`) as reviewable work units, in the ADR's own
migration order, keeping `main` green at every slice: each slice lands with its tests, its schema change and
its readers in the same commit, because a `CHECK` on a column nothing writes and a deferred trigger over
writers that do not append both break the tree rather than the next slice.

## Specs

Quoted from `docs/adr/0004-typed-state-machine.md` (accepted 2026-11-02) and from the request in `L1`.
Line references are to that file unless stated otherwise.

- **S1** — The scope is five document families and two exclusions, by decision: «`goods_receipts` is a
  numbered commerce document (ADR-0003) and is **in scope**; appointments and the employment status are not
  commerce documents … and they are **out of scope**, named here so the omission is a decision and not an
  oversight» (`## Context`).
- **S2** — «**The enum is the only source of truth.** `SalesOrderState`, `SalesOrderItemState`,
  `PurchaseOrderState`, `PurchaseOrderDetailState` and `GoodsReceiptState` are Java enums; the row stores
  `status VARCHAR(24) NOT NULL` with a `CHECK` listing their values — **not** a foreign key to `statuses`,
  which can be edited at runtime and says nothing to the compiler, while a `CHECK` derived from the enum
  refuses any other value.» (decision 1).
- **S3** — «**Transitions are declared once, next to the enum, as a closed set.** One guard,
  `canTransition(from, to)`, is the only place that decides, and every path calls it: the single-item edit
  (`:595-612`), the order-level put, the charge (`:400-412`, `:433`) and the cancel. A state with no entry
  has no outgoing transition, and a target outside its successor set is refused with the transition named.»
  (decision 2).
- **S4** — «**Item-level states are typed the same way.** `sales_order_items` and `purchase_order_details`
  get their own enum and their own closed set, so "an item of a charged order cannot be Cancelled" is a rule
  in one place instead of the `!"Cancelled".equals(…)` test at `:433`.» (decision 3).
- **S5** — «**Every change appends a row, and the database checks that it did.**
  `status_transition (company_id, document_type, document_id, from_status, to_status, actor, occurred_at,
  reason)` is append-only: no `UPDATE`, no `DELETE`. The state column answers "where is this document"; the
  table answers "how it got here".» (decision 4).
- **S6** — «**The tie between the two is enforced at commit, not audited at night.** A deferred constraint
  trigger on each document table refuses a commit in which a state change has no matching
  `status_transition` row, and a document created without its creation row. … a writer that forgets the row
  **cannot commit**, instead of being discovered later.» (decision 5). The reverse direction is the same
  decision's side B: «a transition appended without moving the document is refused at commit» (`## Schema`).
- **S7** — «**A transition names a document of its own tenant, and the database proves it.** … a `BEFORE
  INSERT` trigger resolves the document by `document_type` and refuses a row whose document does not exist
  under that `company_id`. Without it a wrong `company_id` is not rejected but **hidden**, because
  ADR-0005's policy filters the row instead of failing the write.» (decision 6).
- **S8** — «**The lookup survives as a presentation catalog.** `statuses`/`status_types` keep their rows,
  gain a `code` (the enum constant) **for the five families that have one** … and stop being the authority:
  a label is translation, and renaming one changes what the screen says.» (decision 7).
- **S9** — «**Drop `status_id`** and its indexes (`V1:334`, `:488`, `:514`, `V12:36`) once every reader uses
  the new column, and delete the maps at `SalesOrderService.java:66-76` and `PurchaseOrderService.java:73-81`.
  … the two out-of-scope tables — `scheduling_appointments`, `employees` — keep their `status_id` untouched,
  which is what makes the scope decision visible in the schema instead of only in prose.» (migration step 9).
- **S10** — The migration order is the ADR's, and two of its constraints are hard: «Step 7 closes the window
  and re-checks it, before step 8 arms the new table — the order matters: a window left open weakens the
  table for everything that runs after it» (step 0), and «`actor = 'unknown:migration'`,
  `reason = 'MIGRATION'`» with «The history before this migration is one initial row, not a reconstruction;
  inventing transitions would fabricate evidence.» (step 6).
- **S11** — Every invariant in `## Invariants (each one testable)` is a test in this delivery: T1 (a value
  the enum does not have is refused), T3 (append-only, one move recorded once, one creation row), T4 (state
  and latest transition agree in both directions), T5 (enum ↔ catalog, scoped to the five families), T6
  (`CHECK` ↔ enum), T7 (a transition of another tenant is refused).

## Tasks

| ID | S# | Work | Depends on | Route |
| --- | --- | --- | --- | --- |
| T1 | S2, S3, S4 | The five domain enums with their closed transition sets and `canTransition(from, to)`, the successor sets copied from `SO_TRANSITIONS`, `SO_ITEM_TRANSITIONS`, `PO_TRANSITIONS` and `DETAIL_TRANSITIONS`, plus unit tests per family (every declared edge accepted, every undeclared pair refused, no state reachable from itself) | — | inline; one work-unit commit |
| T2 | S2, S8, S10 | `V66`: the `NO FORCE` window with its two self-checks, `status VARCHAR(24)` on the five tables, the explicit `VALUES` backfill per family, the null assertion, `CHECK` + `NOT NULL`, the bridge trigger that derives `status` from `status_id` while that column lives (it dies in T8), `statuses.code` with its backfill and its assertion, the window closed and re-checked | T1 | inline |
| T3 | S11 | The two parity tests: the `CHECK` read back from `pg_constraint` compared with `Enum.values()` in both directions, and `statuses.code` joined to the enum scoped to the five families, failing on a constant without a row and on an in-scope row without a constant | T2 | inline |
| T4 | S5, S7, S11 | `V67`: `status_transition` with `seq`, its three indexes, the append-only function and trigger with the `REVOKE`/`GRANT` pair, `document_tenant_and_state`, decision 6's `BEFORE INSERT` trigger, and `apply_tenant_isolation('status_transition')` **last**, with the assertion that `unprotected_tenant_tables()` returns no row. Plus `StatusTransitionRecorder` and its tests: `UPDATE`/`DELETE` raise, a cross-tenant row is refused, the application role holds `INSERT, SELECT` only | T2 | inline |
| T5 | S5, S6, S11 | Sales order family: `V68` with the deferred pair on `sales_orders` and `sales_order_items` and the seed of one creation row per existing document **and item**; `SalesOrderService` writes through the enum and appends via the recorder on creation, the order-level put, the single-item edit, the charge and the cancel; `SalesOrderResponse`/`SalesOrderItemResponse`/`UpdateSalesOrderStatusRequest` carry the code; the Angular sales screens read it | T3, T4 | delegated writer |
| T6 | S5, S6, S11 | Purchase order family: `V69` with the deferred pair on `purchase_orders` and `purchase_order_details` and its seed; `PurchaseOrderService` on the enum with the append on creation, the status put, the derived detail promotion and the receipt-driven move; the purchase DTOs; the Angular purchases screens | T5 | delegated writer |
| T7 | S5, S6, S11 | Goods receipts: `V70` with the deferred pair on `goods_receipts` and the seed of its creation rows; the receipt write path moves to the enum and appends; `GoodsReceiptResponse` carries the code; the Angular receipt screens | T6 | delegated writer |
| T8 | S9, S11 | The drops: `V71` drops `status_id` and its four indexes on the five tables and the bridge trigger of T2; the two service maps and the remaining name comparisons are deleted; `StatusValidator`/`StatusRepository` stop serving the five in-scope families and keep serving the two out-of-scope ones | T7 | inline |

## Log

**L1 — the request, verbatim.** «si arranca con adr0004», in answer to the pending-work report whose
closing question was «¿Arranco por ADR-0004 (el que no depende de ninguna decisión) o preferís destrabar
primero alguna de esas tres?».

**L2 — the plan is the ADR's own migration order, and the slices are cut where a slice can land alone.**
The ADR numbers the delivery 0–9; a slice boundary that puts a `NOT NULL` column in one commit and its
writer two commits later leaves `main` refusing every insert in between, so the boundaries follow what keeps
the tree green instead: T2 adds the column behind a bridge trigger that derives it from `status_id` while
that column still exists (the trigger is named in `V66` as temporary and T8 deletes it), and T4 creates
`status_transition` **without** the five deferred triggers, because those triggers refuse every existing
writer until the family's own slice has moved it. The deferred pair therefore lands per family in T5, T6 and
T7 — the same order as the ADR, with the constraint arriving with its writers rather than before them.

**L3 — the head is `V65`, and it is a Java migration, which is why the first SQL file of this delivery is
`V66`.** `life-control-api/src/main/java/db/migration/V65__Document_identity_render.java` is ADR-0003's
renderer; `MigrationHeadTest` pins that a Java migration counts, so `V66` is the next free number and the
schema suites' head assertion follows it.

**L4 — the five document tables are already armed except nothing else is: `sales_orders`,
`goods_receipts` and `purchase_orders` carry `company_id` and a `tenant_table` row after ADR-0003, so
`V66`'s `NO FORCE` window names exactly those three and T4's arm of `status_transition` is the only new
policy.** Measured in `V64__document_identity_per_tenant.sql:47-48` (open) and `:265-266` (close) — the
window pattern this delivery copies.
