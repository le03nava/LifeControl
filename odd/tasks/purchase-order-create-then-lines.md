# ODD feature: purchase-order-create-then-lines

**Repository**: LifeControl — `life-control-app-angular` (`features/purchases/purchase-orders`), `odd/tasks/`
**Branch**: the chain `feat/purchase-order-create-then-lines-01-service-and-types` → `-02-two-step-and-per-line` → `-03-search-lock-specs`
**Status**: deferred — the unit is the purchase-order screen becoming a two-step surface: `/purchases/orders/create` renders the header only and creates the order, the same screen then becomes `/purchases/orders/{id}` without leaving it and the detail area appears, every line persists on its own against the detail sub-resource, the product catalogue is searched on the server through the `?search=` parameter that already exists on the endpoint, and the supplier and the store lock as soon as the order holds a line. T12 records that purging abandoned drafts is out of scope by the operator's decision and T14 that paginating the catalogue endpoint is deferred until a short term matches enough rows to need it; those two are the only things this unit does not carry. The `implemented` this paragraph opened with on 2026-11-09 was false and is corrected here: no line of the unit existed in `main` until slice 01 landed T1, which is why the token now states what the record owes and why `L9` names each slice as it lands and flips it to `implemented` when the last one does.
**Created**: 2026-11-09 · **Risk**: none — no stored data is rewritten and no contract changes: the flow uses `POST /api/purchase-orders` with no `details` (already legal, `PurchaseOrderService.java:232-235`), the three detail routes the API already exposes, and the `search` parameter `ProductSupplierRepository.findByCompanyIdAndSupplierIdWithSearch` already reads. The one consumed shape that moves is the Angular service's own return type for `addDetail`/`updateDetail`/`deleteDetail`, which today lies about what the API answers and no caller reads.

## Problem

The create screen and the edit screen are one component, so the detail area exists before the order does; and the product catalogue is fetched once, keyed on the supplier alone while the company arrives asynchronously, so on an existing order it stays empty for the life of the page. Separately, changing the supplier or the store after lines exist leaves the order inconsistent — with no check on the server for supplier↔product and a 404 at save time for a variant the new store does not stock.

| Fact | Evidence |
| --- | --- |
| The detail area renders in create mode too | `life-control-app-angular/src/features/purchases/purchase-orders/pages/purchase-order-edit/purchase-order-edit.html:62-67` — `<app-detail-table>` sits outside any `isEditMode()` guard |
| The catalogue request is keyed on the supplier only | `purchase-order-edit.ts:203-215` — `switchMap((supplierId) => { const companyId = this.orderCompanyId(); if (!supplierId \|\| !companyId) { …EMPTY } }`, with the company read at request time from a channel that resolves later |
| The only emission in edit mode precedes the company | `purchase-order-edit.ts:315` — `this.supplierId$.next(order.supplierId);` inside `populateForm`, called synchronously from `loadOrder`'s `next` (`:291-296`); `orderCompanyId` is fed only by `onCompanyResolved` (`:280-282`) from the cascade |
| The list is filtered in memory | `components/detail-table/detail-table.ts:149-157` — `filteredProducts` is a `computed` over `availableProducts()`; typing never issues a request |
| A supplier change leaves the lines untouched | the only writers of `lineItems` are `purchase-order-edit.ts:332` (`populateLineItems`) and `:344` (`onItemsChanged`); no effect or subscription on the supplier or the store clears them |
| A store change resets only the add-row form | `detail-table.ts:198-206` — `this.newVariantId.set(null); this.selectedVariant.set(null);`, never `items()` |
| No server check pairs a line's product with the chosen supplier | `PurchaseOrderService.java:786-792` — `productRepository.findById(id).filter(Product::getEnabled)`, unscoped; the service never imports `ProductSupplier` |
| A store change is caught late and as a 404 | `PurchaseOrderService.java:811-822` — `existsByProductVariantIdAndCompanyStoreId(...)` else `ProductVariantNotFoundException` |
| The endpoint already searches server-side | `PurchaseOrderController.java:89-92` `@RequestParam(required = false) String search`, backed by `ProductSupplierRepository.java:33-43` (`LOWER(p.name) LIKE … OR LOWER(p.sku) LIKE …`) |
| The client method already accepts the term | `data/product.service.ts:97-108` — `getProductsBySupplier(companyId, supplierId, search?)`, and no caller passes it |
| Lines have no ordinal | no `lineNumber` exists in `purchaseorder/` or `db/migration`; `PurchaseOrderDetailRepository.java:14` reads with no `ORDER BY` |

