# ODD feature: purchase-order-line-tax

**Repository**: LifeControl — `life-control-api` (migrations, `purchaseorder`, `tax`, `salesorder`), `life-control-app-angular` (`features/purchases`), `odd/tasks/`
**Branch**: `feat/purchase-order-line-tax`, created from `origin/main` @ `697a1c0c`; this record is written in that worktree, where the unit's work happens
**Status**: **blocked** — T2, the one task that stands alone, is written: `ZERO_RATE_CODES` moved out of `SalesOrderService` into `TaxCodeService` as the one list both line writers read, with the sales behaviour unchanged and its existing zero-by-definition test as the pin. T1 and T3–T6 wait on `purchase-order-money`, whose T1/T2 — the derivation function this unit extends in place and the document money columns whose `tax_total` it fills — were specified on 2026-11-10 and never written: #840 merged that record alone, the tree's highest migration is still `V91`, and `purchase_order_details` still carries `total` with no `subtotal` beside it. What the unit is: `purchase_order_details` gains `tax_code`, `tax_rate` and `tax_amount`; the code comes from the product and the rate is snapshotted with the order's date through the existing `TaxRateService.resolve`, never from a client and never re-resolved on edit; a taxable code with no enabled rate on that date is a 400 that writes nothing; and `total` starts including the tax while `subtotal` stays the base. Two shapes the product-sourced code cannot express — a supplier exempt on a taxable product, and frontier IVA 8% beside the general 16% — are gaps with their own issues, not silent behaviour.
**Created**: 2026-11-10 · **Risk**: items 1 and 6 apply to T1 and T3–T6 — item 1 because `tax_amount` moves stored money on financial purchase rows and a wrong resolution is not detectable after the fact; item 6 because until T5 gives it a reader, `tax_amount` is a column whose only writer is a trigger fed by a rate resolved at write time. T2, the only task this branch carries, is **none**: it moves a constant into the layer that owns the rule and rewrites no behaviour, and the test that already pinned the sales half proves the half that stayed.

## Problem

The tax machinery exists, is generic, and has **no production caller**. Purchases cannot state a tax at all, and the layer that would let it does not know it is needed.

| Fact | Evidence |
| --- | --- |
| The purchase-order module has no tax anywhere | `grep -i tax life-control-api/src/main/java/com/lifecontrol/api/purchaseorder/` → no matches; no purchase-order migration mentions tax |
| The product already declares its fiscal code | `V89__products_tax_code.sql:52-54` — `products.tax_code VARCHAR(20) NOT NULL DEFAULT 'NA'` with `fk_products_tax_code` |
| The resolver exists and nobody calls it | `tax/service/TaxRateService.java:50-51` (`resolve(companyId, taxCode, documentDate)`) and `:84` (`lineTaxAmount`); its only callers are its own test (`TaxRateServiceTest.java:126,137,139`) and, in production, nothing |
| The zero-by-definition rule lives in a consumer | `SalesOrderService.java:116` — `ZERO_RATE_CODES = List.of("NA","TASA_0","EXENTO")`, applied at `:1082-1084`; `tax/service/TaxCodeService.java:32` states it does not carry the rule |
| One rate per code per date, by schema | `V82__tax_rate.sql:61` — `uq_tax_rate_company_code_from UNIQUE (company_id, tax_code, effective_from)`, read by `findFirstBy…EffectiveFromDesc` (`TaxRateRepository.java:44-45`) |
| The document has no date of its own | `V64__document_identity_per_tenant.sql:187-188` — a purchase order's date is its `created_at` |
| Purchases has no discount, so the tax base is the line subtotal | no discount column on `purchase_order_details` (`V1:341-355`); `lineTaxAmount` takes `discountApplied` and mirrors `ck_item_tax` (`V81:170-171`) |
| The copy precedent, and its exception | `SalesOrderService.java:268,390,737` resolve; `:367-370` and `:791-793` state "no re-resolution, and this is the rule rather than an omission"; `:380-384` and `:802-806` re-resolve only when a line moves to another variant |

## Specs

**S1** — The line states its own tax as a snapshot: `purchase_order_details` gains `tax_code VARCHAR(20) NOT NULL DEFAULT 'NA' REFERENCES tax_code (code)`, `tax_rate NUMERIC(9,6) NOT NULL DEFAULT 0` and `tax_amount NUMERIC(19,4) NOT NULL DEFAULT 0`. Rows that already exist keep `'NA' / 0 / 0`, so no history is rewritten — the rule `V81` and `V89` already applied.

