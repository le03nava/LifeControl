# ODD feature: purchase-order-detail-endpoints

**Repository**: LifeControl — `life-control-api` (`purchaseorder/service`, `purchaseorder/controller`, `common/state`), `odd/tasks/`
**Branch**: `feat/purchase-order-detail-endpoints`
**Status**: implemented — the unit is the three detail sub-resource routes becoming usable by a client that has no state catalog at hand, and closing the ownership hole in two of them: `POST /api/purchase-orders/{id}/details` defaults an omitted `statusId` to `PENDING` exactly as `POST /api/purchase-orders` defaults an omitted `statusId` to `DRAFT`, `PUT` keeps the line's current status when the payload omits it, and `PUT`/`DELETE` answer 404 for a `detailId` that is not a line of `{id}`. T4 pins the Draft guard's 409, which no test asserted before. No migration and no response shape change: `PurchaseOrderDetailResponse` is untouched. Nothing is deferred.
**Created**: 2026-11-09 · **Risk**: item 3 — two routes change observable behavior under clients that already call them: a payload that answers **400** today starts answering **201**/**200**, and a request that answers **200**/**204** today starts answering **404**. The new behavior is the one the sibling routes already have and the one the new purchase-order flow depends on, so the change is the unit rather than a side effect, but it is a contract change on a public API and it lands before the client that needs it.

## Problem

The purchase-order detail sub-resource exists, is Draft-guarded, and is not usable by a client that does not carry the document-state catalog in hand — and two of its three routes do not check that the line belongs to the order in the path.

| Fact | Evidence |
| --- | --- |
| `POST /{id}/details` has no `statusId` fallback | `life-control-api/src/main/java/com/lifecontrol/api/purchaseorder/service/PurchaseOrderService.java:445` — `var detailState = detailStateOf(request.statusId());`, with no `?:` arm. Contrast the order itself at `:194`: `request.statusId() != null ? orderStateOf(request.statusId()) : INITIAL_ORDER_STATE` |
| An absent state id is a 400, not a default | `common/state/DocumentStateCatalog.java:65-67` — `throw new IllegalArgumentException("A status id is required to name a state of …")`, mapped at `exception/GlobalExceptionHandler.java:109-112` |
| `PUT` recomputes the state from the payload and compares it | `PurchaseOrderService.java:491` — `var detailState = detailStateOf(request.statusId());` then `if (detailState != detail.getStatus()) { DocumentStates.requireTransition(...) }`, so an omitted id does not mean "leave it alone" |
| `PUT` resolves the line by id alone | `PurchaseOrderService.java:478` — `detailRepository.findById(detailId).orElseThrow(() -> new PurchaseOrderDetailNotFoundException(detailId));`, with no comparison against `purchaseOrderId` |
| `DELETE` has the same gap | `PurchaseOrderService.java:516` — the same `findById(detailId)` and nothing else |
| The Draft guard is real and untested at the HTTP boundary | `PurchaseOrderService.java:690-701` `loadAndValidateDraftPO`, called at `:442`, `:477`, `:514`; `InvalidStatusTransitionException` extends `ConflictException`, mapped to **409** at `GlobalExceptionHandler.java:68-71`. No test in `purchaseorder/` asserts that 409 |
| The client methods already exist and are typed against the wrong shape | `life-control-app-angular/.../data/purchase-order.service.ts` declares `addDetail`/`updateDetail`/`deleteDetail` as `Observable<PurchaseOrder>` while the API answers `PurchaseOrderDetailResponse` (201/200) and an empty **204** |

The ownership gap is what makes this a prerequisite rather than a convenience: a flow that persists every line against these routes hands the client a `detailId` per row, and today any `detailId` of any order of the same tenant is accepted by the path segment that was supposed to scope it.

## Specs

**S1** — `POST /api/purchase-orders/{id}/details` with `statusId` omitted creates the line in `PENDING` and answers **201** with the created detail. `PENDING` is `PurchaseOrderService.INITIAL_DETAIL_STATE`, the constant the whole-order create path already uses for a line it receives without a state, so the two paths name the same state for the same absence.

**S2** — `PUT /api/purchase-orders/{id}/details/{detailId}` with `statusId` omitted keeps the line's current status and answers **200** with the updated detail. Omission means "do not touch the state", never "move it to null": no transition is evaluated and no `requireTransition` runs. When `statusId` **is** sent and differs from the stored state, the existing `DocumentStates.requireTransition` check is unchanged.