## Specs

**S1** — On `/purchases/orders/create` the detail area does not render at all: the operator sees the header only. The operator's words: «en la pantalla no aparezca el area de lineas de detalle hasta que se cree la orden de compra».

**S2** — Saving the header creates the order and the screen stays where it is, becoming `/purchases/orders/{id}`, with the detail area appearing to add products. The operator's words: «cuando se cree te deje en la misma pantalla y aparezcan las lineas de detalle para agregar productos». The navigation replaces the history entry, so Back from the created order does not return to a `/create` URL that would produce a second order.

**S3** — The created order is a `Draft` with no lines: the create call sends the header and no `details`. A failed create leaves the operator on `/purchases/orders/create` with the header intact and the server's message shown, and creates nothing.

**S4** — Each line persists on its own: adding a line is `POST /{id}/details` and the row appears from the server's answer; editing quantity or price is `PUT /{id}/details/{detailId}`; removing is `DELETE /{id}/details/{detailId}`. There is no batch line save and no local staging area that can diverge from the server.

**S5** — A line's failure is reported on that line and nowhere else: a rejected `POST` leaves no row, a rejected `PUT` restores the row's previous values, and neither clears the other rows nor navigates away. The general banner stays reserved for header failures.

**S6** — The product catalogue is searched on the server through the endpoint's existing `search` parameter: the field seeds with an empty term so the supplier's catalogue is offered before typing, a term of two or more characters narrows it after a 300 ms debounce, and a single character issues no request and keeps the previous result.

**S7** — The supplier and the store are editable while the order holds no lines and lock as soon as it holds one. The operator's words: «ya guardada la orden no se pueden mover los datos de la tienda y proveedor». Unlocking is not offered: to change them, remove the lines first.

**S8** — The lock is explained where it is visible. A locked supplier or store says why, so the operator is not left guessing which control to release; and the line-removal path that unlocks it is reachable.

**S9** — Lines render in creation order: by `createdAt` then `id`, because the API returns them with no `ORDER BY` and no ordinal column exists. This unit adds no migration and no ordinal field.

**S10** — An order that is not `Draft` renders its lines read-only: the detail area is visible, no add row is offered, and no quantity, price or removal control is enabled. The existing `isDraft()` gate stays the single source of that decision.

**S11** — Only the header can be dirty. Because lines save as they are added, the unsaved-changes guard covers the header fields alone, and leaving after a line was added does not prompt about that line.

