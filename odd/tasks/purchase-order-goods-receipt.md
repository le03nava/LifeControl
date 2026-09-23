# ODD feature: purchase-order-goods-receipt

**Repository**: LifeControl — `life-control-api/` (workstreams 1-2) + `life-control-app-angular/` (UI) + sales rework deferred (workstream 3)
**Status**: **W1 DELIVERED AND MERGED.** W1a = PR #117 → merge commit `e846155`; W1b = PR #118 → merge commit `56f76ac` (retargeted from `feat/po-receipt-w1a` to `main` after #117 landed). `origin/main` is now `56f76ac`, and its tree is `127c631a14a83604c9fa5e1f9cde49cc63653740` — **byte-identical to the gate-verified W1b candidate**. Post-merge CI on `main`: API CI success 2m27s, Angular CI success 3m52s. Both feature branches were deleted (remote and local). **W2 IN PROGRESS** — **W2a** (inventory core) delivered as commit `e810a48` on `feat/po-receipt-w2a` (10 files, +1431); **W2b** (per-store inventory settings) delivered as commit `4cc7f2d` on `feat/po-receipt-w2b` (16 files, +2062, stacked on `e810a48`); decisions **W2-D1…W2-D5** locked. Neither branch is pushed and no PR is open — that remains the user's decision. **W2c DELIVERED** as five commits on `feat/po-receipt-w2c` — `eace8f0` (schema/entities/repositories), `692f595` (reception use case + `/api/goods-receipts` API), `edfb946` (the `lc-receiving` role), `407db1b` (the security review's required changes), `162507e` (the gateway route) — with the mandatory security review satisfied by a substituted security-focused verification (the provider-owned risk reviewer is not dispatchable while the review switch is off). Final gates: **608 suites / 1990 tests / 0 failures / 0 errors / 0 skipped**, spotless clean, 0 SpotBugs findings, gateway compiling. **ALL THREE W2 SLICES ARE MERGED INTO `main` (user-authorized push, PR and merge, 2026-09-20)**: **#119** (W2a) → merge **`62184b4`** (tree `49ee03b…` = the `e810a48` tip tree), **#120** (W2b) → merge **`55cfab3`** (tree `0a477f2…` = the `4cc7f2d` tip tree), **#121** (W2c) → merge **`cd60b2f`** (tree `d52e68c…` = the `162507e` tip tree). **`origin/main` is now `cd60b2f` and its tree is `d52e68c0f59746a0e66e5528ee695a2fa0efce8c` — byte-identical to the W2c tip that passed the 608-suite / 1990-test gate**, so every slice's merged content is exactly the reviewed bytes. Each PR was retargeted to `main` before its merge (the W1 procedure) and its diff re-verified clean at that point (10 / +1431, 16 / +2062, 39 / +4580−14). Post-merge CI on `main` @ `cd60b2f`: **API CI success**, **Angular CI success**. No labels and no issue linkage, matching the house convention (no PR template, no PR-validation workflow, no `type:*` label in use beyond `type:chore`). The three feature branches were then **deleted, remote and local**, after verifying each tip was reachable from `main`: `git branch -d` (never `-D`) so the delete could not silently drop unmerged work, `git push origin --delete` for the remote side, plus `git remote prune origin`. **`origin` now carries `main` only**, and all seven W2 commits (`e810a48`, `4cc7f2d`, `eace8f0`, `692f595`, `edfb946`, `407db1b`, `162507e`) remain reachable through their merge commits — verified with `git merge-base --is-ancestor`. **Two unmerged branches were deliberately left untouched**: `chore/project-conventions-skill` (`cec4578`, unpushed, the only copy) and `fix/docker-artifact-provenance` (`b30c8d3`, checked out in `/home/leo3nava/workspace/LifeControl-worktrees/docker-artifact-provenance`, owned by another session). #121 carries the substituted-not-native security review flag in its body, and that flag **survives the merge** — see the security-review section below. **W2d (Angular UI) IN PROGRESS** — sliced into **W2d1** (receipts UI) and **W2d2** (store inventory-settings screen) under **W2-D11**, with decisions **W2-D11…W2-D14** locked on 2026-09-20; the `lc-receiving` routing guard moved to its own **W2d3** slice with its own security review. **W2d1 DELIVERED AND MERGED** — 14 commits on `feat/po-receipt-w2d1`, tip `6be6950` (tree `1d491d97`), rebased onto `main` @ `3e451fe` before delivery; **PR #125 → merge `b3b58f5`**, and `main^{tree}` after that merge is `1d491d97` — byte-identical to the gated tip. **W2d2 DELIVERED AND MERGED** — 4 commits on `feat/po-receipt-w2d2` (tip `0340481`, tree `4e390525`), stacked on `6be6950`; gates green at the tip and two independent verifications (0 blocking findings, 3 real defects, and one doc drift of my own that the second round refuted and that `0340481` fixes); **PR #126 → merge `c9630ec`**, and `main^{tree}` after that merge is `4e390525` — byte-identical to the gated W2d2 tip. Both PRs: **no issue, no labels, body in English with the repo's fixed sections**, `Angular CI → Lint, Build & Test` pass on each (3m51s / 3m45s), and `API CI` / `Docker Build Integrity` correctly **not triggered** (their path filters: W2d is 100% `life-control-app-angular/**`). Post-merge CI on `main` @ `c9630ec`: **Angular CI success, API CI success, Docker Build Integrity success**. Both remote branches were deleted explicitly (`gh pr merge --merge`, then `git push origin --delete`), so `origin` carries `main` only. No force-push was needed: after #125 merged, #126's merge-base (`6be6950`) was already an ancestor of `main`, so the retargeted diff was exactly the 4 commits and the merge preserved the tree byte for byte — the `git push --force-with-lease` a local rebase would have required was blocked by the safety policy and turned out to be unnecessary. See `### W2d2 results` below. **W2d3 DELIVERED** — 7 commits on `feat/po-receipt-w2d3` off `main` @ `f576c34` (the #127 merge), tip `3dff094` (tree `70dfb1a7`), range `10 archivos / +352−41`; the `lc-receiving` routing guard, its own adversarial security verification (0 blocking findings, 4 real doc defects refuted and fixed, plus one deterministic e2e defect of our own that the run caught), gates green at the tip. **PR #128 open** (MERGEABLE, `Angular CI → Lint, Build & Test` pass 3m50s, no issue, no labels) — **not merged**, the user's decision. W3 deferred.
**Created**: 2026-09-19
**Base**: `main` @ `516348a`. Branch `feat/po-receipt-w1a`. The prior session's `project-conventions` work was committed separately as `cec4578` on branch `chore/project-conventions-skill` (not pushed).
**Origin**: user request — "recepción de mercancía de las órdenes de compra, la mercancía se debe agregar al inventario y a una localización de recibo parametrizable para la tienda y cambiar los estatus de recepción de la orden de compra"

## Objective

Receive goods against a purchase order: increase inventory, register the goods at a **parameterizable per-store receiving location**, and drive the purchase-order reception statuses automatically.

## Scope split (user decision, 2026-09-19)

The work is deliberately split into **three independent fronts** so that the purchase-order changes and the receipt implementation do not depend on reworking sales:

| Front | Content | Ships when |
| --- | --- | --- |
| **W1 — Purchase-order changes** | What the purchase order must model and expose so a receipt can exist: variant on the line, contract, reception status model, guards. | First |
| **W2 — Goods receipt** | The receipt document, the parameterizable receiving location, the inventory effect (location balances + ledger), the role. Consumes W1. | Second |
| **W3 — Sales rework (deferred)** | Make the sales path location-aware, restore the strict stock invariant, reconcile, and write `SALE` movements. | Future — explicitly deferred by the user |

W1 → W2 is a hard dependency. W3 is not: it is deferred and its absence creates a **known, accepted interim inconsistency** documented below.

## Decision register