**S2** — The code comes from the product, never from a client. Creating the order with lines and adding a line both read `products.tax_code` through the variant, with the scoped query shape `ProductVariantRepository.java:140-146` already uses. `PurchaseOrderDetailRequest` gains no field.

**S3** — The rate is snapshotted with the document's date, and editing never re-resolves. The resolution is `TaxRateService.resolve(companyId, products.tax_code, createdAt.toLocalDate())`, taken when the line is created; a later change of quantity or unit price keeps the stored `tax_code` and `tax_rate`, and the stored rate is what keeps driving the derived amounts. The single exception is a line **moved to another variant**, which re-resolves because it is no longer the article whose tax was copied — the rule sales already states at `:380-384`.

**S4** — The zero-by-definition codes resolve to rate 0 without consulting `tax_rate`, and the list lives in the tax layer. `ZERO_RATE_CODES` moves out of `SalesOrderService.java:116` so that sales and purchases read **one** list: a rule with two consumers must not live inside one of them, and `TaxCodeService.java:32` already declares it carries no such rule. Sales switches to the shared list in this slice, and its existing behaviour is pinned by a test before the move.

**S5** — A taxable code with no enabled rate on the document's date is a **400** naming the code and the date, and writes nothing. This is the shape `SalesOrderService.java:1090-1094` already uses, and it is the only thing standing between a product-sourced code and a silent zero — including the two shapes the source cannot express.

**S6** — The arithmetic is one function over three columns: `tax_amount = round(subtotal * tax_rate, 4)` (purchases has no discount, so `lineTaxAmount` applies with `discountApplied` at 0, and the helper is reused rather than re-expressed), and `total = subtotal + tax_amount`, derived in the **same** function and the same trigger that derive `subtotal`. A second trigger for `tax_amount` would sort after the money trigger and read it stale — the reason `V85:69-71` gives for sales.

**S7** — The document sums the tax through the function `purchase-order-money` wrote, extended in place: `tax_total = sum(details.tax_amount)` and `total_amount = subtotal + tax_total`, which `ck_po_order_total` already states. No new document column and no second derivation path.

**S8** — The wire states what was copied: `PurchaseOrderDetailResponse` gains `taxCode`, `taxRate` and `taxAmount`, and the document's `taxTotal` starts carrying a non-zero value. Both request records stay unchanged.

**S9** — The tax never reaches inventory cost. `unit_price` does not feed `cost_price` today and this unit does not connect them; whether a non-deductible tax belongs in cost is a separate accounting decision, recorded as a gap by `purchase-order-money`'s D6.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S1, S6, S7 | Migration: the three line columns with their FK and defaults, the derivation of `subtotal`/`tax_amount`/`total` in the existing function, and the document's `tax_total` in the existing document function | money's T1, T2 | worker | — |
| T2 | S4 | Move `ZERO_RATE_CODES` into the tax layer, switch sales to it, and pin the sales behaviour before the move with the test that already exists | — | inline | `refactor(tax)` |
| T3 | S2, S3, S5 | The copy: the create-with-lines path, `addPurchaseOrderDetail`, `updatePurchaseOrderDetail` and its variant-change exception, plus the 400 refusal | T1, T2 | worker | — |
| T4 | S3, S4, S5, S6 | The four mirror tests — snapshot on create, zero-by-definition without touching `tax_rate`, refusal writes nothing, edit does not re-resolve — and one migration-suite case asserting the derived `tax_amount` and `total` | T3 | worker | — |
| T5 | S8 | Angular: the tax column in the detail table and the document breakdown (subtotal, tax, total) through `MoneyPipe` | T3 | inline | — |
| T6 | S1–S9 | Docs and this record's evidence log | T4, T5 | inline | — |

**Total task count: 6.**

## Decisions

**D1 (2026-11-10) — the code comes from the product.** The operator's decision, verbatim: «Del producto, como ventas». Cost recorded rather than argued: two shapes become inexpressible, and they are named below instead of being discovered in production.

**D2 (2026-11-10) — the snapshot is taken at creation, with the order's date.** The operator's decision, verbatim: «Al crear la orden (como ventas)». The accepted consequence is that an order created in December and received in January keeps December's rate. The alternative — resolving at receipt — leaves the document's tax unknown until goods arrive and needs a rule for partial receipts, which is a larger decision than the one taken.