**S12** — The Angular detail methods stop lying about their types: `addDetail` and `updateDetail` answer the `PurchaseOrderDetail` the API returns, and `deleteDetail` answers nothing, matching the **204**.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S12 | `data/purchase-order.service.ts` and `models/purchase-order.models.ts`: correct the three detail return types, and add `total`, `receivedQuantity`, `createdAt` and `updatedAt` to `PurchaseOrderDetail` where the response already carries them | — | inline | — |
| T2 | S1, S2, S3 | `purchase-order-edit.ts`: `save()` in create mode creates the order and navigates to `/purchases/orders/{id}` with `replaceUrl: true`, and the detail area is guarded on `isEditMode()` in the template | T1 | inline | — |
| T3 | S4, S5 | `purchase-order-edit.ts`: `addLine` / `updateLine` / `removeLine` calling the three detail routes, holding per-line pending and error state, and reconciling `lineItems` from the server's answer | T1 | inline | — |
| T4 | S9 | Order `lineItems` by `createdAt` then `id` on every load and after every mutation | T3 | inline | — |
| T5 | S6 | `purchase-order-edit.ts`: replace `supplierProducts` with a search stream keyed on the order's company, its supplier and the debounced term — `merge(of(''), term$.pipe(debounceTime(300), distinctUntilChanged()))` + `switchMap`, the idiom `product-supplier-dialog.ts:161-171` documents | T2 | inline | — |
| T6 | S6 | `detail-table.ts`: drop `filteredProducts` and the in-memory filter, take the already-searched options plus `loading` and `failed`, and emit the search term instead of filtering | T5 | inline | — |
| T7 | S6 | `detail-table.html`: render the searching, empty-catalogue and no-match states distinctly, so "the supplier has no products" stops reading as "nothing matched" | T6 | inline | — |
| T8 | S4, S5, S10 | `detail-table.ts` + template: `addItem`/`removeItem`/`patchRow` become emissions the page persists, per-row pending and error rendering, and the add row is not offered outside `Draft` | T3, T7 | inline | — |
| T9 | S7, S8 | `purchase-order-edit.ts` + template: `headerLocked = computed(() => lineItems().length > 0)` on the supplier and store controls, with the visible reason | T3 | inline | — |
| T10 | S11 | `unsaved-changes.guard.ts` and the page's dirty tracking: the guard covers the header alone once lines save on their own | T3 | inline | — |
| T11 | S1–S12 | Specs: `purchase-order-edit.spec.ts` for the create→stay→`{id}` transition, the late-company search, the term gate and the lock; `detail-table.spec.ts` for the emitted term, the three empty states and the per-row errors | T2–T10 | inline | — |
| T12 | — | Out of scope by the operator's decision: purging abandoned `Draft` orders that are never used. The operator's words: «lo que si dejaria fuera es como depurar las ordenes que se queden en borrador y no se utilicen». Recorded here so the gap is named rather than discovered later | — | — | — |
| T13 | S1–S12 | E2E and docs: the `e2e/specs` purchase-order flow through the two steps, and `AGENTS.md` where it describes the purchase-order screen | T11 | inline | — |
| T14 | S6 | **Deferred**: paginate `by-supplier` (`List<SupplierProductResponse>` → `Page`, `Pageable`, and `totalElements` rendered so "20 de 137" replaces a silent cap). Not needed while a two-character term narrows enough; needed once it matches hundreds of rows, which depends on a volume this repo has no seed to measure. `Risk: item 3` — it changes the response shape and breaks `ProductControllerTest.java:530` (asserts `$` is an array) and the three `ProductSupplierServiceTest` cases | — | — | — |

**Total task count: 14.**

## Decisions

**D1 (2026-11-09) — the catalogue is searched on the server.** The operator chose it over fetching the supplier's whole catalogue and filtering in memory. The endpoint and the Angular service already accept `search`, so the cost is the stream, not the contract; and the current design downloads the entire catalogue on every order open because `by-supplier` is unpaginated, so a debounced search downloads strictly less in every case.

**D2 (2026-11-09) — the header locks on the first line, not on creation.** The operator's proposal locked supplier and store once the order existed; they chose the narrower rule: editable at zero lines, locked at one. It keeps a typo correctable before any line is loaded and still makes the inconsistent order unreachable.

**D3 (2026-11-09) — lines persist one at a time against the detail sub-resource.** Chosen by the operator over one batch `PUT`. It matches what the API was built for — the three routes exist and carry a `Draft` guard — and it buys per-line errors instead of one banner for a whole payload. It also makes `purchase-order-detail-endpoints` a prerequisite, because those routes are not usable from a client that has no state catalog.

**D4 (2026-11-09) — ordering is a client concern, for now.** The API returns lines with no `ORDER BY` and the schema has no ordinal. Adding one is a migration on a table that already holds rows, which is item 1 risk for a display problem; sorting by `createdAt` then `id` is stable, free, and reversible when an ordinal is genuinely needed.

**D5 (2026-11-09) — the lock is a UI invariant, and the record says so.** `updatePurchaseOrder` still overwrites the supplier and the store unconditionally (`PurchaseOrderService.java:309-313`), so the API continues to accept a header change a client could send directly. Enforcing it on the server is a separate contract change and is not this unit; writing it here keeps the claim honest.

**D6 (2026-11-09) — purging abandoned drafts is out of scope.** Named by the operator. The two-step flow makes empty drafts possible where the one-step flow did not, so the gap is real; it is recorded as T12 rather than silently carried.

## Log