| # | Decision | Choice |
| --- | --- | --- |
| **D1** | Inventory model | **1B** — stock balance per `(product_variant, store_location)` **plus** an append-only `inventory_movements` ledger. `product_variants.stock` stays as the sellable aggregate. |
| **D2** | Product → variant resolution | **2B** — `purchase_order_details` gains `product_variant_id`; purchase-order lines are loaded by choosing a variant. No ambiguity at reception time. |
| **D3** | Receiving location | **3A** — per-store configuration (`receiving_location_id`) **with override at reception time**. |
| **D4** | Reception role | **4A** — new client role `lc-receiving` (warehouse), provisioned in `docker/scripts/keycloak-setup.sh`, added to `Roles.java`; only `lc-receiving` + `lc-admin` create receipts and edit inventory settings. **Triggers the mandatory security review (high risk).** |
| **D5** | Delivery shape | **5A** — small chained increments, each leaving `main` green, verified with integration tests (Testcontainers, `AbstractPostgresIntegrationTest`). |
| **D6** | Historical data | **No backfill.** The user confirmed the system is not in production; existing rows may be reset and the ledger starts empty. |
| **D7** | Which location sales deduct from | **B with operator override** — default deduction by priority (the store's sales location first, then remaining locations FIFO), and the operator may explicitly define the location. **Deferred with W3**: sales is not touched now. |

## The interim inconsistency (accepted, must be documented in code)

While W3 is deferred, `SalesOrderService.applyStockChanges` (`:774`) keeps doing exactly what it does today:

```java
variant.setStock(variant.getStock().subtract(delta));   // aggregate only, no location
```

That produces a deliberate, temporary divergence:

```
product_variants.stock   = SUM(location stock) - (everything sold since receipt, until W3 lands)
```

Consequences that W2 must respect:

1. **`product_variants.stock` remains the sellable truth.** W2 must not change what sales reads.
2. **W2 must apply receipt stock ADDITIVELY to both the location balance and the aggregate — never as a `SUM(locations)` recompute.** Recomputing the aggregate from locations would *resurrect* stock that sales already sold: the recompute would overwrite the sales decrement with a larger location sum. This is the single most dangerous implementation detail of W2.
3. **The location-balance view overcounts** by the total sold quantity. Any UI that shows location stock must not present it as sellable availability until W3 lands.
4. **The ledger will only contain `RECEIPT` movements** (no `SALE`, no `ADJUSTMENT`) until W3. It is a partial history, not yet a full stock ledger. Do not sell the ledger as complete audit until W3.
5. **W3 owes a reconciliation.** When sales becomes location-aware, location balances must be repaired to match the aggregate (with D6 this can be a reset rather than a computed backfill).

## Problem (confirmed evidence, read-only)

1. **Reception does not exist.** `PurchaseOrderService.updatePurchaseOrderDetailStatus` (`:384`) validates the detail transition and sets `received_quantity` (`purchase_order_details.received_quantity`, `V1__baseline_schema.sql:348`) but **never touches inventory**. `PurchaseOrderDetailStatusChangedEvent` carries `receivedQuantity`/`orderedQuantity` and its javadoc (`:8-12`) calls itself a hook for a "future goods-receipt consumer" — that consumer does not exist (no listener for it anywhere in the repo).
2. **There is no inventory module and no ledger.** `grep -rn "stock" db/migration/*.sql` returns exactly one line: `product_variants.stock DECIMAL(12,2) DEFAULT 0` (`V1__baseline_schema.sql:409`). Stock is a bare number per `(product_variant, company_store)` with no record of *why* it changed.
3. **Inventory has no location dimension.** `grep -rn "location_id" db/migration/*.sql` → no results. `store_locations` (`V7__store_locations.sql`) is the level-4 leaf of `Store → Area → Zone → Location` and nothing references it from inventory.
4. **No per-store configuration surface.** `CompanyStore` (`store/model/CompanyStore.java`) holds only `storeName`, `email`, `phoneNumber`, `address`, `version`. There is no settings table and no settings screen in Angular.
5. **No atomic reception endpoint.** `PO_TRANSITIONS` (`PurchaseOrderService.java:60`) and `DETAIL_TRANSITIONS` (`:70`) exist, but the header status is set by hand via `PATCH /{id}/status` and is never derived from the detail lines.
6. **Frontend slot exists but is inert.** `purchases/receipts/pages/receipts-placeholder/receipts-placeholder.ts` is a static "Coming Soon" page, the dashboard "Receipts" card is disabled, `PurchaseOrderDetail.receivedQuantity` is modelled but never read, and `purchase-order.service.ts` has no method for `PATCH /api/purchase-orders/{id}/details/{detailId}/status` (exposed by `PurchaseOrderController.java:203`).
7. **Structural mismatch.** `purchase_order_details.product_id` → `products`; stock lives on `product_variants` (`product_id` + `company_store_id`). `product_variants` has **no** `UNIQUE(product_id, company_store_id)` (only a unique index on `bar_code`, `V1:415`).

---

# W1 — Purchase-order changes

**Goal**: make the purchase order reception-ready. No receipt entity, no inventory effect, no settings.

## What changes

| # | Change | Why it is W1 and not W2 |
| --- | --- | --- |
| W1-1 | `purchase_order_details.product_variant_id UUID REFERENCES product_variants(id)` (D2) | It is a purchase-order column; the receipt only reads it |
| W1-2 | Line validation: the chosen variant must belong to the PO's `company_store_id` | The variant is `product × store`; a line pointing at another store's variant is invalid data regardless of receipts |
| W1-3 | `PurchaseOrderDetailRequest` / `PurchaseOrderDetailResponse` carry `productVariantId` | Contract of the purchase-order endpoints |
| W1-4 | Reception status model: detail status derived from quantities (total `< quantity` → `Partial Received`; `== quantity` → `Received`) and header derived (`In Transit` → `Received` when every line is received) | It is the purchase order's own state machine |
| W1-5 | Receivability guard: only a PO in `Accepted`/`In Transit` accepts reception | Domain rule of the purchase order |
| W1-6 | Reconcile the manual `PATCH /{id}/details/{detailId}/status` with W1-4: keep it for `Rejected`/`Cancelled`, stop letting it set `Partial Received`/`Received` by hand | Two writers for one state is a defect |

## W1 decisions (resolved with the user, 2026-09-19)

| # | Question | Decision |
| --- | --- | --- |
| **W1-D1** | How to resolve the W1-3 blocker (a required `productVariantId` breaks the Angular PO form) | **Chained**: **W1a** backend with `productVariantId` **optional** (column nullable, DTO optional) + **W1b** the Angular variant picker, which flips the field to required. No invalid window in practice, both slices small, and W2 is not inflated. |
| **W1-D2** | Fate of the manual detail-status writer `PATCH /{id}/details/{detailId}/status` | **Removed entirely**: endpoint, service method, `UpdatePurchaseOrderStatusRequest` usage on that path and its unit/controller tests. Verified: nothing consumes it (no method in `purchase-order.service.ts`, no gateway path, no other caller). |
| **W1-D3** | Response shape | `PurchaseOrderDetailResponse` carries `productVariantId` **and** `productVariantName` (null when the line has no variant), mirroring the existing `productName` denormalization so W1b needs no extra variant lookup to display a line. |
| **W1-D4** | HTTP class for "this variant does not belong to this product/store" | **404** via a new `ProductVariantNotFoundException extends ResourceNotFoundException`. Rationale: local precedent `StoreZoneIntegrationTest.createZone_UnknownAreaReturns404` (child referencing an unknown parent) and the conventions rule "do not introduce a new pattern when an internal standard already exists". |
| **W1-D5** | How the derived status respects the transition envelope | The derived target (`Partial Received` / `Received`) must be **reachable** from the line's current status in `DETAIL_TRANSITIONS` (bounded reachability walk, at most `|table|` hops); otherwise `InvalidStatusTransitionException` (409). Rationale: W1-D2 removes the only writer that advanced lines `Pending → In Process → In Transit`, so a strict one-hop check would make reception impossible in W2 for a line still at `Pending`. `Rejected`/`Cancelled`/`Received` stay terminal. |
| **W1-D6** | Over-receipt and non-positive quantity in the reception entry point | `IllegalArgumentException` → 400, preserving the exact behaviour class of the code W1a removes; W2 owns the typified rule (`0 <= received + this <= ordered`) at the receipt level. |

## W1a — Purchase-order model and state machine (backend only)

Goal: the purchase order is reception-ready (it models the variant and derives its own reception statuses) with **no receipt entity, no inventory effect and no settings**. Leaves `main` green; no Angular change, so the PO form keeps working.

- [x] W1a-T1 `V9__purchase_order_detail_variant.sql`: `product_variant_id UUID REFERENCES product_variants(id)` (nullable, no backfill per D6) + `CREATE INDEX idx_pod_variant` — no `IF NOT EXISTS`, matching `V7`/`V8` style.
- [x] W1a-T2 `PurchaseOrderDetail` entity: lazy `@ManyToOne` `productVariant` mapped to `product_variant_id` (nullable) + getter/setter/builder.
- [x] W1a-T3 `ProductVariantRepository.findByIdAndProductIdAndCompanyStoreIdAndEnabledTrue` + 404 on a miss.
- [x] W1a-T4 Variant validation on every detail write path (`createPurchaseOrder`, `updatePurchaseOrder`, `addPurchaseOrderDetail`, `updatePurchaseOrderDetail`): when `productVariantId` is supplied it must resolve for `(variantId, productId, po.companyStore.id)` **and be enabled**.
- [x] W1a-T5 `PurchaseOrderDetailRequest.productVariantId` (optional) + `PurchaseOrderDetailResponse.productVariantId`/`productVariantName` (W1-D3) + `toDetailResponse` mapper.
- [x] W1a-T6 Reception state machine: detail status derived from quantities (W1-D5 reachability envelope) + header derivation (`In Transit` → `Received` when every enabled line is `Received`), both driven by `PO_TRANSITIONS`/`DETAIL_TRANSITIONS`; publishes `PurchaseOrderStatusChangedEvent` for the header and `PurchaseOrderDetailStatusChangedEvent` for the line.
- [x] W1a-T7 Receivability guard: only `Accepted`/`In Transit` accept reception; otherwise `InvalidStatusTransitionException` (409). Also rejects a soft-deleted line.
- [x] W1a-T8 Remove the manual detail-status writer (W1-D2): controller endpoint, service method, its tests, and the orphaned `validateDetailTransition` helper.
- [x] W1a-T9 Tests: unit (variant resolution, guard, derivation per starting status, terminal rejection, quantity guards, header promotion + event payload, cross-order detail rejection), controller (endpoint unmapped), integration on `AbstractPostgresIntegrationTest` (round-trip, explicit null variant, foreign product, foreign store, real reception flow through the service bean, seeded-status inventory).
- [x] W1a-T10 Gate `spotlessCheck spotbugsMain` + `test` green (**1827 tests, 0 failures, 0 errors, 0 skipped**), then work-unit commit `611cdba`.
- [x] W1a-T11 Push + PR: **#117** (11 files, +1238/−135, CI `Check` pass, no labels, no issue linkage — matching the repo convention: no PR template, no issue gate, no `type:*` labels in use). The merge is the user's decision.

**Verified facts closed inside W1a** (independent read-only verification + writer rounds): the seeded status names match the transition-table keys exactly for both types (asserted against real PostgreSQL); the response serializes `productVariantId` as an explicit `null` (not an absent key) on a variant-less line; the header promotion cannot fire vacuously (zero enabled lines now returns false); the duplicate exception was dropped in favour of the pre-existing `com.lifecontrol.api.product.exception.ProductVariantNotFoundException`; `UpdatePurchaseOrderStatusRequest` no longer advertises the dead `receivedQuantity` field.

**Follow-ups discovered, deliberately NOT fixed in W1a** (all pre-existing or W2-owned):

1. `(purchaseOrderId, detailId)` ownership gap in the older `updatePurchaseOrderDetail`/`deletePurchaseOrderDetail` — a mismatched pair can mutate another order's line. Only the new reception entry point checks it.
2. `createPurchaseOrder` never persists its details (no `cascade` on `PurchaseOrder.details`, no `detailRepository.save`): the response shows lines that are not in the database. Pre-existing, confirmed in the diff of this change.
3. No concurrency control on the header promotion (`PurchaseOrder` has no `@Version`); W2's receipt must lock (the `findByIdForUpdate` precedent exists on `ProductVariantRepository`).
4. `GlobalExceptionHandler` has no `NoResourceFoundException` handler, so any unmapped route (including the removed one) answers **500** instead of 404.

**Note on the reception entry point**: `registerReceivedQuantity(...)` is the W2 call site and ships in W1a with a javadoc that says so, reusing the same "hook for the goods-receipt consumer" precedent as `PurchaseOrderDetailStatusChangedEvent` (whose javadoc already declares itself as exactly that, and which stays in place unreferenced until W2 publishes it).

## W1b — Angular variant picker + required variant on the contract (follows W1a)

**Goal**: the client always sends the variant, so the contract can require it. This slice flips `productVariantId` to required end to end.

### W1b decisions (resolved with the user, 2026-09-19)

| # | Question | Decision |
| --- | --- | --- |
| **W1b-D1** | Where the picker gets its options | **Optional `storeId` filter on the existing product-scoped endpoint** `GET /api/products/{productId}/variants` + a combined finder (`findByProductIdAndCompanyStoreIdAndEnabledTrueOrderByCreatedAtDesc`). Additive and backward compatible (omitting `storeId` keeps today's behaviour). Rejected: filtering in the client (variants are per `(product, store)` and the endpoint paginates at 12, so the right variant may not be on the loaded page) and a new dedicated endpoint (a third variant listing surface). |
| **W1b-D2** | How "required" is enforced | **`@NotNull` on the request; the column stays nullable.** The API is the invariant boundary; legacy rows with `NULL` are tolerated on read and repaired by the next update (which now always carries a variant). Rejected `V10 … SET NOT NULL`: it fails on existing `NULL` rows, no backfill is allowed (D6), and no migration in this repo has ever deleted or repaired rows. |
| **W1b-D3** | Line editor behaviour | Variant is chosen in the add-line form (product first, then the variants of that product for the order's store) and rendered **read-only** in the line table alongside the product, exactly like `productName` today. Selecting a variant **prefills `unitPrice` with the variant's `costPrice`** when the user has not typed a price yet. |

### W1b task list

- [x] W1b-T1 Backend (additive): optional `storeId` query param on `ProductController.listVariants`, `ProductVariantService.listVariants(productId, storeId, pageable)` and the combined repository finder, with unit + controller tests. Ships alone — nothing existing changes behaviour. → commit `8659bec` (6 files, +359/−8).
- [x] W1b-T2 Contract + client flip (one atomic work unit): `@NotNull` on `PurchaseOrderDetailRequest.productVariantId`, the null-expecting backend tests flipped to expect 400, **and** the Angular side in the same commit — `productVariantId`/`productVariantName` in `PurchaseOrderDetail`/`PurchaseOrderDetailRequest`, the purchases-local `product-variant-picker` component, `DetailTableRow` carrying the variant, the picker wired into the add-line form, the `costPrice` prefill (W1b-D3), the line table showing the variant, and the payload builder always sending it.
- [x] W1b-T3 Specs: Vitest coverage for the picker (loads by product + store, empty state, error state, prefills the price, emits the selection) and updated purchase-order-edit/service specs for the payload; backend tests as described in T1/T2.
- [x] W1b-T4 Gates: **green; PR #118 opened** (branch pushed as `origin/feat/po-receipt-w1b`, base `feat/po-receipt-w1a`). Committed as `645d087` (T2 Angular, 20 files, +1481/−15, includes the squashed non-Draft warning fix-up) and `28405a0` (T2 backend, 5 files, +215/−16). Gate evidence on the final bytes: `npm run lint` exit 0; `npm run test:coverage:check` exit 0 — **103 files / 1887 tests passed**, coverage 91.60 / 73.96 / 87.42 / 91.60 vs 80/60/75/80; `npm run build` exit 0 (4 pre-existing budget warnings); `./gradlew spotlessCheck spotbugsMain test` exit 0 (559 suites / 1840 tests / 0 failures / 0 SpotBugs findings) — **cache-hit, not a fresh execution** (a frontend-only candidate makes that gate causally independent). The two GATE-verified files were committed byte-identically: post-rebase `HEAD^{tree}` = `127c631a` equals the pre-rebase tree of the verified working tree. Remote CI on PR #118: `Check` pass 2m02s, `Lint, Build & Test` pass 3m37s. **Merge of #117 (then #118 retargeted to `main`) is the user's decision.**

**W1b fix-up round and commit identity** (2026-09-19): an independent read-only verification found that the inline missing-variant warning of `detail-table` was not conditioned on `isDraft()`, so outside Draft it told the user to delete and re-add a line while both controls are disabled — contradicting the (correct) save banner that already branches on `isDraft()`. Two inline edits fixed it (the warning copy now branches on `isDraft()`; a spec covers the non-Draft case), plus `@if (items().length > 0)` → `!== 0` to clear a pi-lens HTML false positive on the unescaped `>` (semantically identical for an array). After gates passed, the edits were squashed with `git commit --fixup=a3fd81f` + `git rebase -i --autosquash 8659bec` (the rebase bypassed the commit hooks on purpose: the squashed bytes were already hook-canonical). Old → new commit ids: `a3fd81f` → **`645d087`**, `a5dcb72` → **`28405a0`**; `8659bec` unchanged. The rebase reproduced the verified bytes exactly (`HEAD^{tree}` = `127c631a` before and after).

**Recon facts that bound this slice** (read-only mapping, before any write): the sales-side `product-variant-selector` is **not** reusable as a line editor — it is a table-level scan box that auto-emits and clears, has no product filter and no row context; there is **no** Angular variant data service anywhere (the sales selector calls `HttpClient` inline); the Angular purchase-order models were never touched by W1a; and permission-wise both variant endpoints and the PO detail endpoints use the same `lc-admin`/`lc-sales` roles, so nothing new is exposed.

---

# W2 — Goods receipt

**Goal**: the receipt document, the parameterizable receiving location, the inventory effect, the role. Depends on W1.

## W2 execution plan (locked with the user, 2026-09-19)

### Recon facts that bound W2

Independent read-only mapping over `main` @ `56f76ac` (W1 merged, Flyway head `V9`):

1. **`statuses` has no `type` column.** The group is the FK `status_type_id → status_types`, with `UNIQUE(status_type_id, status_name)` (`V1__baseline_schema.sql:255-276`). Only four types are seeded — `PURCHASE_ORDER`, `PURCHASE_ORDER_DETAIL`, `SALES_ORDER`, `SALES_ORDER_ITEM` (`V3__seed_reference_data.sql:11-25`). **There is no goods-receipt status family**: W2 seeds a new `status_type` plus its statuses. Status lookup is `StatusRepository.findByTypeNameAndStatusName` (`:22-29`), and type enforcement is `StatusValidator.requireStatusOfType` (`:19-28`).
2. **`generateOrderNumber` is not concurrency-safe.** It reads the max order number then inserts, with no lock, no sequence and no retry (`PurchaseOrderService.java:627-649`); uniqueness rests only on `order_number UNIQUE` (`V1:319`). The original plan's "atomic **and** idempotent by `receipt_number` (same generator pattern)" inherited neither property.
3. **Quantity type mismatch.** `purchase_order_details.quantity` and `received_quantity` are `INTEGER` with `CHECK (quantity > 0)` / `CHECK (received_quantity >= 0)` (`V1:345-348`) while `product_variants.stock` is `DECIMAL(12,2)` (`V1:409`). `registerReceivedQuantity(UUID, UUID, int)` takes the **accumulated total**, not the delta (`PurchaseOrderService.java:438`).
4. **No "location belongs to store" query exists.** The chain is `store_locations → store_zones → store_areas → company_stores` and must be composed from `StoreAreaRepository.findByIdAndCompanyStoreId` → `StoreZoneRepository.findByIdAndStoreAreaId` → `StoreLocationRepository.findByIdAndStoreZoneId`. `StoreLocationRepository` has **no** store-scoped finder, and **no endpoint lists locations by store** (only `GET /api/companies/.../store-zones/{zoneId}/store-locations`), so the picker's data source is real work, not a reuse.
5. **`PurchaseOrderDetailStatusChangedEvent` still has no listener** (`@EventListener`/`@TransactionalEventListener` grep over `src/main` finds only the unrelated activity-log and Keycloak listeners). An `AFTER_COMMIT` listener would break the receipt's atomicity, so the receipt use case calls `InventoryService` directly inside the same transaction.
6. **Gateway routing.** `/api/companies/**` and `/api/store-areas|store-zones|store-locations/**` are routed; **`/api/company-stores/**` is not** (`api-gateway/.../Routes.java:63-80`). `api-gateway` is a separate build with no CI.
7. **Store write authorization precedent**: `@PreAuthorize` with `ADMIN, COMPANY, COMPANY_COUNTRY, COMPANY_REGION, COMPANY_ZONE, COMPANY_STORE` (`CompanyStoreController.java:90-91`), exactly the Angular `STORE_WRITE_ROLES` set.
8. **Legacy `NULL` variant lines.** W1b made the variant required on the contract but deliberately kept the column nullable (W1b-D2), so a `NULL`-variant line cannot produce a `goods_receipt_items.product_variant_id NOT NULL` row. W2c must fail closed with a clear error naming the line, and never silently skip the inventory effect for it.

### W2 locked decisions

| # | Decision | Choice |
| --- | --- | --- |
| **W2-D1** | Delivery shape | **Four chained slices**, each leaving `main` green and each a small reviewable PR: **W2a** inventory core (inert), **W2b** per-store receiving/sales location settings, **W2c** receipt document + endpoints + the `lc-receiving` role, **W2d** Angular UI. Slice branches stack (`W2b` off `W2a`), following the W1a/W1b precedent. |
| **W2-D2** | Session scope | **W2a and W2b only** in this session: no auth surface, no receipt document. W2c carries the mandatory security review for the new role and gets its own focused pass. |
| **W2-D3** | Quantity type | **`DECIMAL(12,2)` throughout the receipt and the ledger**, as the original DDL intended, even though the purchase order's quantities are `INTEGER`. `product_variants.stock` stays `DECIMAL(12,2)`, so no stock column changes. **Known consequence deferred to W2c** and recorded so it is not forgotten: the accumulated reception is written back into `purchase_order_details.received_quantity INTEGER`, so W2c must define how a decimal reception lands on an integer column (integrality check at the receipt boundary, versus migrating the column). |
| **W2-D4** | Atomicity of the receipt | **A pessimistic lock on the purchase order** with `receipt_number` generated inside the lock (precedent `ProductVariantRepository.findByIdForUpdate:37-40`). Explicitly **not** chosen: a client idempotency key, unique-violation retry, or a DB sequence. |
| **W2-D5** | Migration numbering | Delivery order wins over the original plan's numbering: `V10` = inventory core (W2a), `V11` = store inventory settings (W2b), `V12` = goods receipts (W2c). Applied migrations are never renumbered. |
| **W2-D6** | REST surface | **Flat `/api/goods-receipts/**`**, following the module's document convention (`/api/purchase-orders`, `/api/sales-orders`): `POST /api/goods-receipts` (create, `purchaseOrderId` in the body), `GET /api/goods-receipts?search=&page=&size=` (paged list), `GET /api/goods-receipts/{id}`. Costs **one line** in `api-gateway/.../routes/Routes.java`; no `SecurityConfig` change (`/api/**` is already `.authenticated()`). Rejected: nested-only under the purchase order (no global list for W2d) and a mixed surface (two path conventions in one slice). |
| **W2-D7** | `W2-D3` integrality, resolved | **Integrality check at the receipt boundary.** `goods_receipt_items.quantity_received` stays `DECIMAL(12,2)` (W2-D3) but the use case rejects any non-whole `quantityReceived` with a 400 naming the line. **`purchase_order_details.quantity` / `received_quantity` stay `INTEGER`** — no schema change, no `Integer`→`BigDecimal` churn across the PO DTOs, `registerReceivedQuantity(int)` signature or the Angular form. Accepted cost: 2.5 kg cannot be received until W3 revisits the measure-unit question. |
| **W2-D8** | Receipt numbering | **`GR-{orderNumber}-{NN}`** (e.g. `GR-PO-20260603-00001-01`), generated **inside the purchase-order lock** by **`COUNT(*)` over that order's receipts**, not by a `max(receipt_number)` string lookup — receipts are immutable (no update, no delete), so the count is an exact monotone counter, and counting sidesteps the trap the inherited generator has, where ordering zero-padded numbers as text breaks the moment the suffix widens (`"…-100" < "…-99"`, and `"…100000" < "…99999"`). The counter is PO-scoped, so **W2-D4's per-order lock is exactly what protects it** — the two decisions compose without a new DB object. `order_number` is always generated (`PurchaseOrderRequest` accepts no `orderNumber`) so the length is bounded; the generator **fails closed if the number would exceed `VARCHAR(30)`**. |
| **W2-D9** | Receiving authorization | **A new `lc-receiving` client role, added to `ScopeLevel.STORE`.** `verifyCompanyStoreAccess` only recognizes store scope through `ScopeLevel.STORE` roleNames (`ScopeLevel.java:36`, today `lc-company-store`/`lc-company-store-read`), so a standalone role would 403 unconditionally; adding it is what makes the role functional. **The six existing store write roles keep access to the receipt endpoints** (new capability, nobody loses access). Requires the `company_store_id` claim to reach the token for a user holding only `lc-receiving`, and makes the **mandatory security review** the gate for this slice. |
| **W2-D10** | Tenant scoping of the receipt reads | **The new `/api/goods-receipts` reads are store-scoped; the leak is not copied.** A flat list has no store in its path, and `@PreAuthorize` only proves the caller holds *some* store-scoped role — so an unscoped list would hand every store's receipts to any store-level user, contradicting W2-D9's store-scoped receiving role. The module has the infrastructure already (`CurrentUserContext.getCompanyStoreIds()`), and the flat store controllers (`StoreLocationFlatController`) establish the house pattern of resolving the entity's chain and authorizing through `verifyCompanyStoreAccess`, but they are **id-addressed only** — there is no tenant-scoped *list* precedent. So: non-admins are filtered to their `company_store_ids` (an empty set yields an empty page, not an error), admins stay unscoped, and `GET /{id}` verifies the receipt's own store. **Recorded as a pre-existing platform defect, deliberately out of scope**: `GET /api/purchase-orders` (`PurchaseOrderService.getAllPurchaseOrders:120-130`) and other flat lists are already unscoped and returning every tenant's rows to any authenticated caller. Widening this slice to fix them would be a different change with a different blast radius; it belongs in its own follow-up. |

### W2 route declaration and checks

- **Route per task**: W2a and W2b are each delegated to one bounded writer (`gentle-ai-worker`, foreground `mode: "task"` — background subagent policy is off), with `gentle-ai-verify` for independent verification. The parent owns the plan, the decisions and synthesis.
- **TDD mode: unknown — no precedence invented.** No TDD configuration exists in this repository or session (`.pi/` holds only `gentle-ai/sdd-preflight.json`; there is no `tdd` key in the root or component `AGENTS.md`). Because the mode is unresolved, the tasks run **ordinary functional checks**, not strict TDD, with the repository's exact runners: `cd life-control-api && ./gradlew spotlessCheck spotbugsMain test` (the CI gate, `.github/workflows/api-ci.yml`) and, for Angular, `npm run lint && npm run test:coverage:check`. Strict TDD with its RED/GREEN/REFACTOR evidence can be enabled on request; the resolved mode, its source and the runner would then be recorded here.
- **Delivery strategy**: `feature-branch-chain` (stacked slices), as W1 did. W2a+W2b authored-line forecast: **~1,100–1,400** (additions + deletions, generated files excluded) — above the ~400 advisory, which is exactly why the chain strategy was chosen up front instead of at the first overflow.
- **RDD**: disabled by default. Per work-unit commit the parent calls `gentle_review` with `{"operation":"assess"}` over that committed range and follows the returned plan, instead of judging risk from the task description.

## W2a — Inventory core (inert, no auth, no settings)

**Goal**: the location balances and the append-only ledger exist and the additive receipt mutator is correct under the interim inconsistency. Nothing calls it yet; `main` stays green and existing behaviour is unchanged.

- [x] W2a-T1 `V10__inventory.sql`: `product_variant_locations` with `UNIQUE (product_variant_id, store_location_id)` + `inventory_movements` (append-only, `movement_type VARCHAR(30)` carrying only `RECEIPT` until W3) + the indexes the original plan omitted (`product_variant_id`, `store_location_id`, `company_store_id`, `occurred_at`, and `(reference_type, reference_id)` as the receipt back-link). `V7`/`V8` style, no `IF NOT EXISTS`, `gen_random_uuid()` defaults as in `V1`.
- [x] W2a-T2 Entities `ProductVariantLocation` / `InventoryMovement` + repositories, with a pessimistic locking finder for the `(variant, location)` balance mirroring `ProductVariantRepository.findByIdForUpdate`. The movement type follows the repository's existing enum-mapping convention.
- [x] W2a-T3 `InventoryService.applyReceipt(...)`: lock the variant **and** the location balance in **sorted id order** (precedent `SalesOrderService.applyStockChanges:851-868`), append the ledger row, `location.stock += qty`, `variant.stock += qty`. **Additive, never a `SUM(locations)` recompute**, carrying the in-code comment for risk #1 of this document: a recompute would resurrect stock that sales already sold.
- [x] W2a-T4 Record the interim contract in a docblock: sales still bypasses locations, location stock overcounts by everything sold, the ledger holds only `RECEIPT` rows until W3.
- [x] W2a-T5 Tests — unit + integration on `AbstractPostgresIntegrationTest`: the first receipt creates the balance row; a second accumulates; the **resurrection regression test** (a variant whose aggregate is *below* the location sum, simulating a sale, must end at `aggregate + qty`, and the test must fail under a `SUM` recompute); ledger rows are append-only; `UNIQUE (variant, location)` is enforced.
- [x] W2a-T6 Gates (`spotlessCheck spotbugsMain test`) + independent re-verification + work-unit commit.

### W2a results — delivered as commit `e810a48`

10 new files, **+1431 authored lines and 0 deletions** (`git show --stat` is authoritative here; the writer's own per-round figures were unified-diff stats and do not reconcile exactly). Branch `feat/po-receipt-w2a`, not pushed. **Gate evidence on the committed bytes**: `./gradlew spotlessCheck spotbugsMain --no-daemon` — fresh execution, BUILD SUCCESSFUL, 0 SpotBugs findings; `./gradlew test --no-daemon` — fresh execution, **569 suites / 1857 tests / 0 failures / 0 errors / 0 skipped**, i.e. exactly **+10 suites / +17 tests** over the pre-slice baseline **559 / 1840**, with the non-inventory remainder equal to the baseline (so no other component's test surface moved). The last parent edit was a one-line comment citation; `:test` then reported `UP-TO-DATE` **because a comment does not change bytecode**, so the tested bytes are identical to the freshly executed green run. The commit was not hook-verified: `lint-staged` reported “could not find any staged files matching configured tasks” for these Java sources.

**Delivery-budget note**: W2a alone consumed 1431 authored lines against a ~1100–1400 forecast for W2a+W2b combined. The overrun is test volume (845 of the 1431) plus the fix round — not gratuitous scope. The chain strategy already in place absorbs it, nothing was split artificially, and no test or comment was trimmed to fit the heuristic.

**Independent verification ran twice, read-only and adversarial.** Round 1 returned `findings` on the pre-fix bytes: **(F1)** the lock order was decided by a pre-read probe of the balance id, so with an absent row a transaction locked variant-first while a concurrent one that already saw the committed row could lock balance-first — a real deadlock cycle for three concurrent receipts on the same `(variant, location)` — and the probe left the balance entity in the persistence context before the locking select, risking a lost update on `product_variant_locations.stock`; **(F2)** `companyStoreId` was never compared with the locked variant's store, so the ledger could attribute a receipt to the wrong store; **(F3)** a `NULL` aggregate stock would NPE.

**Fixes (by simplification, not by added machinery).** The probe is deleted: there is now one global order, **`variant → balance`**, documented at `InventoryService.java:87-90` as cycle-free because a receipt locks exactly one variant and one balance and a balance row belongs to exactly one variant. `findByIdForUpdate` is the transaction's first database interaction; the conflict-tolerant upsert stays as a cheap defence and because a caught unique violation cannot be recovered inside an already-aborted PostgreSQL transaction. F2 is a plain `IllegalArgumentException` thrown after the variant lock and before any write (`:93-98`) — its HTTP mapping is W2c's, and the 3-hop “location belongs to store” check was correctly **not** added (W2b owns it). F3 is `orZero(...)` (`:139-141`) applied to both rows. The lock-order regression test pins the order with a Mockito `InOrder` **and** a `verify(never())` on the removed probe call, so it fails against the old shape two independent ways.

**Round 2 result**: F1 gone, confirmed against observed PostgreSQL locking (`product_variants … FOR NO KEY UPDATE` → upsert → balance `FOR NO KEY UPDATE` → ledger insert → both updates), with both balance paths (row absent, row existing) executed end to end on real PostgreSQL; F2 scope discipline and F3 confirmed; blast radius exactly the 10 new paths with `git diff` empty; inertness confirmed by grep. Its only remaining candidate-caused finding was low and doc-only (a wrong `SalesOrderService` line citation in a comment), which was corrected to `:803-813` (verified by `grep -n`, since `applyStockChanges` is at `:774`, the sort at `:803` and the lock loop at `:806`).

**Verified facts closed inside W2a**: the resurrection regression test really encodes the interim inconsistency (variant aggregate `40.00`, seeded balance `100.00`, receipt `5.00`, asserting `45.00` = aggregate + qty and `105.00` = location + qty) and fails under any `SUM(locations)` recompute; the additive rule is code, not comment (a grep over the package finds “recompute” only inside comments); the ledger has no production update or delete path; `ddl-auto=validate` plus a Flyway run to `v10` proves the entities match the migration; nothing outside the package calls `applyReceipt`.

**Follow-ups discovered, deliberately NOT fixed in W2a**:

1. `SalesOrderService.java:860-861` calls `variant.getStock().compareTo(...)`/`.subtract(...)` with no null guard, so the same `NULL` stock F3 handles here would NPE on the sales path. Pre-existing, out of W2a scope, owned by W3's sales rework.
2. `InventoryService`'s variant↔store guard throws an untyped `IllegalArgumentException`. **W2c must map it to its HTTP contract**, and must keep the 3-hop location-belongs-to-store check out of the mutator (W2b owns it).
3. Residual condition from verification: “the locking select is the first read of the balance” holds because `applyReceipt` is the transaction root. **W2c must not pre-load the balance row inside the same transaction** before calling it, or the stale-instance hazard returns.
4. No real-PostgreSQL test inserts a variant with `stock = NULL` (the F3 case is unit-level only, and the balance-side null branch is unreachable under `NOT NULL DEFAULT 0`). Recorded, low value.
5. **The native risk assessment could not be produced.** `gentle_review` `{"operation":"assess"}` failed twice with two different native outcomes: `native command returned empty output` over the working tree, then `native response is schema incompatible` over the committed range (`baseRef` `56f76ac`, `committedOnly: true`). The mirrored contract maps that to `unassessable`, treated as **high risk**, whose plan is writer self-verification **plus** a separate independent verifier — exactly what ran. Both failures are unexplained by this repository and are surfaced to the user; no blind retry loop was performed and the `assess` route was not substituted by model judgment.

## W2b — Per-store receiving/sales location settings (no auth)

**Goal**: each store points at its receiving and sales location, validated against the store the location actually belongs to.

- [x] W2b-T1 `V11__store_inventory_settings.sql` (`company_store_id` PK, both location FKs `NOT NULL`, `version`, timestamps).
- [x] W2b-T2 `StoreInventorySettings` entity + repository (with `@Version`) and `StoreInventorySettingsRequest`/`Response`.
- [x] W2b-T3 The 3-hop "location belongs to this store" validation with its own exception. **HTTP class: 404** via a new `StoreLocationNotInStoreException extends ResourceNotFoundException`, applying W1-D4 by analogy (a child referencing an unknown parent → 404; local precedent `StoreZoneIntegrationTest.createZone_UnknownAreaReturns404`).
- [x] W2b-T4 `StoreInventorySettingsService` (get + upsert with validation) with unit tests.
- [x] W2b-T5 `StoreInventorySettingsController` nested under the existing stores path — `@RequestMapping("/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/inventory-settings")` — reusing the store write role set (`ADMIN, COMPANY, COMPANY_COUNTRY, COMPANY_REGION, COMPANY_ZONE, COMPANY_STORE`). **No gateway change**: `/api/companies/**` is already routed and the nested path feeds `currentUserContext.verifyCompanyStoreAccess`. `@PreAuthorize` values must come from `Roles` constants.
- [x] W2b-T6 Store-locations lookup **by store** (the picker's data source, absent today): composed 3-hop repository/service query + `GET /api/companies/.../stores/{storeId}/store-locations`.
- [x] W2b-T7 Tests: unit (validation, upsert, unknown store, foreign-store location), controller, integration (schema and FKs, location-not-in-store rejection, round trip on real PostgreSQL).
- [x] W2b-T8 Gates + work-unit commit.

### W2b results — delivered as commit `4cc7f2d`

16 files (15 new plus `StoreLocationRepository` at `+28/−0`), **+2062 authored lines, 0 deletions**. Branch `feat/po-receipt-w2b`, stacked on `e810a48`, not pushed. **Gate evidence**: `./gradlew test --no-daemon --rerun-tasks` — genuine fresh execution, **584 suites / 1898 tests / 0 failures / 0 errors / 0 skipped**, i.e. **+15 suites / +41 tests** over the W2a baseline 569 / 1857, with the non-inventory remainder exactly 1857 so no other component moved; `./gradlew spotlessCheck spotbugsMain --no-daemon` and the focused inventory run (58 tests) executed fresh and green with 0 SpotBugs findings. Flyway reached `v11 - store inventory settings` on real PostgreSQL **after** the migration's comment was edited, which proves the checksum change is safe (Testcontainers databases are ephemeral and V11 exists in no persistent environment). The commit was again not hook-verified: `lint-staged` reported no matching staged files.

**Two independent verification rounds, read-only and adversarial.** Round 1 returned four low findings, none blocking. **(F1)** The "three-hop proof" only proved one hop: the candidate seeded the chain from the location's own FK associations and then re-queried those same FK values, so only `findByIdAndCompanyStoreId` could ever fail, while the javadoc claimed three independent proofs. **(F4)** One assertion compared two distinct UUID constants and proved nothing. The same round also confirmed the reduction is semantically complete — because `store_locations → store_zones → store_areas` and `store_areas → company_stores` are all `NOT NULL`, "the location's area belongs to store S" is equivalent to "the location belongs to store S", so the guard was correct all along — and confirmed that the executed foreign-store rejection (`rejectsForeignStoreLocation`: 404 in both field orders and `count() == 0`) would fail if the check were removed.

**Fix round.** The guard now carries the single load-bearing predicate at `StoreInventorySettingsService.java:222-232` with an honest javadoc explaining why one check suffices; hops 2–3 and the then-unused `StoreZoneRepository` collaborator were deleted (4 queries → 2 per location, 8 → 4 per upsert). F4's vacuous assertion was removed. **G1 was closed**: `OwnershipValidationTests.rejectedUpdateChangesNothing` PUTs a valid pair, records the version, PUTs a foreign-store location in both field orders (404 each) and asserts the stored pair, the version and `count() == 1` are unchanged, so the update-path "write nothing on rejection" is executed evidence instead of a code-order argument. Round 2 confirmed the guard's strength is unchanged, that `StoreZoneRepository` is fully absent from the package, that `StoreLocationRepository.findByIdAndStoreZoneId` still exists and is still used by `StoreLocationService`, that no test was weakened, and that the totals reconcile exactly (584 − 15 = 569, 1898 − 41 = 1857). Its only remaining finding was another doc-only leftover — the `V11` comment's "three-hop join" wording — fixed before the commit.

**Confirmed inside W2b**: the two typed exceptions mirror `ProductVariantNotFoundException` and extend `ResourceNotFoundException` with no extra annotations; rejection is ordered before the load and any save; every `@PreAuthorize` uses `Roles` constants, and the observed role sets match the neighbouring nested store endpoints (GET settings and GET store-locations: the six write roles plus `lc-company-store-read`; PUT: the six write roles); the appended JPQL is store-scoped, filters `location.enabled = true` and is totally ordered by `(area.displayOrder, area.areaCode, zone.displayOrder, zone.zoneCode, location.displayOrder, location.locationCode)`; `Auditable` contributes only `created_at`/`updated_at`, so the assigned `@Id` on `company_store_id` does not conflict; optimistic locking is real (`upsertSettings` loads the row and mutates it in place, and the version genuinely advances).

## W2c — The receipt document, the use case and the receiving role

**Branch**: `feat/po-receipt-w2c`, stacked on `4cc7f2d` (W2b) — the slice depends on both W2a's `applyReceipt` and W2b's settings table, so the stack is forced, not chosen. **Forecast**: ~1,600–2,100 authored lines including tests, i.e. larger than W2b. **Risk: high** (new auth surface) — the security review is a merge gate, not an advisory.

**New package `com.lifecontrol.api.goodsreceipt/**`** (`model`, `repository`, `dto`, `exception`, `service`, `controller`), mirroring the module's package-per-aggregate layout (`purchaseorder`, `salesorder`, `inventory`).

**Documented lock order for the slice: `purchaseOrder → variant → balance`.** The receipt locks the purchase order first and keeps it for the whole transaction; `applyReceipt`'s `variant → balance` order (W2a) then nests inside it. No existing path locks a variant before a purchase order, so the order is cycle-free — the code must carry this comment.

**W2c-T1 — `V12__goods_receipts.sql`** (+ `V12` owns the new status family)
- `goods_receipts`: `id`, `receipt_number VARCHAR(30) NOT NULL UNIQUE`, `purchase_order_id` FK, `company_store_id` FK, `receiving_location_id` FK, `status_id` FK, `received_by VARCHAR(255)`, `received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`, `comments VARCHAR(500)`, `enabled BOOLEAN NOT NULL DEFAULT true`, timestamps.
- `goods_receipt_items`: `id`, `goods_receipt_id` FK, `purchase_order_detail_id` FK, **`product_variant_id NOT NULL`** FK, `quantity_received DECIMAL(12,2) NOT NULL CHECK (quantity_received > 0)`, `comments VARCHAR(500)`, timestamps (the entity extends `Auditable`, so the columns must exist).
- Indexes the original plan omitted: `goods_receipts(purchase_order_id)`, `(company_store_id)`, `(receiving_location_id)`, `(status_id)`, `(received_at)`; `goods_receipt_items(goods_receipt_id)`, `(purchase_order_detail_id)`, `(product_variant_id)`. Unnamed FKs, `gen_random_uuid()`, no `IF NOT EXISTS` — `V7`/`V10`/`V11` style.
- **New `status_types` row `GOODS_RECEIPT` + one `statuses` row `Registered`**, using V3's idempotent `INSERT … SELECT … WHERE NOT EXISTS (LOWER(…))` pattern. V3 is frozen (Flyway checksum) so the family is seeded here, not appended there.
- **W2c-T2 — entities + repositories + DTOs**: `GoodsReceipt`/`GoodsReceiptItem` entities; `GoodsReceiptRepository` (+ the PO-scoped receipt **count** used to build the number, and the paged/search listing), `GoodsReceiptItemRepository`; request/response records including the line list. **The typed exceptions deliberately land with their consumers in C2/C3, not in C1** — C1 has no code that throws them, so shipping them here would be dead code added only to satisfy a planning line. **Also adds `PurchaseOrderRepository.findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`)** mirroring `ProductVariantRepository.findByIdForUpdate` — W2-D4's lock **does not exist today** (verified: no `@Lock` on `PurchaseOrderRepository`). The finder is deliberately **not** filtered by `enabled`, unlike the variant precedent: a disabled order must fail with a precise message from the use case, not be disguised as a missing row.
- **W2c-T3 — the reception use case** `GoodsReceiptService.createReceipt(...)`, `@Transactional`, one transaction, no `AFTER_COMMIT` listener. Order is part of the contract:
  1. **First DB interaction is the purchase-order lock** (`findByIdForUpdate` → `PurchaseOrderNotFoundException`).
  2. `purchaseOrderService.requireReceivable(po)` (W1-5: `Accepted`/`In Transit`; a `public` method, verified `:413`).
  3. Resolve the receiving location: operator override, else the store's `store_inventory_settings.receiving_location_id` (`StoreInventorySettingsNotFoundException` when unset). Validate it **belongs to the PO's store** (reusing `StoreLocationNotInStoreException` → 404) **and `enabled = true`** — this closes W2b's debt #1, where a decommissioned leaf could be configured and then received into.
  4. **Validate the whole request before any write**: the order must be `enabled` (the lock deliberately does not filter it, so the failure is a precise message rather than a confusing not-found); non-empty lines; no duplicate `purchaseOrderDetailId`; each detail belongs to the PO, is enabled, and has a **non-null `productVariant`** — the W1b legacy `NULL`-variant line must **fail closed naming the line**, never silently skip the inventory effect (recon fact 8); the **variant itself is enabled**; the **detail's status can still reach the receiving state** (`Partial Received`); `quantityReceived` whole and `> 0` (W2-D7); `existingReceived + this ≤ ordered`; and the variant belongs to the PO's store, failing with a **typed 4xx before the mutator runs** — never letting the mutator's bare `IllegalArgumentException` be the error the operator sees (W2b debt #4).

  **The last two rules were added after independent verification of C2/C3 (finding F1).** The first implementation validated the detail's `enabled`, the variant's *store* and the *quantity*, but not that the **variant is enabled** — and `ProductVariantRepository.findByIdForUpdate` filters `enabled = true`, so a variant disabled after the order was raised passed the entire validation phase, the receipt was inserted, and only then `applyReceipt` threw `ProductVariantNotFoundException` (404) and rolled everything back. That broke the "no write precedes validation" invariant in the letter and reproduced exactly the symptom W2b debt #4 set out to remove: the operator saw the *mutator's* error. **The same class of defect has a second reachable instance my own review found while fixing the first**: a line whose detail sits in a terminal status (`Cancelled`/`Rejected`) passes the amount check but `registerReceivedQuantity` rejects it via `isDetailStatusReachable` — *after* the receipt and the inventory effect were written. Both are now pre-validated. The transition question is answered by **W1's own rule, not a duplicate map**: `PurchaseOrderService` gains a public detail-level guard mirroring the existing public `requireReceivable(PurchaseOrder)` (`:413`), so the receipt reuses the single source of truth for `DETAIL_TRANSITIONS` (`:75-83`) instead of copying it. **The guard's predicate needed a second correction, found by the bounded re-verification after the fix round**: the first version asked whether the detail could reach *either* derived target (`Partial Received` **or** `Received`), which is wrong because `isDetailStatusReachable` is a **reflexive** BFS (`:617-635` starts with `fromStatus` already visited), so `Received → Received` matched and the guard admitted a **terminal** line — while `registerReceivedQuantity` derives the target from the accumulated quantity and would still throw `InvalidStatusTransitionException` on a partial amount, *after* the writes. Requiring reachability to the **receiving** state alone is the exact predicate: it admits `Pending`, `In Process`, `In Transit` and `Partial Received` (which can all reach both targets) and rejects `Received`, `Rejected` and `Cancelled`, so the guard's accept-set equals the mutator's accept-set without needing the quantity. **Consequence worth stating: after these rules no reachable input failure remains after the write phase**, so the transaction's rollback is defence-in-depth rather than a coverage gap — which is a stronger outcome than testing rollback, and the reason the rollback-plus-side-effect-assertions test cannot be written for a reachable case.
  5. Generate `receipt_number` **inside the lock** (W2-D8) from the receipt **count** for that order (not a string `max`), zero-padded, failing closed above 30 chars.
  6. Persist the receipt (status `GOODS_RECEIPT`/`Registered` via `findByTypeNameAndStatusName`) and its items.
  7. Per line: `inventoryService.applyReceipt(variantId, companyStoreId, receivingLocationId, quantity, "GOODS_RECEIPT", receipt.id, username)` then `purchaseOrderService.registerReceivedQuantity(poId, detailId, accumulated)`. **Reusing W1's method is mandatory, not a preference**: risk #3 of this document and W1-D2 removed the manual detail-status writer, so W2 must not reintroduce a second one. Passing the **accumulated total** (the method assigns, it does not add — verified `:480`) and letting it derive `Partial Received`/`Received` keeps one writer. **Never pre-load the balance row in this transaction** (W2b debt #5) — `applyReceipt` must be the first reader of it.
  8. Return the response. **The receipt is immutable**: no update, no delete.
- **W2c-T4 — authorization + endpoints**: `GoodsReceiptController` at `/api/goods-receipts` with `POST` (201), `GET` (paged `ResponseEntity<Page<GoodsReceiptResponse>>`, `@PageableDefault(size = 12)`, `search`) and `GET /{id}`, `@PreAuthorize` built from `Roles` constants, plus `@Tag`/`@Operation`/`@ApiResponse` annotations. **Reads are tenant-scoped (W2-D10)**: the list filters non-admins to their `company_store_ids` and `GET /{id}` verifies the receipt's own store, while admins stay unscoped. In the first commit the endpoint set accepts only the existing store roles, so the endpoint is coherent on its own; **C4 then adds `Roles.RECEIVING` / `ScopeLevel.STORE` and extends this same `@PreAuthorize` set** — that ordering is deliberate, because it keeps the whole authorization diff in one reviewable commit with no endpoint bulk in the way.
- **W2c-T5 — provisioning, docs, gateway**: `lc-receiving` added to the client-roles loop in `docker/scripts/keycloak-setup.sh` (client roles, not realm roles); `life-control-api/AGENTS.md` role table + endpoint map + Keycloak required-client-roles list; `api-gateway/.../routes/Routes.java` gains one `.route(RequestPredicates.path("/api/goods-receipts/**"), HandlerFunctions.http(props.lifeControlApiUri()))`.
- **W2c-T6 — tests**, kept with the unit they verify: unit (the full validation matrix — non-integral quantity, over-receipt, duplicate line, foreign detail, disabled location, location-not-in-store, `NULL`-variant line, the 30-char number guard, the accumulated-total handoff); controller (`GoodsReceiptControllerTest` standalone MockMvc) and a security test pinning the role set (`lc-receiving` passes, an unrelated role 403); integration on `AbstractPostgresIntegrationTest` (schema/FKs, Flyway to `v12`, round trip on real PostgreSQL, two receipts accumulating and deriving `Partial Received`→`Received`, the over-receipt rejection rolling back **both** the inventory and the status write, and the ledger row carrying `reference_type='GOODS_RECEIPT'` + `reference_id = receipt.id`).
- **W2c-T7 — gates + verification**: fresh `./gradlew spotlessCheck spotbugsMain test --no-daemon --rerun-tasks`; independent `gentle-ai-verify`; and the **mandatory security review** of the `ScopeLevel` change, the `@PreAuthorize` set and the claim-provisioning requirement.

### W2c commit plan (5 reviewable work units)

| # | Commit | Contains |
| --- | --- | --- |
| C1 | `feat(purchases): add the goods receipt schema, entities and repositories` | T1, T2 — committed `eace8f0` |
| C2+C3 | `feat(purchases): register goods receipts and expose the API` | T3 + T4, their exceptions and all tests |
| C4 | `feat(auth): add the lc-receiving store-scoped role` | `Roles`, `ScopeLevel`, the controller's `@PreAuthorize` set + its test |
| C5 | `feat(gateway): route the goods receipt API` | `Routes.java` + `keycloak-setup.sh` + `AGENTS.md` — **CORRECTION (2026-09-20, verified with `git show --name-status`): the plan's file attribution was wrong. `162507e` is `Routes.java` only (1 file, +1). `keycloak-setup.sh` and `AGENTS.md` landed in C4 (`edfb946`, 8 files, +135/−10), and `AGENTS.md` again in C4b.** The intent — the whole authorization diff, provisioning included, readable in C4/C4b with nothing unrelated in the way — is better served by what actually shipped than by the plan. |

**The planned C2/C3 split was deliberately merged into one commit.** The boundary carried no security significance (the auth work is C4/C5, which stay separate), the API layer is a thin 103-line controller, and the uncommitted work units shared one artifact — the integration test file legitimately contains both the reception-case tests and the HTTP round trip — so splitting them would have required hunk-level staging to manufacture an artificially clean boundary, or would have produced an intermediate commit that does not build. The merged commit is read as two logical halves by file (service and its exceptions, then controller and its security tests).

**C4 and C5 are separated on purpose**: the security review must be able to read the whole auth diff — the role constant, the `ScopeLevel` inclusion, the controller's extended `@PreAuthorize` set and the Keycloak provisioning — without anything unrelated in the way.

### W2c results — delivered as five commits on `feat/po-receipt-w2c`

`eace8f0` (C1: `V12`, entities, repositories, DTOs, the PO lock finder — 11 files, +1143; the plan's "10 files / ~1,075" was an estimate, the real figure is 11 / +1143) → `692f595` (C2+C3: the reception use case, its exception family, the controller, the tenant-scoped reads and the whole test surface — 22 files, +3082/−1) → `edfb946` (C4: the `lc-receiving` role **plus** the Keycloak client role and the `AGENTS.md` tables — 8 files, +135/−10) → `407db1b` (C4b: the security review's required changes + the guard test — 5 files, +246/−30) → `162507e` (C5: the gateway route — 1 file, +1). **Per-commit figures are `git show --shortstat` on the committed bytes, not the writer's unified-diff estimates.** **Pushed and open as PR #121, stacked on #120 (W2b) ← #119 (W2a) ← `main`** — see the status line for the merge order.

**Gate evidence on the final tree**: `./gradlew test --no-daemon --rerun-tasks` fresh → **608 suites / 1990 tests / 0 failures / 0 errors / 0 skipped** (from `585 / 1906` at HEAD before W2c: **+23 suites / +84 tests**, all of them in `goodsreceipt`, nothing else moved); `spotlessCheck` clean and `spotbugsMain` with **0** findings on a forced re-run; `bash -n docker/scripts/keycloak-setup.sh` OK; the gateway compiles and its own tests pass (it has no CI, and its `gradlew` is gitignored).

**Review workload, recorded honestly**: five commits and roughly 4,500 authored lines, of which the reception use case and its tests are the bulk. It is an order of magnitude over the ~400-line advisory, mitigated only by the commit split. The chain strategy was chosen for exactly this, but a reviewer should read it slice by slice, not as one diff.

### What verification actually caught (five defects, four independent verifications)

Every work unit was independently verified before its commit, and **every verification found something real**. This list is the value of that discipline, not a confession:

1. **`received_at` was unreachable through JPA** (C1). The DDL declares `DEFAULT CURRENT_TIMESTAMP`, but Hibernate always names the column in the `INSERT`, so an unset field sends an explicit `NULL` and violates `NOT NULL`. The DDL default was a decoy and no test covered it because the fixture set the value. Fixed with an entity `@PrePersist` callback **deliberately not named `onCreate`** — `Auditable` already declares that method, so the natural name would have *overridden* it and silently stopped `created_at`/`updated_at` from being populated. The regression test fails both ways, and both failures were demonstrated: `null value in column "received_at"` and then `null value in column "created_at"`.
2. **F1 — a write preceded a collaborator rejection.** A disabled product variant passed the entire validation phase because only the detail's `enabled` and the variant's *store* were checked; `InventoryService.applyReceipt` locks with `enabled = true`, so the receipt row was written and only then did the mutator answer 404. That reproduced the exact symptom W2b debt #4 was meant to remove. Fixed pre-write.
3. **F2 (first instance) — an unreceivable detail status was rejected after the write.** A line in a terminal `Cancelled`/`Rejected` state passed the amount check and was rejected by `registerReceivedQuantity` afterwards (409). Fixed pre-write by reusing W1's own transition table through a new public guard, rather than copying `DETAIL_TRANSITIONS`.
4. **F2 (second instance, found by the bounded re-verification of that very fix) — the guard admitted a terminal line.** `isDetailStatusReachable` is a **reflexive** BFS, so `Received → Received` is trivially true and the guard's "reachable to `Partial Received` **or** `Received`" predicate let a `Received` line with quantity outstanding through; the mutator then derived `Partial Received` and threw 409 after the writes. The correct predicate is reachability to the **receiving** state alone, which admits exactly the statuses that can also reach both derived targets. Demonstrated: the old predicate answered `Status expected:<400> but was:<409>`.
5. **The store-scoped list query returned 500 on every request without a `search` term.** This was found only because the verification refused to accept "the query is never executed against PostgreSQL" as a nit: running it for real produced `function lower(bytea) does not exist`, from the null-bound `:search IS NULL` predicate that the repository already warns about elsewhere (`ActivityLogSpecifications`) and still carries in `ProductSupplierRepository`. Fixed by splitting into two plain finders selected by `hasText`, which is the shape the order listing already uses — the idiom is gone rather than cast around.

Plus the **security review** of C4, which found the routing-narrowing defect now fixed in C4b: adding `lc-receiving` to `ScopeLevel.STORE` sent a principal that *also* held a broader role down the store branch, silently narrowing it to its claimed stores — the opposite of what the method's own javadoc promised and of what its four sibling methods do.

### Follow-ups found and deliberately NOT fixed in this slice

Recorded so they cannot evaporate. None of them blocks W2c, and each would widen this slice into code it does not own:

1. **Cross-tenant purchase-order reads (severity: high, pre-existing).** `GET /api/purchase-orders`, `/{id}` and `/{id}/details` gate on `isAuthenticated()` only (`PurchaseOrderController:50,61,143`) and `PurchaseOrderService.getAllPurchaseOrders:120-130` applies no tenant predicate, so **any authenticated caller — including a `lc-receiving`-only principal — reads every tenant's orders and lines.** Introduced by `74740e0` and documented as-is in `AGENTS.md:199`. The receipt reads were deliberately scoped (W2-D10) instead of inheriting this. Own change.
2. **`CompanyStoreService:96/:127` encode "store-scoped" as literal role names** rather than asking `ScopeLevel.STORE`. Unreachable today behind the store-administration gates, and a new test (`ReceivingRoleSecurityGuardTest`) pins those denials so the divergence cannot be armed unnoticed. Re-expressing both predicates in scope terms is the remediation; it belongs with the store module.
3. **`ProductSupplierRepository.findBySupplierIdWithSearch` carries the same nullable-parameter idiom** that produced the 500 above. Worth the same two-finder treatment before something calls it with a null search.
4. **A foreign-store receipt read answers 403 where the house convention masks denial as 404** (`StoreLocationService`, `StoreAreaService`, `StoreZoneService` all mask, and `StoreAreaServiceTest` asserts the equivalence). This discloses only that the UUID is a real receipt; the body is a generic `Access denied`. Decide and record rather than drift.
5. **`/api/goods-receipts/**` relies on method-level `@PreAuthorize` alone**; `SecurityConfig` adds only `.authenticated()`, unlike `/api/users-admin/**` which also has a URL-level rule. Defence in depth, not a hole.
6. **W2b debt #3 is still open**: an optimistic-lock conflict answers 500 because `GlobalExceptionHandler` has no `ObjectOptimisticLockingFailureException` handler (pre-existing since `V8`, affects the whole store tree).
7. **`spotbugsTest` is permanently disabled** (`build.gradle:60-62`) and the only report on disk predates this work, so test sources are effectively unanalysed.
8. **The gateway has no CI and its `gradlew` is not versioned**, so the C5 route is compile-verified here and unguarded thereafter.

### Security review status (a substituted, not native, gate)

The feature's own control requires a security review before the receiving role merges. The provider-owned risk reviewer (R1) is **not dispatchable in this session**: `gentle-ai review mode status` reports `receipt-driven development: off`, `global: unset`, `clone-local: unset`, so the native review lifecycle — and with it the controller-owned lens dispatch — is never entered. `gentle_review assess` is separately and reproducibly broken here (`native response is schema incompatible`). The gate was therefore satisfied with a **dedicated security-focused independent verification** over the committed `edfb946`, which produced a verdict of "safe with required changes", a full blast-radius enumeration of the 30 store-namespace endpoints, and the F2 finding now fixed. That is an equivalent bar, and it is **not** the native provider-owned review; enabling the clone-scoped switch would add it over `edfb946`/`407db1b`.

**Deployment prerequisite, not verifiable from this repository**: the `company_*` claims are provisioned by no file in the tree, so granting `lc-receiving` without them yields 403 on every request. Recorded prominently in `life-control-api/AGENTS.md`; the failure mode is fail-closed.

### W2c scope boundaries (explicit)

- **Not** in W2c: any receipt update/cancel/reversal path; the `SALE`/`ADJUSTMENT` movements; location stock as sellable availability; the Angular UI (W2d); the `ObjectOptimisticLockingFailureException`→409 handler (W2b debt #3, platform-wide and pre-existing since `V8`, still its own small change).

### Debts W2b hands to W2c / W2d (recorded so they cannot evaporate)

1. **`enabled` is not validated on the settings write.** `upsertSettings` accepts a disabled store location as the receiving or sales location, while the picker only offers enabled ones (`findEnabledByCompanyStoreId`). A decommissioned leaf can therefore be configured and W2c would receive into it. Deliberate deferral, owned by W2c — **closed by W2c-T3 step 3**, which requires the resolved location to be `enabled` before any write.
2. **No `version` reaches the client, anywhere in the store tree.** `StoreInventorySettingsResponse` carries no version — and **no** DTO under `store/dto/` exposes one, so adding it to this endpoint alone would introduce a new pattern. Decide the store-tree-wide concurrency-token contract deliberately (W2d) rather than patching this endpoint.
3. **An optimistic-lock conflict answers 500, not 409.** `GlobalExceptionHandler` has no `ObjectOptimisticLockingFailureException` handler, so the conflict falls to the catch-all. This is pre-existing platform debt introduced with `V8__store_optimistic_locking.sql` and it affects every store-tree entity, not just the settings row. Worth its own small change plus the matching handler test; the 409s that exist in the store integration tests are duplicate-key conflicts, not lock conflicts. **Still out of scope in W2c** (see its scope boundaries).
4. **`InventoryService`'s variant↔store guard throws an untyped `IllegalArgumentException`.** **W2c-T3 step 4 closes this by validating the variant's store *before* the mutator runs**, so the operator never sees the mutator's bare `IllegalArgumentException` (which the handler already maps to 400 — `GlobalExceptionHandler.java:69-71`) and the failure is typed and names the offending line.
5. **Do not pre-load the balance row inside the receipt transaction** before calling `applyReceipt`, or the stale-instance hazard that W2a's verification closed comes back. **W2c-T3 step 7 keeps the rule as part of the documented step order.**

## W2 delivery record (2026-09-20, user-authorized push + PR)

The three slices are on the remote and open as a stacked chain. What was done, in order, and what was verified:

| Step | Result |
| --- | --- |
| Preflight | `main` == `origin/main` @ `56f76ac`; the three tips unchanged from their gate-verified bytes (`e810a48` / `4cc7f2d` / `162507e`); tree clean; **no** `po-receipt` branch and **no** open PR on the remote before the push |
| Push | `feat/po-receipt-w2a`, `-w2b`, `-w2c` pushed with upstream tracking; three new remote branches |
| Cleanup | Remote and local branches deleted **after** verifying each tip is reachable from `main`: `git branch -d` (not `-D`, so an unmerged tip would have refused), `git push origin --delete`, `git remote prune origin`. `origin` now carries `main` only. All seven W2 commits remain reachable through their merge commits (`git merge-base --is-ancestor` per commit). **Left untouched on purpose**: `chore/project-conventions-skill` (`cec4578`, unpushed — deleting it destroys the only copy) and `fix/docker-artifact-provenance` (`b30c8d3`, another session's worktree) |
| PR chain | **#119** → `main`; **#120** → `feat/po-receipt-w2a`; **#121** → `feat/po-receipt-w2b` |
| Retarget | **`gh api -X PATCH repos/le03nava/LifeControl/pulls/<n> -f base=main`** used for #120 and #121 — `gh pr edit <n> --base` failed on this machine **under gh 2.45.0**, the version installed at the time; after the 2026-09-23 upgrade to gh 2.101.0 the native command works again and this workaround is no longer required (the record is left as executed). After each retarget the diff was re-checked and stayed exactly the slice's own size, because the merged parent commit is already an ancestor of `main`, so **no rebase was needed** |
| Merges | #119 → **`62184b4`**, #120 → **`55cfab3`**, #121 → **`cd60b2f`** (merge commits, the repo convention; no branch protection on `main`) |
| Byte-identity | After **each** merge, `origin/main^{tree}` was compared against the corresponding gate-verified tip tree: `49ee03b…` = `e810a48^{tree}`, `0a477f2…` = `4cc7f2d^{tree}`, `d52e68c…` = `162507e^{tree}`. **All three match**, so the merged content is the reviewed content, not a recomposition |
| Post-merge CI | `main` @ `cd60b2f`: **API CI success**, **Angular CI success**. The intermediate runs for `62184b4` and `55cfab3` show as **`cancelled`** — the workflows share `concurrency: cancel-in-progress` per ref, so each new push to `main` cancels its predecessor. Expected, not a failure |
| Diff hygiene | Verified per PR against GitHub: 10 / +1431, 16 / +2062, 39 / +4580−14 — each PR contains **only** its own slice, no polluted diff |
| CI | `api-ci` runs on every PR (`pull_request` with no base-branch filter, so stacked PRs are tested; paths `life-control-api/**`). **Angular CI does not run** — no slice touches `life-control-app-angular/**`. The gateway (`api-gateway/**`) and `docker/**` are outside both workflows' path filters, so PR #121's route line and provisioning script are compile/`bash -n` verified locally only |

**Why the branches are stacked rather than three PRs to `main`**: W2b consumes W2a's mutator and W2c consumes both, so a base of `main` would show each reviewer the whole prefix. This mirrors the W1 procedure exactly (PR #118 targeted `feat/po-receipt-w1a`, then was retargeted to `main` after #117 merged) — one chain strategy, not a mix.

**Merge procedure for the chain** (executed 2026-09-20 in this order; the same recipe applies to any future stacked chain): merge #119 → **retarget #120 to `main` via REST** — **`gh pr edit <n> --base main` FAILED on this machine under gh 2.45.0** (`GraphQL: Projects (classic) is being deprecated … repository.pullRequest.projectCards`); use `gh api -X PATCH repos/le03nava/LifeControl/pulls/<n> -f base=main` (**version note, added 2026-09-23: that failure was the stale gh 2.45.0 from Ubuntu noble, not the repository. After the upgrade to gh 2.101.0, installed from the official GitHub CLI apt repo, `gh pr edit --base` and `gh pr edit --body` work again and no REST workaround is needed. The steps below are preserved as executed on 2026-09-20**) → re-verify the diff is still only the slice → merge #120 → retarget #121 the same way → merge. **No rebase was required at any step**, because a merged parent commit is an ancestor of `main`, which is exactly what keeps the child's diff clean. Do not delete a parent branch before retargeting its child. `delete_branch_on_merge=false`, so branches are deleted explicitly — **executed after the three merges**, with `-d` rather than `-D` as the safety net.

**Flag carried in PR #121's body, repeated here because it is a merge gate that survived the merge**: the workstream's mandatory security review for the `lc-receiving` role was satisfied by a **substituted security-focused independent verification**, not by the native provider-owned review (not dispatchable while the switch is off). The role is therefore **live on `main` with the substituted gate only**. Enabling the clone-scoped switch would still add the native review over `edfb946`/`407db1b`, and that remediation is now post-merge rather than pre-merge.

## W2d — Angular UI (W2d1 + W2d2 merged; W2d3 delivered, unpushed — 2026-09-20)

### W2d recon (read-only, 2026-09-20) — facts that bound the slice

Everything below was verified against the tree, not assumed:

1. **The frontend has no goods-receipt code at all.** `src/features/purchases/receipts/pages/receipts-placeholder/receipts-placeholder.ts` is a "Coming Soon" inline card; `purchases-admin.component.ts:31-36` has the Receipts card with `route: null, disabled: true`; no `goods-receipt*` symbol exists anywhere under `life-control-app-angular/src`.
2. **No store-level location listing exists on the frontend.** `StoreLocationService.getStoreLocations(...)` (`features/companies/stores/data/store-location.service.ts:39`) needs the full 7-level chain down to a **store zone**, and its only consumer is `StoreLocationsPage`. W2b added exactly the missing endpoint for this picker: `GET /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/store-locations` → `List<StoreLocationSummaryResponse>` (`store-locations` is a **direct child of `stores`** here, not of a store zone). Nothing consumes it yet.
3. **No settings screen and no upsert-on-404 pattern exist.** `grep -i "settings|upsert"` over `src/**/*.ts` matches one icon literal. W2d2 introduces the first one.
4. **No optimistic-concurrency or `version` handling exists anywhere in the app** — no `version` field on any interface, no `If-Match`/ETag, no conditional-header interceptor. `StoreInventorySettingsResponse(companyStoreId, receivingLocationId, salesLocationId)` carries no version, so W2b debt #2 cannot be closed client-side alone.
5. **The purchase-order contract already carries what the receipt UI needs**: `PurchaseOrder.companyId/companyCountryId/regionId/zoneId/companyStoreId` (the full chain for the store-locations call) and `PurchaseOrderDetail.{id, productVariantId, productVariantName, quantity, receivedQuantity, statusId, statusName}`. The order/detail status **families have different names**: the order family is `Draft…Closed, Rejected` (the frontend's `status-config.ts`), while the detail family is `Pending, In Process, In Transit, Partial Received, Received, Rejected, Cancelled` (`V3__seed_reference_data.sql:187-215`, consumed by `PurchaseOrderService.DETAIL_TRANSITIONS:76-82`). `StatusChip` falls back to the raw English name for an unknown status, so a line chip needs its own label/colour map or it renders `Partial Received` untranslated.
6. **`DetailTableRow` (`components/detail-table/detail-table.ts:24-39`) deliberately drops `receivedQuantity`/`statusId`/`statusName`**, and `purchase-order-edit.ts`'s `populateLineItems` maps `PurchaseOrderDetail → DetailTableRow` without them. A reception-progress column therefore needs the row type extended **and** the mapping completed.
7. **`POST /api/goods-receipts` resolves the store's configured receiving location when `receivingLocationId` is null** (`GoodsReceiptRequest` docblock; `StoreInventorySettingsNotFoundException` when unconfigured). So the create form can stand alone without the settings screen — but a null location on an unconfigured store fails with a 404 the UI must explain, pointing at the settings screen.
8. **`purchases.routes.ts` gates the whole area on `['lc-admin']` with `clientId`**, and `purchases-admin.component.ts` cards carry no `requiredRoles` (unlike the Companies dashboard). A receiving-only user therefore cannot reach any of this today.
9. **`e2e/mocks/api.ts` implements only `/api/companies` and `/api/countries`; every other path 404s**, and there are **no purchases e2e specs**. The first purchases e2e requires extending that mock (incl. `/api/status-types`, `/api/statuses`, `/api/purchase-orders/**`, `/api/goods-receipts**`, `…/inventory-settings`, `…/store-locations`).
10. **The app's gate is `npm run lint` + `npm run test:coverage:check` + `npm run test:e2e`**; coverage floors live in `scripts/check-coverage.mjs` (80/60/75/80 statements/branches/functions/lines) against `coverage/coverage-summary.json`.

### W2d decisions (resolved with the user, 2026-09-20)

| # | Decision | Locked choice |
| --- | --- | --- |
| **W2-D11** | Delivery shape | **Two slices**, superseding the "W2d is one slice" reading of W2-D1: **W2d1** = receipts UI (list, create, detail, dashboard card, PO-detail entry point, reception columns in `DetailTable`); **W2d2** = the store inventory-settings screen. Chained branches, one reviewable PR each. Rationale: two independent surfaces, and one PR would clear the 400-line review guardrail on its own. |
| **W2-D12** | Receipt-creation entry point | **Both**: the receipts list has "Nuevo recibo" with a receivable-order picker, and the purchase-order edit page adds a "Recibir mercadería" action that navigates to the **same** create component with the order preselected (`?purchaseOrderId=`). One component, two doors. |
| **W2-D13** | W2b debt #2 (`version`) and #3 (500 on optimistic lock) | **Deferred, recorded as its own follow-up slice** (backend `version` on the settings response/request + `ObjectOptimisticLockingFailureException` → 409 platform-wide + the conflict UX). W2d stays **frontend-only**, as planned; one slice, one area. The debt is *not* closed and *not* silently dropped. |
| **W2-D14** | The `lc-receiving` routing guard | **Its own slice (W2d3) with its own security review**, following the W2c precedent of separating auth from feature work. W2d1 and W2d2 are built and tested under `lc-admin`, touching no security routing. Consequence, stated plainly: until W2d3 lands, the new UI is **admin-only** and `lc-receiving` stays API-only. |

### W2d1 — Receipts UI (branch `feat/po-receipt-w2d1`)

| Task | Scope | Allowed edit surfaces (source) |
| --- | --- | --- |
| **W2d1-T1** | `models/receipt.models.ts` (new): `GoodsReceipt`, `GoodsReceiptLine`, `GoodsReceiptRequest`, `GoodsReceiptLineRequest`, `StoreLocationSummary` — field-for-field against `GoodsReceiptResponse`/`StoreLocationSummaryResponse`, plus `receipt.models.spec.ts` only if it earns its keep (a pure-interface file has no runtime behaviour to assert; see T10). | `life-control-app-angular/src/features/purchases/receipts/models/**` |
| **W2d1-T2** | `data/goods-receipt.service.ts` (new) + spec: `getReceipts(page, size, search?)`, `getReceipt(id)`, `createReceipt(request)` on `${apiUrl}/goods-receipts`; Observable-only variant (no local list state), mirroring `PurchaseOrderService`. | `.../receipts/data/**` |
| **W2d1-T3** | `features/inventory/` (new feature folder): `models/store-location-summary.models.ts` + `data/store-location-lookup.service.ts` for the **store-level** listing added by W2b (`…/stores/{storeId}/store-locations`), + spec. It lives in `inventory`, not `purchases`, because W2d2's settings screen consumes it too and the endpoint is inventory's. | `life-control-app-angular/src/features/inventory/**` |
| **W2d1-T4** | Extend `purchases/purchase-orders/data/status-config.ts` with the **detail** status family (`PO_DETAIL_STATUS_LABELS`, `PO_DETAIL_STATUS_COLORS`, and `isDetailStatusTerminal`/receivable helper) + spec assertions. Additive; the order family maps stay untouched. | `.../purchase-orders/data/status-config.ts`, `.../status-config.spec.ts` |
| **W2d1-T5** | `receipt-list` page replacing the placeholder: `rxResource` list with the list-page conventions (12/page, 300 ms debounced search, skeleton/error/empty states, paginator) + "Nuevo recibo". Columns: receipt number, order number, received at, received by, status chip, actions. | `.../receipts/pages/receipt-list/**`, delete `.../receipts/pages/receipts-placeholder/**` |
| **W2d1-T6** | `receipt-create` page: order picker (paged + search) → `getPurchaseOrder(id)` → receivable lines (pending = `quantity - receivedQuantity`, detail status receivable) with per-line quantity defaulted to pending; lines left at 0 are excluded; `receivingLocationId` optional override fed by T3's lookup with the store chain taken from the order; comments; submit → `createReceipt` → navigate to the receipt detail with a success toast; server-error mapping (400 with per-line message, 403, 404 unconfigured location → pointer to the settings screen, 409 order not receivable). Reads `?purchaseOrderId=` for the PO-detail entry door. | `.../receipts/pages/receipt-create/**` |
| **W2d1-T7** | `receipt-detail` page (read-only document view: header + lines). **Deliberate addition beyond the original T19 text**, recorded as such: `GET /api/goods-receipts/{id}` exists and without it the list row dead-ends and the create flow cannot be verified by the operator. | `.../receipts/pages/receipt-detail/**` |
| **W2d1-T8** | Wiring: `purchases.routes.ts` gains `receipts`, `receipts/create`, `receipts/:id` (the placeholder import is removed); `purchases-admin.component.ts` enables the Receipts card; `purchase-order-edit` gains the "Recibir mercadería" action (`html` + handler) gated to a receivable order. | `.../purchases.routes.ts`, `.../pages/purchases-admin/**`, `.../purchase-orders/pages/purchase-order-edit/**` |
| **W2d1-T9** | `DetailTable` reception progress: `showReceiptProgress` input (default `false`, so the draft editor is untouched), `receivedQuantity`/`statusName` added to `DetailTableRow`, a "Recibido" column and a detail-status chip using T4's maps; `purchase-order-edit.populateLineItems` completes the mapping and passes `showReceiptProgress` for a loaded order. | `.../components/detail-table/**`, `.../purchase-orders/pages/purchase-order-edit/**` |
| **W2d1-T10** | Tests: service spec (T2, T3), list/create/detail page specs, detail-table additions, status-config additions, purchase-order-edit additions. No spec for a pure interface file. | co-located `*.spec.ts` under the surfaces above |
| **W2d1-T11** | First Playwright purchases spec + the mock extension it needs (statuses, purchase orders, goods receipts, inventory settings, store-locations) — one happy path: admin → Purchases → Receipts → Nuevo recibo → pick order → receive a pending line → registered receipt visible in the list. | `life-control-app-angular/e2e/**` |
| **W2d1-T12** | Docs: `life-control-app-angular/AGENTS.md` — the `inventory` feature folder, the `lc-receiving`-not-yet-wired status, and the corrected `STORE_ROLES`/purchases-gate drift this recon found. | `life-control-app-angular/AGENTS.md` |

### W2d1 results — 14 commits on `feat/po-receipt-w2d1` (rebased onto `main` @ `3e451fe`)

| Work unit | Commit (post-rebase) |
| --- | --- |
| receipts data layer (models + `GoodsReceiptService`) | `5d8a26e` |
| `SKIP_ERROR_NOTIFICATION` + interceptor opt-out | `2fe4536` |
| `features/inventory` (store-location lookup + settings read) | `d0cca0c` |
| purchase-order **detail** status family + receivable predicates | `5c191e7` |
| `receipt-list` + `StatusChip.family` (placeholder deleted) | `7a5b2ea` |
| `receipt-detail` (read-only view, lines joined to the order) | `37bf0b2` |
| `receipt-create` (two states + `receipt-form.utils`) | `c993add` |
| wiring (routes, dashboard card, PO-edit action) | `782c7e8` |
| `DetailTable` reception progress (opt-in) | `fc19070` |
| first purchases e2e + mock extension | `467ca28` |
| docs (`AGENTS.md`) | `5b5b44d` |
| e2e made independent of the silent-SSO race | `5b1ea62` |
| the verification's four fixes | `69494ed` |
| RBAC table + `STORE_ROLES` doc correction | `6be6950` |

**Gates on the committed bytes at the rebased tip** (`6be6950`): `npm run lint` clean; `npm run test:coverage:check` → **111 test files / 2073 tests, 0 failures**, coverage **92.33 / 74.38 / 87.76 / 92.33** (all four floors OK, up from the 91.60/73.96/87.42/91.60 baseline); `npm run build` succeeds. Range vs `main`: **47 files, +6012/−118, all under `life-control-app-angular/`**.

**The branch was rebased onto `main` before delivery because main moved.** While W2d1 was in flight another session merged #122/#123/#124 (docker provenance, docker build-integrity CI, the gateway's Gradle wrapper) — `main` went `cd60b2f` → `3e451fe`. Before the rebase, `git diff main..HEAD` rendered *main's own* additions as if this slice had reverted them (the independent verification caught it as process finding #10). After the rebase, `merge-base == main == 3e451fe` and the range is exactly the slice.

#### Independent verification (read-only, over the committed range) — 4 real defects, all fixed

The verifier confirmed the payload contract field-for-field, the client validation as a strict subset of the server's, the URL/controller match, the routing order, the `DetailTable` backward compatibility (proved from the diff, not from tests), the detail page's read-only-ness and its refusal to render raw UUIDs, and the e2e mock's honesty (including that the pre-existing `/api/companies` and `/api/countries` handlers are byte-identical). It then found and I fixed:

1. **The predicates were case-insensitively permissive but the backend is not.** `requireReceivable` compares case-insensitively, yet `isDetailStatusReachable` looks its transition table up **case-sensitively**, so `'pending'`/`'in transit'` were offered client-side and refused server-side. Fixed by comparing the canonical seeded names exactly (fail-closed), with the docblocks corrected to describe the real asymmetry.
2. **A failed settings read was silent end-to-end** — toast suppressed, no error rendered, `requiresLocation()` false, so the operator submitted believing the configured location would be used. Fixed: the failure renders its own warning and now **requires** an explicit location (the client cannot know the default it could not read).
3. **`enabled` was absent from the client's gating.** A soft-deleted `Accepted` order reached through the order page (or a hand-typed `?purchaseOrderId=`) offered a reception that died server-side with `DisabledPurchaseOrderException` → 400 → the misleading "Revisá las cantidades" copy. Fixed at all three sites: the create page's blocking state, the picker row, and the order page's action.
4. **Two nits**: `receipt-detail` had re-implemented the `cause`-walk that `shared/data/http-error-message.ts` now exports (the shared one is used and the copy deleted), and the e2e mock echoed a `Z`-suffixed `receivedAt` where the real DTO is a zone-less `LocalDateTime`.

Its coverage-gap list is recorded below as follow-ups rather than silently deferred.

#### The e2e harness flake (pre-existing, proven, and NOT caused by this slice)

`npm run test:e2e` is **not** reliably green in this environment, and the cause is not this slice. Evidence:

- `npx playwright test` (full suite, all four spec files) → **10 passed / 2 failed**, the two failures always `purchases-receipts.spec.ts`, always the last two tests dispatched; the app renders the **unauthenticated** Home (a `Login` button), and a temporary probe showed the authorization request reaching the **real** Keycloak on `:8181` (`Invalid parameter: redirect_uri`) instead of the mock.
- The same run shape **without** the new spec, and with `e2e/mocks/api.ts` reverted to `main`'s version, and with only the pre-existing specs: `--workers=1` → **2 failed / 8 passed**, twice, deterministically. So the flake reproduces on the pristine harness with none of this slice's code in play.
- With 30 tests in parallel (`--repeat-each=3` over the pre-existing specs) both the pristine and the extended mock pass 30/30, so it is not simple parallel load.
- Two concrete hypotheses were tested and **refuted**: (a) the login-status iframe losing keycloak-js's first message (answering `changed` to the first three polls did not help), and (b) the third-party-cookie probe losing its single `supported` post (repeating it every 50 ms did not help). Both experiments were reverted; `e2e/mocks/keycloak.ts` is unmodified.
- **The practical CI answer**: `npx playwright test --retries=2` (CI's `retries`) → **10 passed / 2 flaky**: both failures pass on the first retry. CI's own configuration (`workers: 2`, `retries: 2`, a fresh server, no real Keycloak on `:8181`) therefore ends green — as a flaky pass, not a clean one.

**Follow-up (own change, not this slice): root-cause and fix the mocked silent SSO.** Until then the suite's authentication tests are flaky in any environment where a real Keycloak answers on `:8181`.

#### Follow-ups recorded, not fixed (W2d1 scope boundary)

1. **`receipt-create.scss` is 77 bytes over the 4 kB component-style budget** (`4.08 kB`), one new build warning. Deliberate: the component hosts two surfaces (picker + form) and the alternative was either degrading the loading chrome every receipt page shares or splitting the picker into its own component — a bigger refactor of a just-verified page. Three pre-existing pages (`company-list`, `product-list`) are further over the same budget. The clean fix is extracting the picker.
2. **The e2e mock has no `/api/status-types` or `/api/statuses` handler**, so a future purchases spec that opens the order-edit page's transition flow will hit the 404 fallthrough (the W2c recon predicted it).
3. **Unasserted branches the verifier named**: a 200 `getSettings` followed by a non-404 failure at page level; `defaultLocationName()` returning `null`; the picker's pagination and 300 ms debounce; `chooseAnotherOrder()`'s reset; and no test pins `RECEIVABLE_DETAIL_STATUSES` against the backend's `DETAIL_TRANSITIONS`.
4. **A goods-receipt 400 returns a flat `ErrorResponse`**, not a per-field body, so the plan's "400 with a per-line message" surfaces as raw English text in the page's muted `server-detail` line inside a Spanish UI. Accepted deliberately (the information is preserved, not invented).

### W2d2 — Store inventory-settings screen (branch `feat/po-receipt-w2d2`, stacked on W2d1)

| Task | Scope | Allowed edit surfaces (source) |
| --- | --- | --- |
| **W2d2-T1** | `features/inventory/data/store-inventory-settings.service.ts` + spec: `getSettings(chain)` (404 → `null`, the app's first "unconfigured" read) and `upsertSettings(chain, request)` on the nested `…/stores/{storeId}/inventory-settings`. | `.../inventory/data/**` |
| **W2d2-T2** | `inventory-settings` page: the store chain resolved from query params (the established leaf convention) with the store chosen from the store card that links here; two `<mat-select>`s bound to the T3/W2d1-T3 lookup list (options hold the entity, `compareById`), receiving + sales location, same location allowed twice; "unconfigured" empty form on 404; save + reload; 400/404/403 mapping. | `.../inventory/pages/inventory-settings/**` |
| **W2d2-T3** | Navigation: `companies.routes.ts` gains the settings child under the `stores` group (roles `STORE_ROLES` read / `STORE_WRITE_ROLES` write, `unsavedChangesGuard` on the write path), and `stores-page`'s card gains the "Configurar inventario" action that navigates with the chain as query params — the same shape as its existing store-areas action. No header-menu entry: that matches the store-areas/store-zones precedent, which is reached from the page, not the menu. | `.../companies/companies.routes.ts`, `.../stores/pages/stores-page/**` |
| **W2d2-T4** | Tests: service spec + page spec + the stores-page action spec. | co-located `*.spec.ts` |
| **W2d2-T5** | Docs: the settings pattern in `life-control-app-angular/AGENTS.md`, and the deferred concurrency contract recorded next to it. | `life-control-app-angular/AGENTS.md` |

### W2d2 results — 3 commits on `feat/po-receipt-w2d2` (stacked on W2d1's tip `6be6950`)

| Work unit | Commit |
| --- | --- |
| the settings screen (service upsert + page + route + card action + specs) | `eba0126` |
| the three defects the verification found | `492311a` |
| docs (`AGENTS.md`: the settings pattern + the read/write split) | `7697bce` |
| the doc drift the second verification refuted | `0340481` |

Range vs the W2d1 tip: **12 files, +1309/−2**, all under `life-control-app-angular/`.

**Gates at the tip** (`7697bce`): `npm run lint` 0 · `npm run test:coverage:check` 0 → **112 test files / 2123 tests / 0 failures / 0 skipped**, coverage **92.39 / 74.60 / 88.07 / 92.39** (floors 80/60/75/80 OK; the W2d1 baseline was 111 files / 2073 tests, 92.33/74.38/87.76/92.33) · `npm run build` 0 with **no ERROR** and no new budget warning (the 4 `.scss` budget warnings and the over-budget initial bundle are all pre-existing; `store-inventory-settings.scss` is not among them). The new page spec carries **41 tests**. Gates were run twice by an independent verifier: once on the feature commit and once on the fix commit, each time proving `HEAD`/`HEAD^{tree}` and a clean tree before and after, so the gated bytes are the committed bytes.

#### Delivery

**Merged, byte-identical.** #125 merged into `main` as **`b3b58f5`** and #126 as **`c9630ec`**. `git rev-parse main^{tree}` after each merge equals the corresponding gated tip tree exactly (`1d491d97` and `4e390525`), so what the gates and the two verifications inspected is what `main` now contains — no conflict resolution, no reformat, no drift in the merge.

The retarget procedure mattered, and it is cheaper than the recorded one suggested: after #125 merged, `6be6950` was already an ancestor of `main`, so `main...feat/po-receipt-w2d2` collapsed to exactly the 4 W2d2 commits and GitHub reported 12 files / +1309−2 with **no rebase needed**. A local rebase onto the new `main` produced the *same tree hash* (`4e390525`) — which is the proof that the rebase was cosmetic — and the `--force-with-lease` it would have required was blocked by the safety policy. The merge went ahead on the untouched remote branch instead, so no history was rewritten at all. Lesson for the next stacked pair: **retarget first and re-measure the diff; rebase only if the merge-base is not already an ancestor of the base branch.**

#### Deviations from the W2d2 plan as written (locked with the user, 2026-09-20)

1. **Surface**: the page lives at `features/companies/stores/pages/store-inventory-settings/`, not at `features/inventory/pages/inventory-settings/**` as W2d2-T2 said. The plan was the outlier: the three sibling leaf pages (`store-areas-page`, `store-zones-page`, `store-locations-page`) all live under `companies/stores/pages/`, and the page's entry door is the store card. The data services stay in `features/inventory/` — data in the owning feature, page next to its entry point.
2. **Route shape**: one route with `STORE_ROLES` + `unsavedChangesGuard` as `canDeactivate`, instead of the plan's "read route + write path". There is a single form on a single route, so the read/write split happens **inside** the component: `canWrite = hasAnyClientRole(STORE_WRITE_ROLES)` gates the actions and `canSubmit()` folds in `!canWrite`. That second half is a deviation from the plan *and* from the repo's existing precedent (which only gates rendering) — argued and accepted because a rendering-only guard is one refactor away from emitting a PUT.
3. **`canWrite` itself is post-plan**: the plan assumed the route gate alone was enough. It is not: `STORE_ROLES` includes the read-only `lc-company-store-read`, while the backend's `@PreAuthorize` on the PUT excludes it, so a read-only principal could open the screen and die with a 403.
4. **Test count**: 41 page specs, of which 6 are role-gating and 3 came from the verification fixes.

#### Independent verification (read-only, range `6be6950..eba0126`) — 0 blocking findings, 3 defects, all fixed

The verifier worked against the **real backend** rather than against the client's own tests, and confirmed: the constructed URL matches the backend's nested route **segment for segment (12/12)**, including the absent trailing slash (Spring Boot 3 does not match one); the response/request DTO field names, order and nullability match `StoreInventorySettingsResponse`/`Request` and the `NOT NULL` columns; the 404-as-"unconfigured" contract is real (`ResourceNotFoundException` → 404 in `GlobalExceptionHandler`, and `NOT NULL` columns make a null-filled row impossible); the `countryId → companyCountryId` mapping matches `stores-page` and all three siblings; `canWrite` is computed by the identical expression as the siblings; and `MatSelect` emits no `selectionChange` on a programmatic `[value]` assignment, so the seeding path cannot set `dirty`.

Findings, all fixed in `492311a` unless noted:

1. **F1, medium** — the *stale-location notice* sat behind `canWrite`, so a read-only principal saw a blank selector, **indistinguishable from a store that was never configured**, with the only text naming the condition hidden. This also broke the repo's own convention: `canWrite` gates actions there, never informative text. Fixed by un-gating the notice. It was caused by the fix instruction given in this session, not by the original screen.
2. **F2, low** — the read-only guarantee was held **by rendering alone**: `canSubmit()` did not consult `canWrite`, so a programmatically-reached `onSave()` would emit the PUT and only the server's 403 would stop it. Fixed by adding `!this.canWrite` to `canSubmit()`.
3. **F3, low (race, premise corrected later)** — the seeding `effect()` unconditionally overwrote both selections **and reset `dirty` to `false`** on any reload, so a programmatic or in-flight edit could be lost *and* the unsaved-changes guard let the operator navigate away. Fixed by making the effect yield to `dirty()`. **The second verification corrected the premise**: Angular reports a reload as `'reloading'` and `isLoading()` includes it, so the template's skeleton branch replaces the form for the whole reload — the "operator edits in that window" story the fix commit tells is not reachable through the UI. The guard is kept as defence in depth (the effect is the only programmatic writer of both selections), and both the comment and `AGENTS.md` now say that instead of claiming a live window.
4. **F4, low — recorded, NOT fixed** — the store-locations read is not marked `SKIP_ERROR_NOTIFICATION`, so a failed locations read renders **two** error surfaces (the global toast plus the page's own error state), and the two reads of the same page behave differently. The root cause lives in W2b's `store-location-lookup.service.ts`, outside this slice's surfaces; the fix crosses the slice boundary, so it is recorded rather than applied.

**Test honesty, named by the verifier** (partly fixed): the reload test's name overclaimed (synchronous mock → nothing was ever pending) and now actually holds the reload in flight via a deferred `ReplaySubject` mock; the read-only "guard inert" test asserts something true of every role; the two negative `stores-page` navigation tests pass on a no-op (only the positive test carries evidence); and the stale case was covered only with a writer role, which is exactly why F1 slipped through — a read-only stale case now exists. The URL test compares the implementation against a hand-written literal, so it detects implementation drift but **cannot** detect client/backend divergence; the cross-check is the segment-by-segment comparison, not that test.

**Fail-without-fix demonstrations** (recorded verbatim, one per fix, source line temporarily reverted): F2 → `expected true to be false`; F3 → `expected 'loc-1' to be 'loc-3'`; F1 → `expected null to be truthy`.

**Pre-existing, found but not caused by this slice**: `companies.routes.ts:10` uses the raw literal `'lc-company-store-read'` while `roles.ts` exports `LC_COMPANY_STORE_READ`; `chain` is read once from `route.snapshot` in a field initializer (same in all three siblings); the `stores/pages/index.ts` barrel is imported by nobody; two duplicate `unsavedChangesGuard` implementations exist.

**Nits recorded**: the new `.scss` has a dead `@use … as form` alias; the "Configurar inventario" button sits **outside** `<app-stores-card>` in `.card-wrapper` while its peers ("Editar", "Áreas") live inside `mat-card-actions`; `saveErrorDetail` renders the backend's raw English `message` and ignores `ApiError.errors`.

#### Second verification (focused, read-only, delta `eba0126..HEAD`) — one doc drift refuted and fixed

A focused pass over the fix round plus the docs, with the instruction to confirm or refute **every** factual claim in the docs diff. It confirmed 20+ claims against the implementing files (the route/gate table, the 404-as-normal contract, enabled-only locations, the soft-delete fact, the `countryId → companyCountryId` mapping, the fail-closed path, the header-menu and entry-door claims, the ungated notice and the `canSubmit()` rule) and refuted one:

1. **Doc drift, medium — the settings PUT is NOT last-write-wins.** The docs commit claimed "dos operadores que guarden a la vez no se enteran del conflicto". False: `StoreInventorySettings` carries `@Version` (the `version` column exists since `V11`), and the service javadoc says a concurrent update makes the stale commit fail "instead of silently winning the last write" — with an integration test asserting no lost update. The conflict surfaces as **500** (no handler), and the operator does find out, because the PUT deliberately keeps the global toast. Worse, the claim contradicted the **pre-existing half of its own bullet** ("el conflicto de lock optimista responde 500, no 409"). This is the same defect class W2d1 already had to fix once (a wrong RBAC table). Corrected in `0340481`.
2. **LOW — the F3 premise was wrong.** See F3 above: the skeleton branch closes the window. Corrected in the code comment and the docs.
3. **Nits fixed in the same commit**: "las 4 hojas" was imprecise (7 files declare the `canWrite` line), "bajo `/companies/stores`" was loose wording for routes that are siblings of it, and a stray trailing backtick.

**New nits recorded, not fixed**: `this.dirty.set(false)` at the end of the seeding effect is now a dead write (the effect can only reach it with `dirty()` already false); the effect now **reads** `dirty`, so it also re-runs on the save-success `true → false` transition and reseeds from the retained pre-save value — invisible today behind the skeleton, a visible snap-back if that branch is ever removed or reordered; the F3 test's `{ ...mockSettings }` spread is load-bearing (Angular's default `Object.is` equality on the resource value), so a "cleanup" that passes the same object would silently make the test vacuous; and the reader sees the stale notice with an imperative "Elegí una nueva" it cannot perform (`canSubmit()` is permanently false for a reader in that state) — recorded as a follow-up, the smallest fix being to gate only the imperative clause.

**Pre-existing doc gaps surfaced by the new text**: the route table still does not say that `/companies/stores`'s own `create`/`edit` children are `STORE_WRITE_ROLES`; and the RBAC matrix lists only the six write roles, so the read split is invisible from it (`lc-company-store-read` appears only in the new section).

#### Debts handed forward from W2d2

1. **F4** above: the locations read needs `SKIP_ERROR_NOTIFICATION` (or the page should stop rendering its own locations error) — own change, crosses into W2b's service.
2. The two weak `stores-page` negative tests; the unasserted branches the verifier listed (401/409/no-`ApiError` in `handleSaveError`, `onRetry()` reloading locations, the loading/skeleton branch, and the Case B "settings exist but zero enabled locations" state, where the configured values are fetched and seeded and then never rendered).
3. Carried unchanged from W2d1: the `receipt-create.scss` budget overshoot; the e2e mock missing `/api/status-types` and `/api/statuses`; the unasserted `RECEIVABLE_DETAIL_STATUSES` pin; the flat `ErrorResponse` on a receipt 400.
4. Carried from W2c: the cross-tenant purchase-order read, the `CompanyStoreService` literal-role predicates, the nullable-parameter JPQL idiom, 403-vs-404 on a foreign-store receipt read, no URL-level role rule for the goods-receipt prefix, `spotbugsTest` disabled, and the gateway's missing CI.
5. From the second verification: the imperative stale-notice copy shown to a read-only principal; the dead `dirty.set(false)` in the seeding effect; the effect's new dependency on `dirty` (latent snap-back if the skeleton branch changes); the load-bearing `{ ...mockSettings }` spread in the F3 test; and the two doc gaps (the `stores` `create`/`edit` gate is not in the route table; the RBAC matrix has no read-role row).
6. `companies.routes.ts:10` still writes `'lc-company-store-read'` as a literal while `roles.ts` exports `LC_COMPANY_STORE_READ`.

### W2d3 — `lc-receiving` routing guard (branch `feat/po-receipt-w2d3`, off `main` @ `f576c34`)

**Goal**: the receipts UI stops being admin-only. `lc-receiving` reaches the receipts area and **nothing else** under `/purchases`; the header menu and the dashboard stop advertising purchase orders to a receiving-only user. **Security-relevant**: it widens a route guard and changes menu visibility, so it takes its own review, exactly as W2c separated the role from the feature. **No backend change** — `Roles.RECEIVING`, the three `GoodsReceiptController` endpoints (`:60,:79,:92`) and `ScopeLevel.STORE.roleNames()` (`:52`) already account for the role.

**Recon facts that bound this slice** (read-only, over `main` @ `f576c34`):

1. **One guard, five unguarded children.** `purchases.routes.ts` carries the whole gate on its single parent (`data: { roles: ['lc-admin'], clientId: 'life-control-client' }`), and the five children declare **no** `data` and **no** `canActivate`. `keycloakRoleGuard` therefore runs exactly once, at the parent. Relaxing the parent without re-pinning `orders*` would hand the entire orders area to `lc-receiving` — which is why the re-pin is part of this slice and not a follow-up.
2. **A child `data.roles` is inert without its own guard.** `normalizeRequiredRoles` is only ever reached through `canActivate` (`auth-keycloak-guard.ts:37-41`), so the re-pin needs `canActivate: [keycloakRoleGuard]` **plus** the `data` block on each of the three `orders*` children. The house precedent is `companies.routes.ts:15-27` (a nested `''` path with its own `canActivate: [keycloakRoleGuard]` + `data`).
3. **The existing spec pins the old parent.** `purchases.routes.spec.ts`'s last case asserts `purchasesRoutes[0].data` deep-equals `{ roles: ['lc-admin'], clientId: 'life-control-client' }`, so it fails the moment T2 lands and must move in the same work unit.
4. **Re-pinning `orders*` creates no dead link in the receipts UI.** `receipt-list` navigates only to `/purchases/receipts/:id`; `receipt-create`/`receipt-detail` read the order through `PurchaseOrderService` (data, not a route). The only cross-link between the two areas points the other way — `purchase-order-edit.html:10` → `/purchases/receipts/create` — and stays admin-reachable.
5. **The dashboard has no role gating today.** `purchases-admin.component.ts`'s `cards` is a static array with no `requiredRoles`, unlike `companies-admin.component.ts:15,145` (`requiredRoles` per card + `filter(card => !card.disabled)`). W2d3 applies that existing pattern instead of inventing one.
6. **The header has no receiving signal.** The Compras entry hangs off `isAdmin` (`header.ts` `items()`), and `isCompanyRole`/`isSalesRole` are the two precedents for a role signal set and cleared in the same `KEYCLOAK_EVENT_SIGNAL` `effect`.
7. **The claim path is the real precondition, and it is provisioning, not code.** `CurrentUserContext#verifyLevel` verifies parent levels against **claims**, never parent roles, so a caller holding **only** `lc-receiving` must carry the `company_id → company_store_id` path in the token (`ScopeLevel.java:49-52`). A `lc-receiving` user without those claims gets **403 from every store-scoped endpoint** even with the guard open, exactly as the W2d1 note already recorded. This slice cannot fix that; it is stated as an operational precondition instead of silently assumed.
8. **The e2e harness needs no work.** `e2e/fixtures/app.ts:18` already accepts `clientRoles` and `e2e/mocks/keycloak.ts:50` fabricates the token from it, so a receiving-only case is one `app.use({ clientRoles: ['lc-receiving'] })` line.
9. **`roles.ts` deliberately does not rank roles** ("it does not rank roles, expand hierarchies, or model realm roles"), and `purchases.routes.ts` writes its role literals inline today. T2 imports the constants (matching the W2d2 nit that `companies.routes.ts:10` still writes `'lc-company-store-read'` as a literal) without adding a hierarchy helper.

| Task | Scope | Allowed edit surfaces (source) |
| --- | --- | --- |
| **W2d3-T1** | `roles.ts`: `LC_RECEIVING = 'lc-receiving'` added to the constants and to `CLIENT_ROLES`. | `src/core/security/roles.ts` |
| **W2d3-T2** | `purchases.routes.ts`: parent relaxes to `roles: [LC_ADMIN, LC_RECEIVING]`; each of `orders`, `orders/create`, `orders/:id` gains `canActivate: [keycloakRoleGuard]` + `data: { roles: [LC_ADMIN], clientId: CLIENT_ID }` (recon 2). Receipts children stay untouched — they are covered by the parent. | `src/features/purchases/purchases.routes.ts` |
| **W2d3-T3** | `header.ts`: an `isReceiving` signal set and cleared alongside `isAdmin` in the same `effect`, and the Compras entry moved out of the `isAdmin` block to `isAdmin() \|\| isReceiving()`. Products and Users Admin stay admin-only. | `src/core/layout/header/header.ts` |
| **W2d3-T4** | `purchases-admin.component.ts`/`.html`: `requiredRoles` on `DashboardCard` (Purchase Orders → `[LC_ADMIN]`; Receipts → `[LC_ADMIN, LC_RECEIVING]`) and the companies dashboard's filter, evaluated once through `hasAnyClientRole` in an injection context. | `.../pages/purchases-admin/**` |
| **W2d3-T5** | Specs: `purchases.routes.spec.ts` updated to the new parent **and** extended with the load-bearing half — the three `orders*` children carry the admin-only `data` **and** their own `canActivate`; `header.spec.ts` cases for a receiving-only token (Compras visible, Products and Users Admin absent) and for `lc-admin` (unchanged); new `purchases-admin.component.spec.ts` for receiving-only (Orders hidden, Receipts shown) and admin (both). The parent's expected roles are written as **literals** (`['lc-admin', 'lc-receiving']`) so the wire value is pinned end to end rather than compared against the constant it came from; no separate `roles.spec.ts` (a pure constant declaration has no runtime behaviour to assert — the W2d1 rule). | co-located `*.spec.ts` |
| **W2d3-T6** | e2e: an `lc-receiving` describe in `navigation.spec.ts` (Compras visible; Products and Users Admin absent) and, in `purchases-receipts.spec.ts`, the guard-split case — a receiving-only user reaches `/purchases/receipts` and is sent to `/unauthorized` on `/purchases/orders`. Recorded flake caveat: the suite's silent-SSO race is pre-existing and CI-green only through `retries` (see the W2d1 flake section). | `e2e/specs/**` |
| **W2d3-T7** | Docs: `life-control-app-angular/AGENTS.md` — the `/purchases` route table, the header-menu table (`isReceiving`), the RBAC matrix's receiving row, the replacement of the now-false "`lc-receiving` es hoy API-only" paragraph, and the claim-path precondition of recon 7. | `life-control-app-angular/AGENTS.md` |
| **W2d3-T8** | Gates at the tip (`npm run lint`, `npm run test:coverage:check`, `npm run build`, `npm run test:e2e`), the slice's **own security-focused independent verification** (the provider-owned risk reviewer is not dispatchable while the review switch is off — `gentle-ai review mode status` reads `receipt-driven development: off`, so the substitute is a `gentle-ai-verify` pass instructed adversarially over the guard widening), and the work-unit commits. | — |

### W2d3 results — 7 commits on `feat/po-receipt-w2d3` (off `main` @ `f576c34`)

**Range**: `10 archivos / +352−41`, todo bajo `life-control-app-angular/`. **No pusheado, sin PR**
(decisión del usuario). Tip `3dff094`, tree `70dfb1a7`.

**Commits**: `adaeaa2` el ensanchamiento del guard (roles + rutas + spec de rutas) · `8ecd4c4` el
gateo de las cards del dashboard · `f528c70` el menú Compras para recepción · `758fd63` los e2e
(navegación + split del guard) · `780e959` la doc (`AGENTS.md`) · `3354a03` las cuatro correcciones
que refutó la verificación adversarial de la doc · `3dff094` el fix del locator ambiguo del e2e.

**Gates en el tip (`3dff094`, tree `70dfb1a7`)**: `npm run lint` 0 · `npm run test:coverage:check` 0
→ **113 archivos / 2131 tests / 0 fallas / 0 skipped**, cobertura **92.41 / 74.67 / 88.08 / 92.41**
(floors 80/60/75/80; baseline de `f576c34`: 112 archivos / 2123 tests, 92.39/74.60/88.07/92.39) ·
`npm run build` 0, sin ERROR y **sin warning nuevo** (los 5 preexistentes: bundle inicial + los 4
`.scss` sobre budget).

**e2e en el mismo tip**: corrida con `CI=true E2E_PORT=4300 npx playwright test --retries=0` →
7 passed / 2 failed, y las 2 fallas son la **flaqueza preexistente de SSO silencioso** (firma:
Keycloak real en `:8181` respondiendo `Invalid parameter: redirect_uri`), no el código nuevo. Corrida
con la config de CI (`CI=true E2E_PORT=4300 npx playwright test`, retries 2) → **9 passed / 5 flaky /
0 failed, exit 0**: las 5 flaky son la misma flaqueza. El test nuevo de recepción pasa **al primer
intento** en las dos corridas (no por retry).

**CORRECCIÓN de una premisa vieja**: la suite e2e **no está cableada en ningún workflow**. `grep`
sobre `.github/workflows/` no encuentra ninguna referencia a `playwright`/`e2e`, y
`angular-ci.yml` corre solo `npm run lint`, `npm run build` y `npm run test:coverage:check`. Así que
"CI verde por los retries" (W2d1) describe una corrida **local con la config de CI**, no un check de
GitHub: GitHub nunca corre esta suite. Los "retries" que la salvan son de la corrida local.

#### Delivery

- Branch `feat/po-receipt-w2d3` pusheada a `origin`; **PR #128** abierto contra `main`:
  https://github.com/le03nava/LifeControl/pull/128
- `state=OPEN`, `mergeable=MERGEABLE`, **10 archivos / +352−41** — exactamente el rango gateado, sin
  rebase (el merge-base `f576c34` ya era ancestro de `main`).
- Sin issue y sin labels, body en inglés con las secciones fijas del repo (`## What`,
  `## Review map`, `## Deliberate decisions`, `## Verification`, `### What verification actually
  caught`, `## Not fixed here`, `## Chain Context`), y **sin `## Size note`** (393 líneas, debajo del
  presupuesto de 400).
- CI: **`Angular CI → Lint, Build & Test` pass en 3m50s** (run 35546387921). `API CI` y
  `Docker Build Integrity` **no dispararon**, que es lo correcto: el cambio es 100%
  `life-control-app-angular/**` y sus path filters no lo matchean.
- **NO mergeado**: el merge es decisión del usuario.

#### Incidente de entorno del e2e (importante para la próxima corrida)

La primera corrida del e2e fue **inválida**: `playwright.config.ts` tiene
`reuseExistingServer: !CI`, así que en local Playwright **reusa cualquier listener en `:4200`**. En
esta máquina `:4200` lo tiene el contenedor `lifecontrol-dev-life-control-app-angular`
(`life-control-app-angular:latest`), o sea una **imagen vieja**: la suite validó esa app, no el árbol.
**Regla: con el stack de docker-dev levantado hay que correr el e2e con `CI=true E2E_PORT=4300`**
(el `CI=true` apaga el reuse; el puerto libre evita el contenedor). No se tocó el contenedor: es de
otro proceso.

#### Verificación adversarial de seguridad (delegada, read-only, rango `f576c34..780e959`)

**0 hallazgos bloqueantes.** Se confirmaron las tres afirmaciones que sostienen el slice: (a) ningún
camino bajo `/purchases` admite `lc-receiving` a una pantalla de órdenes (el `data` del hijo pisa el
del padre y el guard del hijo corre de verdad); (b) `keycloakRoleGuard` **falla cerrado** en todos
los casos salvo la rama "sin lista de roles declarada", que ninguna ruta de órdenes usa; (c)
agregar `LC_RECEIVING` a `CLIENT_ROLES` **no otorga nada**: ese array no tiene ningún consumidor en
todo el frontend (verificado con grep), y todos los chequeos usan listas explícitas que lo excluyen.

**Hallazgo MEDIO preexistente, no introducido acá**: los `GET` de órdenes de compra están detrás de
`isAuthenticated()` (`PurchaseOrderController.java:50,61,143`), no de un rol, así que un token de solo
recepción **puede leer todas las órdenes por API**. "Órdenes admin-only" es una restricción de UI y de
ruteo, no del backend. Se documentó explícitamente en `AGENTS.md` para que nadie la lea como
aplicación de servidor; la deuda sigue abierta (es la misma que arrastra W2c).

**La verificación de la doc encontró 4 defectos reales en el commit `780e959`**, todos corregidos en
`3354a03`: (1) una viñeta seguía diciendo que el padre de `receipts` era admin-only, cuando ese mismo
commit lo había relajado — el drift clásico; (2) la precondición del token omitía
`company_country_id`, que también es `required = true` (`ScopeLevel.java:26,29`); (3) "todos los
endpoints store-scoped responden 403" es **falso para el listado**, que filtra por
`company_store_ids` y responde **200 con página vacía** (`GoodsReceiptService.java:314-333`); (4) la
cita apuntaba al javadoc de `STORE` en vez del código que implementa la regla
(`CurrentUserContext.java:348-354`). Confirma la lección de W2d2: **un commit de doc merece su propia
verificación, como afirmaciones y no como prosa.**

#### Defecto propio que encontró el e2e (y por qué el e2e valía la pena)

El test nuevo de recepción **nunca pasaba**, y no por el código de producción:
`getByRole('heading', { name: 'Recibos' })` matchea por substring, así que en la página vacía
resolvía **dos** elementos (`<h1>Recibos</h1>` y el `<h3>No hay recibos registrados</h3>`) y rompía
en strict mode. Fue **determinista**: falló en el intento original y en los dos retries de una corrida
con `retries=2`. El fix es `{ level: 1, name: 'Recibos' }` (`3dff094`). Sin la corrida del e2e este
test habría quedado en el PR como verde-falso.

#### Dato de alcance de gates (honesto, y no obvio)

`ng lint` **no cubre `e2e/**`**: `angular.json` declara `lintFilePatterns = ["src/**/*.ts",
"src/**/*.html"]`, así que el último commit (que toca solo un spec e2e) lo validó la corrida de
Playwright, **no** el lint. El `lint` 0 de arriba es cierto pero no dice nada sobre ese archivo.
Preexistente, no de este slice.

#### Deudas que deja W2d3 (además de las preexistentes)

1. **La card `disabled` del dashboard de compras quedó inalcanzable**: como `cards` filtra las
   deshabilitadas, `[class.card--disabled]` y la rama `@else` "Coming soon" de
   `purchases-admin.component.html` ya no se pueden renderizar (el `.scss` también). Preexistente en
   parte (ambas cards eran `disabled: false`), pero el slice lo vuelve estructural. El patrón de
   `companies-admin` no tiene esas ramas: alinearlo es el fix limpio, con cero cambio de conducta.
2. `header.ts` sigue leyendo `'lc-admin'` como literal mientras usa `LC_RECEIVING` para lo nuevo:
   inconsistencia preexistente que conviene unificar en un commit de limpieza.

### Debts handed forward again (unchanged, and now with an owner)

1. **W2b #2 and #3** → the W2-D13 follow-up slice (version contract + 409 handler). W2d does **not** close them.
2. ~~**`lc-receiving` is API-only** until W2d3.~~ **Cerrado por W2d3**: el rol ya entra al dashboard
de compras y al área de recibos, con el área de órdenes re-guardada. La precondición que queda **no
es código**: el token debe llevar el camino de claims `company_id` → `company_country_id` →
`company_store_id`, o los endpoints store-scoped responden 403 (alta y detalle) o 200 con página
vacía (listado).
3. Carried from W2c: the cross-tenant purchase-order read (`isAuthenticated()` only), `CompanyStoreService:96/:127` literal-role predicates, `ProductSupplierRepository`'s nullable-parameter idiom, 403-vs-404 for a foreign-store receipt read, no URL-level role rule for the new prefix, `spotbugsTest` disabled, and the gateway's missing CI/unversioned `gradlew`.

---

## W2-A Per-store receiving location (D3) — original design intent (task ids T1–T24), superseded by W2a/W2b above
- T1 `Roles.RECEIVING = "lc-receiving"` + client role in `docker/scripts/keycloak-setup.sh` + role/endpoint tables in `life-control-api/AGENTS.md`.
- T2 `V10__store_inventory_settings.sql`:
  ```sql
  CREATE TABLE store_inventory_settings (
      company_store_id      UUID PRIMARY KEY REFERENCES company_stores(id),
      receiving_location_id UUID NOT NULL REFERENCES store_locations(id),
      sales_location_id     UUID NOT NULL REFERENCES store_locations(id),  -- consumed by W3, stored now
      version    BIGINT NOT NULL DEFAULT 0,
      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
  );
  ```
  **Validation note**: `store_locations` hangs off `store_zones`, not `company_stores`, so "the location belongs to this store" is a join (`store_locations → store_zones → store_areas → company_stores`), not an FK. It gets its own exception and its own test.
- T3 Entity + repository + `StoreInventorySettingsRequest`/`Response`.
- T4 `StoreInventorySettingsService` with the store-scoped location validation.
- T5 Nested controller under the stores path (GET/PUT) + OpenAPI annotations.
- T6 Store-locations lookup by store (lightweight, for the picker).

## W2-B Inventory effect (D1, with the interim rule) — original design intent, delivered as W2a
- T7 `V11__inventory.sql`:
  ```sql
  CREATE TABLE product_variant_locations (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      product_variant_id UUID NOT NULL REFERENCES product_variants(id),
      store_location_id  UUID NOT NULL REFERENCES store_locations(id),
      stock DECIMAL(12,2) NOT NULL DEFAULT 0,
      UNIQUE (product_variant_id, store_location_id)
  );

  CREATE TABLE inventory_movements (                -- append-only
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      product_variant_id UUID NOT NULL REFERENCES product_variants(id),
      company_store_id   UUID NOT NULL REFERENCES company_stores(id),
      store_location_id  UUID NOT NULL REFERENCES store_locations(id),
      movement_type      VARCHAR(30) NOT NULL,      -- RECEIPT (SALE/ADJUSTMENT arrive with W3)
      quantity           DECIMAL(12,2) NOT NULL,    -- signed
      reference_type     VARCHAR(40),
      reference_id       UUID,
      created_by         VARCHAR(255),
      occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
  );
  ```
- T8 Entities + repositories.
- T9 **`InventoryService.applyReceipt(...)`** — the interim-safe mutator: lock the variant and the location balance in sorted id order, write the ledger row, `location.stock += qty`, `variant.stock += qty`. **Explicitly NOT a `SUM(locations)` recompute** — with a comment explaining why (interim inconsistency, item 2 above), because the natural 1B implementation is the recompute and it would resurrect sold stock.
- T10 Comment/docblock recording the interim contract: sales still bypasses, location stock overcounts, ledger holds only receipts until W3.

## W2-C The receipt document and use case — original design intent, planned as W2c
- T11 `V12__goods_receipts.sql` + receipt status type/seed:
  ```sql
  CREATE TABLE goods_receipts (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      receipt_number VARCHAR(30) NOT NULL UNIQUE,
      purchase_order_id UUID NOT NULL REFERENCES purchase_orders(id),
      company_store_id UUID NOT NULL REFERENCES company_stores(id),
      receiving_location_id UUID NOT NULL REFERENCES store_locations(id),
      status_id UUID NOT NULL REFERENCES statuses(id),
      received_by VARCHAR(255),
      received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
      comments VARCHAR(500),
      enabled BOOLEAN NOT NULL DEFAULT true,
      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
  );

  CREATE TABLE goods_receipt_items (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      goods_receipt_id UUID NOT NULL REFERENCES goods_receipts(id),
      purchase_order_detail_id UUID NOT NULL REFERENCES purchase_order_details(id),
      product_variant_id UUID NOT NULL REFERENCES product_variants(id),
      quantity_received DECIMAL(12,2) NOT NULL CHECK (quantity_received > 0),
      comments VARCHAR(500)
  );
  ```
- T12 Entities + repositories + DTOs.
- T13 Reception use case, atomic and idempotent by `receipt_number` (same generator pattern as `PurchaseOrderService.generateOrderNumber`): validate receivability (W1-5), resolve the location (store default or operator override, D3), validate `0 <= received + this <= ordered`, call `InventoryService.applyReceipt` per line, update `received_quantity`, trigger the W1-4 status derivation.
- T14 Endpoints: `POST/GET /api/purchase-orders/{id}/receipts`, `GET /api/goods-receipts/{id}`, `GET /api/goods-receipts`.
- T15 Gateway route registration in `api-gateway/src/main/java/com/lifecontrol/gateway/routes/Routes.java` if a new prefix is introduced.
- T16 Unit + controller + integration tests (Testcontainers) including the location-does-not-belong-to-store rejection and the over-receipt rejection.
- T17 Work-unit commit.

## W2-UI (Angular, follows W2) — original design intent, planned as W2d
- T18 `goods-receipt.service.ts` + models.
- T19 `receipt-list` + `receipt-create` pages (replace the placeholder).
- T20 Store inventory-settings screen — **no settings-screen pattern exists in the app today**, this introduces it.
- T21 `DetailTable` received column + line status chip; `purchase-order.service.ts` gains the detail-status method if W1 keeps it.
- T22 Enable the disabled dashboard card; route wiring; `lc-receiving` guard in `roles.ts`.
- T23 Vitest specs + the first Playwright spec for purchases.
- T24 Work-unit commit.

---

# W3 — Sales rework (deferred)

**Not scheduled.** Recorded so the debt is explicit and the reconciliation is not forgotten.

| # | Change |
| --- | --- |
| W3-1 | Refactor `SalesOrderService.applyStockChanges` onto `InventoryService`; deduce by priority (D7-B): the store's `sales_location_id` first, then remaining locations FIFO, with an operator-specified override |
| W3-2 | Write `SALE` movements into `inventory_movements` (the ledger becomes a real stock history) |
| W3-3 | Restore the strict invariant `product_variants.stock = SUM(product_variant_locations.stock)` and switch `InventoryService` from additive to recompute semantics |
| W3-4 | **Reconcile** location balances against the aggregate; with D6 this can be a reset rather than a computed backfill |
| W3-5 | Frontend: make the location picker honest — location stock only becomes sellable availability after W3 |

---

## Risks

1. **The additive-vs-recompute trap (W2-B T9).** Implementing the receipt as a `SUM(locations)` recompute silently resurrects sold stock. This must be a comment in the code, not just a line in this document.
2. **W1-3 breaks the Angular purchase-order form.** Resolve (a) vs (b) before coding W1.
3. **Two writers for the detail status.** Resolved by W1-D2 (removal); W1b/W2 must not reintroduce a manual writer.
4. **`product_variants` has no `UNIQUE(product_id, company_store_id)`.** D2 removes reception-side ambiguity; the constraint itself is out of scope until confirmed.
5. **`lc-receiving` touches auth.** Security review mandatory before the W2-A role merges (high risk).
6. **Flyway.** Head is `V8__store_optimistic_locking.sql`; `V9`-`V12` are free. Never edit an applied migration.
7. **Ledger honesty.** Until W3 the ledger has no `SALE` rows; do not expose it as a complete movement history.
8. **No E2E coverage** for purchases or store locations today.

## Verification plan

- `cd life-control-api && ./gradlew spotlessCheck spotbugsMain --no-daemon` then `./gradlew test --no-daemon` (CI gate, `.github/workflows/api-ci.yml`).
- Integration tests extend `AbstractPostgresIntegrationTest` (Testcontainers, Flyway enabled) for schema, FK and constraint behaviour.
- Angular: `npm run lint`, `npm run test:coverage:check`, `npm run test:e2e`.

## Controls (project-conventions)

- **Risk classification**: **W1a = medium-high** (additive schema change + contract fields + an endpoint removal; no auth or permission change). **W2-A = high** (new role + auth surface).
- **Security review**: mandatory for W2-A (the `lc-receiving` role), not applicable to W1a — it adds no auth surface and no data exposure; the variant/ownership validation it adds is a narrowing of accepted input.
- **Migration plan**: additive migrations only, no backfill (D6).
- **Rollback plan (W1a)**: revert the work-unit commit (`git revert <sha>`). Because V9 only adds a **nullable** column plus an index, and nothing else in the codebase reads `product_variant_id`, a revert is data-safe: no backfill exists to lose and no read path depends on the column. Flyway does not roll back, so the column and index physically remain after the revert — harmless, and they are dropped by a forward migration if W1a is abandoned instead of fixed. The one behavioural piece a revert restores is the removed manual detail-status endpoint; no client consumes it, and the API answers 500 for that unmapped path today, so a reverted environment must not be relied on for that route until follow-up 4 is fixed.
- **Gaps**: no `CONTRIBUTING.md`/`SECURITY.md`/`docs/**`; no coverage threshold for the API; no rollback plan for W2-A yet.