**S3** — `PUT` and `DELETE` answer **404** when `detailId` is not an enabled line of the order named by `{id}`, and write nothing. The lookup is scoped by both ids in one query; the path segment is a filter, not a decoration.

**S4** — The three detail routes answer **409** with the message `Invalid status transition: <STATE> → detail mutation` when the order is not `DRAFT`, and persist nothing. The behavior exists; S4 is the test that pins it at the HTTP boundary, because a guard no test reaches is a guard that a refactor deletes silently.

**S5** — No migration, no schema change, and no change to `PurchaseOrderDetailResponse`, `PurchaseOrderDetailRequest` or the `lineTotal` computation. `PurchaseOrderDetailRequest.statusId` stays optional in the record; this unit only gives its absence a defined meaning.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S1, S2 | `PurchaseOrderService.addPurchaseOrderDetail` and `updatePurchaseOrderDetail`: default an omitted `statusId` to `INITIAL_DETAIL_STATE` on add and to the stored state on update | — | inline | — |
| T2 | S3 | `PurchaseOrderDetailRepository`: a finder scoped by `purchaseOrderId` + `detailId` + `enabled`, and its use in the `PUT` and `DELETE` paths in place of the bare `findById` | — | inline | — |
| T3 | S1, S2, S3 | `PurchaseOrderServiceTest`: the omitted-`statusId` cases for add and update, the transition still enforced when `statusId` is sent and differs, and the foreign-`detailId` 404 for both routes asserting nothing was written | T1, T2 | inline | — |
| T4 | S4 | `PurchaseOrderDetailVariantIntegrationTest` (or `PurchaseOrderControllerTest`): the 409 for `POST`, `PUT` and `DELETE` on a non-Draft order, asserting the stored lines are unchanged | T2 | inline | — |
| T5 | S5 | Verification: the `purchaseorder` slice of `./gradlew test` | T3, T4 | inline | — |

**Total task count: 5.**

## Decisions

**D1 (2026-11-09) — the default lives in the service, not in the client.** The alternative was to have the Angular form fetch the detail-state catalog and send `PENDING` explicitly. That couples every client to a UUID it has no business knowing, and it leaves the endpoint unusable from any other caller — including the integration tests that already build payloads by hand. The whole-order route already decided this question the other way (`INITIAL_ORDER_STATE` at `:194`), and two sibling routes disagreeing about what an absent `statusId` means is the defect, not the fix.

**D2 (2026-11-09) — omission on `PUT` means "keep", not "reset".** `PUT` is a full replacement of the mutable line fields, but the state is not one of the fields the add-row form owns. Treating an omitted `statusId` as `null` would force every quantity edit to also assert a state, and would make `requireTransition` evaluate a transition nobody requested.

**D3 (2026-11-09) — the ownership check is a scoped finder, not a post-hoc comparison.** A `findById` followed by `if (!detail.getPurchaseOrder().getId().equals(id))` reads the row of another order before rejecting it and can be forgotten in the next route added. One query that names both ids cannot be satisfied by the wrong order.

## Log

**L1 (2026-11-09) — the request that opened this unit.** «puedes revisar /purchases/orders/c1206910-3422-48ea-add6-61d4d620abd7 cuando intento agregar un producto no encuentra nada y no veo que se haga alguna peticion al backend puedes revisar»

**L2 (2026-11-09) — the design decision that made this unit a prerequisite.** «si arrancamos con b ahora, no tendria que cambiar de que hagamos esto? mejor analicemos la funcionalidad completa y primero hagamos mi propuesta y en la pantalla no aparezca el area de lineas de detalle hasta que se cree la orden de compra y cuando se cree te deje en la misma pantalla y aparezcan las lineas de detalle para agregar productos, lo que si dejaria fuera es como depurar las ordenes que se queden en borrador y no se utilicen, puedes generar el odd o los odds para esta funcionalidad» — with the answer to the persistence question, chosen by the operator: per-line persistence against the detail sub-resource rather than one batch `PUT`. That choice is what turns this unit from optional into required, because those routes were never exercised from the UI.

