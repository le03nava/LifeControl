# sales-order-cross-store-authz

**Status**: implemented — every `life-control-api` sales-order path that addresses an existing order by id authorizes the
caller for **that order's store**, and `GET /api/sales-orders` narrows to the stores the caller is scoped to, which is the
rule `GoodsReceiptService` already applies to receipts. Issue #889. Branch `fix/sales-order-cross-store-authz`, base
`origin/main` @ `4d08e7d2`. Nothing is deferred: the guard, the list narrowing and the suites land together.
**Created**: 2026-11-18 · **Risk**: item 2 applies — this unit *is* an authorization boundary, and the failure it closes is a
store-scoped cashier charging, voiding, deleting and reading another store's orders. Item 3 does not: no endpoint, path,
payload or status code changes for a caller that was already allowed, and the only new answer is a 403 that today is a 200.
Item 1 does not: the guard runs **before** the first write, so a refused call leaves the row, its lines and its payments
exactly as they were, which S1–S3 assert as an effect and not as an intention. Item 6 does not: every rule below has a test
that fails on the previous tree.

## Problem

`lc-sales` is a store-scoped role, but `SalesOrderService` enforces that scope only where an order is built from a request:
`createSalesOrder` and `updateSalesOrder` call `validateCompanyStoreExists`, which derives the
company → country → region → zone → store chain and hands it to `CurrentUserContext#verifyCompanyStoreAccess`. Every path
that loads an **existing** order by id skips it, and the flat list does not narrow at all. The only thing behind those
paths is company-level PostgreSQL RLS, which is not the same guarantee: `GoodsReceiptService` says so and verifies the
store on create *and* on the id-addressed read, and narrows its list through `CompanyStoreScopeResolver`.

## Specs

**S1** — «A store-A-scoped `lc-sales` caller requesting `GET`, `PUT` or `DELETE` on a store-B order gets 403, and the row
is byte-identical afterwards.»

**S2** — «`POST /api/sales-orders/{id}/charge` on a store-B order gets 403, no `sale_payment` row is written and the order
total does not move.»

**S3** — «A status change or an enable/disable on a store-B order gets 403 and the stored status stays as it was.»

**S4** — «`GET /api/sales-orders` returns zero store-B orders for the store-A caller, while a company-scoped caller still
sees both.»

**S5** — «A store-B-scoped caller can still perform all of those operations on a store-B order, so the change does not close
the legitimate path.»

**S6** — «`./gradlew test` in `life-control-api` passes, including the existing sales-order suite.»

**S7** — «Every sales-order path that addresses an existing order by id answers **403** when the order's store is outside
the caller's store scope» — the issue's expected outcome, which is broader than S1–S3: the order's **line** paths
(`POST/PUT/DELETE /api/sales-orders/{id}/items…`) address the same order by id, and they move money, so they carry the
same guard.

## Tasks

| ID | Task | Specs | Route | Commit |
|----|------|-------|-------|--------|
| T1 | Guard every id-addressed sales-order path against the order's store, immediately after the row is loaded and before any write | S1, S2, S3, S5, S7 | inline | — |
| T2 | Narrow `getAllSalesOrders` through `CompanyStoreScopeResolver.StoreScope` | S4 | inline | — |
| T3 | Suites: the refusals, the "row is byte-identical" effects, the still-legitimate store-B path, and the list scoping | S1–S5, S7 | inline | — |
| T4 | Module gate: `./gradlew test spotlessCheck spotbugsMain` in `life-control-api` | S6 | inline | — |

## Decisions

**D1 — the id-addressed guard reuses `validateCompanyStoreExists`.** `SalesOrder` maps `company_store_id` as a
plain column (`SalesOrder.java:58-59`), not an association, so the company → country → region → zone → store chain the
guard needs cannot be read from the order. `validateCompanyStoreExists(id)` already does exactly the two steps an
id-addressed path needs — resolve the store row, then apply `verifyStoreAccess` — so the guard on an existing order is
that same call. The cost is one extra `SELECT` per id-addressed request, which is the price of the chain being read
from the store instead of duplicated on the order.

**D2 — the guard runs before every other write in the method, not merely before the response.** `chargeSalesOrder`
auto-promotes an `Active` order to `Pending` (`ensureOrderIsPending`) and `deleteSalesOrder` reverses stock; both are
writes that happen before the money moves. S2 and S3 assert the stored state did not move, so the guard's position is
part of the contract and not an implementation detail.

## Evidence

All runs in `life-control-api` of this worktree, `./gradlew … --no-daemon`.

| Run | Result |
|-----|--------|
| RED (T1) — `test --tests …service.SalesOrderServiceTest` | the 10 new cases in `StoreScopeOnExistingOrdersTests` failed (7 on `AccessDeniedException` not thrown, 3 because the call completed), and every pre-existing nested class stayed green |
| GREEN (T1) — same command | `BUILD SUCCESSFUL`; the 10 cases pass, and so do the pre-existing ones once the class fixture answers the store row the guard resolves |
| RED (T2) — same command | the 3 new `GetAllSalesOrdersTests` cases failed for the reason each states: the store-A caller received BOTH orders, the search case never reached the scoped finder, and the empty scope touched the repository |
| GREEN (T2) — same command | `BUILD SUCCESSFUL` |

## Log

**L1** (user, 2026-11-18, verbatim): «ok dale a 889 y apruebalo»

**L2** (issue #889, the requirements this unit answers, verbatim): «### Problem
`lc-sales` is a store-scoped role (`common/security/ScopeLevel.java:79-85`), and `SalesOrderService` enforces that scope
only on the two paths that build an order from scratch: `createSalesOrder` (`SalesOrderService.java:191`) and
`updateSalesOrder` (`:286`) call `validateCompanyStoreExists`, which delegates to `verifyStoreAccess` (`:948-970`).
Every path that loads an **existing** order by id does not: `chargeSalesOrder` (`:405`), `findByIdForUpdate` (`:413`),
`updateSalesOrderStatus` (`:628`), `deleteSalesOrder` (`:658`), `enableSalesOrder` (`:688`), and the read-by-id in
`getSalesOrderById` (`:175-178`). `getAllSalesOrders` (`:161-172`) returns the company's whole order list to a caller
whose role is scoped to one store.»

**L3** (evidence, 2026-11-18): the strict-stub consequence of D1, measured. The guard resolves the store row, so the
class fixture had to answer `companyStoreRepository.findById(companyStoreId)` for every existing case that addresses an
order by id — `lenient`, because the create-only cases never ask for it. `updateSalesOrder_StoreOutsideCallerScope_Throws403`
also had to make its customer stub lenient: the guard now answers before the customer is validated, and that is exactly
what that case asserts.