**D3 (2026-11-10) — the zero-by-definition list moves to the tax layer.** It is a rule about what a code *means*, and the second consumer is what makes the extraction legitimate rather than a refactor for its own sake. Sales keeps behaving identically, pinned before the move.

**D4 (2026-11-10) — the tax base is the line's `subtotal`.** Sales computes on `subtotal - discount_applied`; purchases has no discount, so the same expression applies with the discount at zero, and `lineTaxAmount` is reused instead of duplicated.

**D5 (2026-11-10) — one function derives three columns.** Same-event triggers fire in name order, so a separate `tax_amount` trigger would be read stale by the money trigger that follows it — the defect `V85:69-71` documents for sales.

## The two gaps this unit carries

Neither is resolved here; each is a decision of its own, and both are invisible to a test because the input is valid:

1. **A supplier exempt on a taxable product.** The product says `IVA`, the supplier invoices exempt. The line computes a tax that is not owed — an invented deductible tax, not a rounding difference.
2. **Frontier IVA 8% beside the general 16%.** Both are the code `IVA`, and `uq_tax_rate_company_code_from` plus `findFirstBy…EffectiveFromDesc` allow exactly one rate per code on one date, so there is nowhere to state the 8%.

S5 is the honest mitigation: where the resolution cannot answer, the request is refused by name instead of defaulting to zero.

## Log

**L1 (2026-11-10) — the decisions that produced this record, in the operator's selected words.** «Del producto, como ventas» (the code comes from the product, not from the supplier, the pair or the client); «Al crear la orden (como ventas)» (snapshot at creation); «Sí, pero en un ODD anterior separado» (the chain); «No, y se registra el hueco» (the inventory cost stays out).

**L2 (2026-11-10) — the measurement.** `TaxRateService.resolve` and `lineTaxAmount` exist with no production caller; `ZERO_RATE_CODES` is hardcoded in `SalesOrderService.java:116`; the purchase-order module has zero occurrences of `tax`; and `V82:61`'s unique key is what makes the vocabulary unable to hold two rates for one code.

**L3 (2026-11-10) — why this record opens with `deferred`.** Its work is specified and its source cannot land before `purchase-order-money`'s derivation function exists, so claiming it delivered would be false in the one place nobody revisits. The header states the unit's content and the chain; the delivery evidence belongs in dated rows here.

**L4 (2026-11-10) — the correction this record forced on its predecessor.** Writing this spec exposed that a single `total` cannot start including tax once rows exist, so `purchase-order-money`'s S2 was amended to derive `subtotal` beside `total` and its D5 to say why. The correction landed in the same session, before any source line, which is the only window where it is free.

**L5 (2026-11-10) — the smallest set of tests to mirror, from the sales slice that already paid for them.** `SalesOrderServiceTest.java:4135` (a line asks for the product's code and the document's date and stores both), `:4182` (a zero-by-definition code answers 0 without asking the rate table), `:4165` (a taxable code with no rate is refused), `:4203` (editing never resolves). The schema side mirrors `SalesMoneyBreakdownMigrationIntegrationTest.java:345,411` for the derived amount, and `TaxRateServiceTest.java:126-139` for rounding parity with the check constraint.