**L3 (2026-11-09) — evidence gathered for this record.** `POST`'s missing state fallback (`PurchaseOrderService.java:445` vs `:194`), the absent ownership check in `PUT`/`DELETE` (`:478`, `:516`), the 409 mapping (`GlobalExceptionHandler.java:68-71`), and the absence of any 409 assertion for the detail guard across `purchaseorder/` tests.

**L4 (2026-11-09) — this gap was already named, and left open.** The SDD verification report for `purchase-order-backend` (Engram observation #1216, 2026-06-03) recorded it as a warning and as a suggestion, in these words: «`PurchaseOrderDetailRequest` has extra `statusId` field — The service expects it, requiring the client to know the detail status UUID» and «Consider auto-assigning the initial "Pending" detail status server-side rather than requiring it from the client». The unit was archived with the warning non-blocking. D1 is that suggestion, made executable — which is why it is a default in the service rather than a field the client fills.

**L5 (2026-11-10) — the record's own claim was false until this unit shipped, and this is the measurement that said so.** The record opened `**Status**: implemented` while nothing of it existed in `main`: the highest migration was `V91`, `PurchaseOrderService` still resolved a line's state with a bare `detailStateOf(request.statusId())`, `PUT`/`DELETE` still looked the line up with a bare `findById`, and the Angular tree held `purchase-order-list` and `purchase-order-edit` with no create step. This record and `purchase-order-create-then-lines.md` opened that way because their PRs carried only `odd/tasks/*.md`. A header travels in the PR that ships its code, so this unit is the one that makes this header true instead of rewriting it, and the Branch line above now names this unit's branch rather than its successor's.

**L6 (2026-11-10) — the RED that pins S1 and S2, observed before the fix.** With only the two coalescing expressions reverted and the rest of the change in place, the slice answered `IllegalArgumentException: A status id is required to name a state of PURCHASE_ORDER_DETAIL` in both new cases — the exact failure the unit exists to remove — and every other case in the slice passed, so the two failures were the rule and not the harness. S3's own RED is a compile error by construction: the scoped finder it names did not exist, so no case could have exercised the old lookup through it.

**L7 (2026-11-10) — the GREEN, and the delivery evidence.** `life-control-api/gradlew test --tests 'com.lifecontrol.api.purchaseorder.*'` → **151 tests, 0 failures, 0 errors, 0 skipped**, `BUILD SUCCESSFUL`, `PurchaseOrderDetailVariantIntegrationTest` and the money migration suites included. Source and tests in `0e5e1bdd` on `feat/purchase-order-detail-endpoints`. The two Angular methods typed against the wrong shape (`data/purchase-order.service.ts`, named in the Problem table) stay with U2, which is the unit that consumes them.

**L8 (2026-11-10) — the independent verification, and the five cases it added.** A verifier ran the slice itself (151 tests, 0 failures) and three scratch reversions to learn whether each rule is actually pinned: reverting `:486` failed exactly the omitted-`statusId` create, reverting `:538` failed exactly the keep-on-omission case, and reverting both lookups to `findById` failed eleven cases. Verdict: S1-S5 satisfied, nothing merge-blocking, and three advisories — nothing exercised the `enabled` half of S3, nothing hit the omitted-`statusId` create at the wire, and S4's message was asserted only below the boundary. This commit answers them where they live, against a real database and through the routes a client calls, because the scoped query and its `enabled` clause are not observable through a mock: a create that omits the id answers 201 and stores `PENDING`; an edit that omits it keeps a stored `CANCELLED`; a foreign `detailId` answers 404 to `PUT` and `DELETE` with the owner's line untouched; a soft-deleted line answers 404 to both; and the three writes on a non-Draft order answer 409 carrying `Invalid status transition: SENT → detail mutation` with the line unchanged. The three controller cases now assert that message too. Slice after it: **45 suites, 156 tests, 0 failures**.

**L9 (2026-11-10) — the two edges S3 changes that the risk line does not spell out.** Both are authorized by S3 rather than by accident, and both are now pinned: `DELETE` of a line that is already soft-deleted answers **404** where it answered 204 before, and `PUT` of that line answers **404** where it answered 200 — the lookup is now "an enabled line of this order", and a disabled line is not one. Nothing in this repository called either edge: the only production callers of the three routes are the Angular data service, which never re-sends a deleted line, and the integration tests.