**L1 (2026-11-09) — the defect that opened the work.** «puedes revisar /purchases/orders/c1206910-3422-48ea-add6-61d4d620abd7 cuando intento agregar un producto no encuentra nada y no veo que se haga alguna peticion al backend puedes revisar»

**L2 (2026-11-09) — the question that changed the design.** «analiza cual es la mejor opcion si esa, o crear un servicio que se ejecute al autocompletar»

**L3 (2026-11-09) — scale context and the flow question.** «ahorita no esta productivo pero la idea es que si cresca, me inclino por la opcion B, solo explicame el flujo antes, para agregar un articulo ya tiene que estar la orden de compra previamente guardada?»

**L4 (2026-11-09) — the proposal this unit implements.** «que pasa si al crear la orden seleccionas la info del proveddor, desspues de varios articulos y sin guardar mueves el proveedor?, no sería más facil que primero se tenga que seleccionar la información de la tienda y proveedor, guardar la orden y ya guardada la orden no se pueden mover los datos de la tienda y proveedor y entonces si se puedan agregar articulos»

**L5 (2026-11-09) — the decision to build the flow first.** «si arrancamos con b ahora, no tendria que cambiar de que hagamos esto? mejor analicemos la funcionalidad completa y primero hagamos mi propuesta y en la pantalla no aparezca el area de lineas de detalle hasta que se cree la orden de compra y cuando se cree te deje en la misma pantalla y aparezcan las lineas de detalle para agregar productos, lo que si dejaria fuera es como depurar las ordenes que se queden en borrador y no se utilicen, puedes generar el odd o los odds para esta funcionalidad»

**L6 (2026-11-09) — the three answers that fixed the specs.** Catalogue searched on the server through the existing `search` parameter; the header locks when a line exists rather than on creation; lines persist one at a time against `POST`/`PUT`/`DELETE /{id}/details`.

**L7 (2026-11-09) — the measurement behind the problem table.** The catalogue request is emitted once, from `populateForm`, while `orderCompanyId()` is still null; the gate returns `EMPTY` and nothing re-emits it. Verified at `purchase-order-edit.ts:203-215`, `:280-282`, `:291-296` and `:315`. The edit-mode spec's mock order carries `companyId: null` (`purchase-order-edit.spec.ts:41`), which is why the case was never covered.

**L8 (2026-11-09) — the catalogue endpoint's missing ceiling.** «ok, si» — the operator approved recording the gap rather than fixing it now. `by-supplier` returns every match with no `Pageable` and no total, so a client cannot tell "that is all" from "there is more". The sales side already answers this shape (`product-variant-selector.ts:104-118` calls `/product-variants/search` with `page`/`size` and reads `page.content`), and `listProducts` is `Page<ProductResponse>`; `by-supplier` is the only product read in the repo that returns a bare `List`. Recorded as T14, deferred, with its cost.

**L9 (2026-11-10) — the chain this unit is delivered as, and why the token moves twice.** The operator asked for the three slices by name: «dale a u2 con los tres eslabones». Slice `01-service-and-types` ships **T1/S12** (the three detail methods stop answering a `PurchaseOrder` the server never sends); slice `02-two-step-and-per-line` ships T2–T4 and T8; slice `03-search-lock-specs` ships T5–T7, T9–T13. The header opens `deferred` from slice 01 on: the token must answer «is this in `main` or does it still owe something», and until the last slice merges the unit still owes most of itself — the alternative, leaving the false `implemented` in place while three PRs touch this subsystem, is the defect `L10` measures rather than names.

**L10 (2026-11-10) — slice 01, and the RED a type correction gets.** T1's second half was already true: `PurchaseOrderDetail` carries `total`, `receivedQuantity`, `createdAt` and `updatedAt`, so only the return types moved. The RED is a compile failure rather than a failing assertion, and it was observed: with the three signatures reverted, `tsc -p tsconfig.spec.json --noEmit` answered `error TS2339: Property 'quantity' does not exist on type 'PurchaseOrder'` at the two spec lines that read the answer, and the same command is clean with the change in. Runtime: `ng test --no-watch --include '…/data/purchase-order.service.spec.ts'` → **20 tests passed**; `PurchaseOrderDetailRequest.statusId` stays optional and a case now asserts `statusId` is absent from the body a catalog-less client sends.