**L6 (2026-11-10) — the measurement that stopped T1, and the one task that does not need it.** Starting the unit checked its own dependency instead of trusting the record it names, and the dependency is documentation only. `git diff --stat d7e2544a^1 d7e2544a` (#840, `purchase-order-money`) is two files, both `odd/tasks/*.md`, +177; the merge's second parent ends at `d8ff39a6 docs(odd): specify the purchase-order money layer`; the highest migration in `main` is `V91__seed_tax_code_process.sql`; and `subtotal` appears in neither `purchaseorder/model/PurchaseOrderDetail.java` nor `purchaseorder/dto/PurchaseOrderResponse.java`. #843 does the same to two more records: its diff against its first parent is `odd/tasks/*.md` only, so `purchase-order-create-then-lines` and `purchase-order-detail-endpoints` are specified and not written — `PurchaseOrderService.java:445` still calls `detailStateOf(request.statusId())` with no fallback, and `:480` and `:517` still resolve a line by `findById(detailId)` alone. T1 has nothing to extend, so no line of it can land before money's T1/T2; T2 has no dependency, which is why the header's token is `blocked` rather than `deferred`. Three neighbours were checked and are real: `V30` for the fractional quantity, `numberedAt` at `PurchaseOrderService.java:196-227`, and `companyResolved` at `company-info-section.ts:84`.

**L7 (2026-11-10) — T2, and the sentence it had to delete rather than move.** `TaxCodeService` carried a paragraph headed *What this class deliberately does not do*, saying the zero-by-definition rule belongs to the resolver and not to a catalog read; D3 reverses that, so the paragraph is rewritten instead of left standing beside a constant that contradicts it. The constant moved with its rationale intact (why these three, why a `List`, why the name alone decides) and gained `isZeroRateByDefinition`, so the second consumer in T3 reads the answer rather than re-implementing `contains`. The pin is the sales test that already existed (`addItem_withAZeroByDefinitionCode_neverAsksTheRateTable`) and it was run **before** the move on the untouched tree — `BUILD SUCCESSFUL` — so the move is proven behaviour-preserving rather than assumed to be. Sales still names the same three codes in its refusal sentence, now through `TaxCodeService.ZERO_RATE_CODES`. The route became `inline` rather than the planned `worker`: one constant, one helper and one consumer is smaller than a writer start, and no delegation trigger held.

**L8 (2026-11-10) — the block is lifted, measured rather than assumed.** L6's measurement was correct on the day it was taken and is now stale: `purchase-order-money`'s T1/T2 landed as V92 and V93, and `main` at `08964f79` carries both — `purchase_order_details` has `subtotal` beside `total`, `purchase_order_detail_money()` and `purchase_order_money()` exist and are the two functions this unit extends in place, and `purchase_order_details` carries the four money columns the document derivation reads. So T1 has something to extend and T3–T6 follow it; the header's `blocked` token is rewritten when this unit's own work is done, not while it is in flight, because the closed vocabulary has no word for that state and inventing one would be the live-state claim the record rules forbid. What did not change: the two gaps, the decisions, and the specs — the money layer was specified expecting exactly this successor.

**L9 (2026-11-10) — a citation the refusal inherits, checked instead of copied.** T3's 400 mirrors the sentence sales already uses (`SalesOrderService.java:1072-1077`), which is what S5 asks for, so purchases now says "the purchase is refused" in the same words. Verifying that sentence against the ADR found it half right: `ADR-0002 §Decision 6` is genuinely about this ("Tax is a rate snapshot, not a calculation in the UI… copied onto the line at write time, so a rate change never rewrites history"), and the `D-a` tag beside it is not — `D-a` is the backfill of a `Completed` sale with no payment method (`docs/adr/0002-money-first-class.md:8`). The wrong half predates this unit, lives in sales, and is deliberately not corrected here: changing sales' message is another unit's diff, and diverging purchases from it would leave two texts for one refusal. It is recorded so the next reader does not copy the pair a third time — the tag goes when sales is next touched, not by silently forking the wording.

**L10 (2026-11-10) — `S5`'s "writes nothing" means no row, and the sequence is where that sentence needs a second clause.** T4 found the honest problem with the spec rather than with the code: `createPurchaseOrder` allocates the document number at `PurchaseOrderService.java:217`, before the line loop that resolves the tax at `:265`, so a refused request has already taken a value from the sequence by the time the 400 is thrown. The test pins the observed behaviour with the reason beside it instead of asserting a property the code does not have.

Two things follow, and only one of them is this unit's:

- **Sequences are not gap-free and PostgreSQL does not promise they are.** `nextval()` is non-transactional by design, so any refusal that happens after an allocation burns a number, and so does any failure between the allocation and the commit. Moving the allocation below the line loop would reduce how often that happens; it would not make "no consumption" true, and asserting it would be asserting something the database will not honour. What S5 owes is that the refusal leaves **no order row, no detail row, and no document change**, and that is what test 3 asserts.
- **Moving the allocation is a change to the identity path, not to this unit.** ADR-0003 decision 4 and the comment at `:213-216` state why the number is taken as part of the document's identity, and an existing expectation in `CreatePurchaseOrderTests` would move with it. That is its own small unit with its own review, named here so it is not rediscovered as a bug: the sequence gap is real, its cost is a skipped number, and it is not a state this tree can corrupt.
