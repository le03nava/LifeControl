# ODD feature: purchase-order-line-tax

**Repository**: LifeControl — `life-control-api` (migrations, `purchaseorder`, `tax`, `salesorder`), `life-control-app-angular` (`features/purchases`), `odd/tasks/`
**Branch**: `feat/purchase-order-line-tax`, rebased onto `origin/main` @ `08964f79` — the money unit it depends on is merged; this record is written in that worktree, where the unit's work happens
**Status**: **implemented** — the unit is the purchase line's own tax: `purchase_order_details` carries `tax_code`, `tax_rate` and `tax_amount`; the code comes from the product, the rate is a snapshot taken with the document's date, a taxable code with no enabled rate on that date is a 400 that writes nothing, and `total` includes the tax while `subtotal` stays the base. The derivation extends V92's line function and V93's document function **in place** — no third trigger, because same-event triggers fire in name order. Two shapes the product-sourced code cannot express were named here on 2026-11-10: one is a named gap with its own issue (frontier IVA 8% beside the general 16%, #855) and the other — a supplier exempt on a taxable product — was answered on 2026-11-12 as a decision of the domain, because the tax code is the product's and a supplier carries no fiscal treatment here (#852, `L17`). The inventory cost stays out by decision (S9).
**Created**: 2026-11-10 · **Risk**: item 1 applies — `tax_amount` moves stored money on financial purchase rows, and a wrong resolution is not detectable after the fact: the rate is copied at creation, so a wrong copy is a wrong invoice line that no later correction re-derives. Item 6 does **not** apply: T4's schema case and T5's screen both read `tax_amount` in the same branch. T2 was **none** — it moved a constant into the layer that owns the rule, proven by the sales test that already pinned the half that stayed.

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

**S3** — The rate is snapshotted with the document's date, and editing never re-resolves. The resolution is `TaxRateService.resolve(companyId, products.tax_code, createdAt.toLocalDate())`, taken when the line is created; a later change of quantity or unit price keeps the stored `tax_code` and `tax_rate`, and the stored rate is what keeps driving the derived amounts. The single exception is a line **moved to another variant**, which re-resolves because it is no longer the article whose tax was copied — the rule sales already states at `:380-384`. That rule governs a row that already exists. A **full replacement** — the `PUT` that re-sends the document's whole line set — soft-deletes the rows and creates new ones, so each new line copies what a created line copies: the product's current code and the rate effective on the document's `created_at`, never the instant the edit runs. The consequence is stated rather than left to be discovered: the snapshot moves only when the product's `tax_code` changed, or when a `tax_rate` row with `effective_from <= created_at` was published **after** the order was created; a rate published later than the document's date cannot move it. `L14` records the review finding this sentence answers, and T4 pins both halves.

**S4** — The zero-by-definition codes resolve to rate 0 without consulting `tax_rate`, and the list lives in the tax layer. `ZERO_RATE_CODES` moves out of `SalesOrderService.java:116` so that sales and purchases read **one** list: a rule with two consumers must not live inside one of them, and `TaxCodeService.java:32` already declares it carries no such rule. Sales switches to the shared list in this slice, and its existing behaviour is pinned by a test before the move.

**S5** — A taxable code with no enabled rate on the document's date is a **400** naming the code and the date, and leaves nothing behind. On the paths that create a line it is thrown before the first write; on `updatePurchaseOrder`, which replaces a document's lines, the existing rows are soft-deleted before the resolution runs and what keeps the refusal from leaving a half-replaced document is the caller's transaction, not the order of the statements — the distinction `L13` records, because the first version of this spec claimed both. This is the shape `SalesOrderService.java:1090-1094` already uses, and it is the only thing standing between a product-sourced code and a silent zero — including the two shapes the source cannot express.

**S6** — The arithmetic is one function over three columns: `tax_amount = round(subtotal * tax_rate, 4)` (purchases has no discount, so `lineTaxAmount` applies with `discountApplied` at 0 — **but the derivation is written in plpgsql, not through that helper**, which still has no production caller and is now the second of three expressions of one rule, the third being the `ck_item_tax` the sales row is checked against; `L13` names it as a gap instead of claiming a reuse that does not exist), and `total = subtotal + tax_amount`, derived in the **same** function and the same trigger that derive `subtotal`. A second trigger for `tax_amount` would sort after the money trigger and read it stale — the reason `V85:69-71` gives for sales.

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

## Slices

The unit is larger than the ~400-authored-line review budget, so it travels as chained slices — and each boundary is
verified on its own tip before its PR opens, because the predecessor unit paid for that lesson in red: `V92`/`V93` alone
derived four decimals while the response still carried Java's two, so a "schema only" slice was never green.

| Slice | Tasks | Scope | Why the boundary falls here |
| --- | --- | --- | --- |
| 1 | T2, T1 | the rule and the schema: `ZERO_RATE_CODES` in the tax layer, and `V94` | A column list with no writer is not reviewable alone; the rule both line writers read belongs with the columns they fill |
| 2 | T3, T4 | the copy, the refusals, and the tests that pin them | The service is what decides a code and a date, and the tests are only meaningful against it |
| 3 | T5, T6 | the screen and the docs | Nothing below changes, and it is the only slice reviewable by looking at rendered numbers |

## Decisions

**D1 (2026-11-10) — the code comes from the product.** The operator's decision, verbatim: «Del producto, como ventas». Cost recorded rather than argued: two shapes become inexpressible, and they are named below instead of being discovered in production.

**D2 (2026-11-10) — the snapshot is taken at creation, with the order's date.** The operator's decision, verbatim: «Al crear la orden (como ventas)». The accepted consequence is that an order created in December and received in January keeps December's rate. The alternative — resolving at receipt — leaves the document's tax unknown until goods arrive and needs a rule for partial receipts, which is a larger decision than the one taken.

**D3 (2026-11-10) — the zero-by-definition list moves to the tax layer.** It is a rule about what a code *means*, and the second consumer is what makes the extraction legitimate rather than a refactor for its own sake. Sales keeps behaving identically, pinned before the move.

**D4 (2026-11-10) — the tax base is the line's `subtotal`.** Sales computes on `subtotal - discount_applied`; purchases has no discount, so the same expression applies with the discount at zero, and `lineTaxAmount` is reused instead of duplicated.

**D5 (2026-11-10) — one function derives three columns.** Same-event triggers fire in name order, so a separate `tax_amount` trigger would be read stale by the money trigger that follows it — the defect `V85:69-71` documents for sales.

## The two gaps this unit carried, and where each stands

Both were named here on 2026-11-10 as cases the input cannot detect, because each is a decision of its own. One has since been answered by a decision of the domain and the other is still open:

1. **A supplier exempt on a taxable product — closed by decision, 2026-11-12, no code.** «el tax code se define a nivel producto no hay proveedor excento» (issue #852). The tax code is a property of the **product**; a supplier has no fiscal treatment in this domain and no column anywhere carries one (`suppliers` holds name, `razonSocial`, `rfc`, contact, address and `enabled`, and nothing else). So the case the gap described — a line whose supplier *says* exempt — cannot be produced by any write this tree accepts: the code the line copies is the product's, which is also why `lineTaxFor` reading `products.tax_code` through the variant is the whole rule rather than half of one. What would reopen it is a supplier fiscal profile (a code, a boolean, a régimen, a constancia), and that is a different decision with fiscal consequences of its own, not a field to slip under a bug fix. Invariant the decision buys: **a purchase line's tax code is exactly what its product declares**, which is checkable and is what the existing tests already pin.
2. **Frontier IVA 8% beside the general 16% — still open (issue #855).** Both are the code `IVA`, and `uq_tax_rate_company_code_from` plus `findFirstBy…EffectiveFromDesc` allow exactly one rate per code on one date, so there is nowhere to state the 8%.

S5 is the honest mitigation for the second: where the resolution cannot answer, the request is refused by name instead of defaulting to zero. The first needs no mitigation, because it has no input.

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

**L11 (2026-11-10) — T5 was verified twice, and the second run is the one that counts.** The writer worked in a worktree with no `life-control-app-angular/node_modules`, so it proved its change in a scratch copy under `/tmp` and said so instead of reporting a green it had not measured on the tree. That is the right call, and it is also not verification: the parent installed the dependencies in the worktree (`npm ci`) and ran the gate on the real tree — `src/features/purchases` 20 files / 461 tests, `npm run lint` clean, and `npm run test:coverage:check` at 200 files with 86.7% statements, 86.4% branches, 89.64% lines. The scratch copy and its 357 MB were removed. The lesson generalises past this unit: a worktree is not the anchor, and "it passed somewhere with the same sources" is not the same claim as "it passed here".

**L12 (2026-11-10) — what the docs say now, and one row that had to be revised rather than appended.** `AGENTS.md`'s `## Dinero de la orden de compra` grew five rows — the line's tax columns, why the rate is a column and not a join, the edit rule with its single exception, the refusal and what the sequence does lose, and the zero-by-definition codes owning one list — and its "who writes it" row now names all three derivations instead of two. The "what it does not cover" row was **rewritten**: it used to say the tax was the successor and `tax_total` derived to 0, which is a sentence about a past this tree no longer has; it now carries the inventory-cost decision and the two gaps that belong to the product vocabulary. That is the same failure the money unit's `V94` fixed in a column comment, and it is worth naming: a doc row that promises a successor becomes false the moment the successor lands, and only a reader who comes back to it will notice.

**L13 (2026-11-10) — the independent verification, and the two sentences it made me rewrite.** An independent pass over `08964f79..abfc43df` gave all nine specs `PASS` with no blocker, and measured rather than trusted: it read `pg_get_triggerdef` from a real database to see `BEFORE INSERT OR UPDATE OF unit_price, quantity, tax_rate` with `tax_amount` absent, ran eight of its own probes (a rate-only update re-deriving the amount and the document, a soft delete and a re-enable keeping `ck_po_order_total`, a hand-written total refused, an undeclared code refused by the FK, a negative rate refused by the `total >= 0` check), and confirmed `L10`'s ordering by reading it in the tree rather than believing the record.

Two claims did not survive, and both were mine:

- **`S5` said the refusal is thrown "before the first write on every path".** True for the create and the add-a-line paths; false for `updatePurchaseOrder`, which soft-deletes the document's existing lines before it resolves the tax for the replacements. Nothing half-written escapes, but the guarantee is the transaction's, not the statement order's, and the spec now says so.
- **`S6` said `lineTaxAmount` "is reused rather than re-expressed".** It is not: the arithmetic lives in the plpgsql function, the helper still has no production caller, and the rule now has three expressions (that helper, the trigger, and the `ck_item_tax` sales rows are checked against). The values agree where they were probed; the claim of reuse does not exist, and the spec now names the duplication as a gap rather than asserting a reuse.

Five further advisories are recorded and not fixed, because each mirrors a shape the sales row already has or is unreachable from any endpoint: `tax_rate` carries no `0..1` check (a negative hand-edit is refused one step later by `total >= 0`, a rate above 1 is accepted); a derived column is not a re-derivation input, so a hand-written `subtotal` leaves the line stale while the document recomputes (the same class `purchase-order-money` declared); a **backdated** `tax_rate` row restates an existing line when `updatePurchaseOrder` replaces it, where `S3`'s only stated exception is a variant move; `PurchaseOrderDetail.taxCode` defaults to `'NA'`, so a future path that forgets `lineTaxFor` silently writes an untaxed line instead of failing; and the FK and negative-rate refusals are pinned by the schema alone, with no committed test, because the probe that measured them was the verifier's and adding it would have grown this unit past what its review can carry.

**L14 (2026-11-10) — an escalated review finding, and why the answer was a sentence plus a test rather than a guard.** The native review of `08964f79..331bc343` did not approve: it closed `escalated` with cause `unknown_causality` and one finding, `R4-1` (resilience, `CRITICAL`, `PurchaseOrderService.java:364`), whose claim is that the full-replacement branch re-resolves the tax for every replacement line and so restates a stored snapshot when a product's code changes or a backdated rate appears. The provider **downgraded** that finding as `unverified_location` and escalated anyway, and it offers no continuation (`native_stop_required`, `automatic_retry: false`), which is why this is a maintainer decision and not a correction I could run.

The finding describes the code correctly. It was also seen independently: the verification pass had already recorded the same behaviour as an advisory (`A3`) and noted that sales makes the same choice. Two instruments agree the behaviour exists; they disagree on its severity, and that disagreement is exactly what causality `unknown` means here.

**Decision (operator, 2026-11-10): specify it and pin it.** Not a guard, because the `PUT` carries no line identity — product, variant, quantity, price and comments, nothing that says "this is the line that was there" — so "keep the snapshot of the same line" is not implementable, and a heuristic match on the variant would refuse legitimate replacements. What the unit owes instead is that the behaviour is written down, that the damage is bounded and measured, and that a future reader does not rediscover it as a bug:

- the rule now distinguishes **a row that exists** (editing never re-resolves) from **a line set that is replaced** (new rows, new copy against `created_at`);
- the two conditions that actually move the snapshot are named, and the one that cannot — a rate published after the document's date — is named too;
- T4 gains a case with both halves, so half one proves a normal rate change restates nothing and half two pins the restatement as intended.

What this does **not** claim: that the behaviour is safe for a company that backdates rates or recodes products, and that a full replacement is a bad way to correct a line — a document re-sent to fix a comment re-copies every line's tax. If that becomes a real operator flow, the answer is an endpoint that edits one line, not a guard inside this one.

**L17 (2026-11-12) — gap 1 answered as a decision of the domain: a supplier has no fiscal treatment here.** The
question #852 asks — "either refuse naming both facts, or override and record who stated it" — assumes the
supplier carries a tax condition, and the answer is that it does not: «el tax code se define a nivel producto no
hay proveedor excento». So the honest resolution is neither of the two the issue offers, and it is written where
the gap was named rather than as a code change: the code a purchase line copies is the product's, an invariant
that already holds and that the existing cases pin, and both candidate shapes (a `suppliers.tax_code`, a
boolean) are what would *create* the ambiguity rather than remove it. Recorded, not silently dropped: what would
reopen this is a supplier fiscal profile, which is a decision with fiscal consequences of its own — régimen
fiscal, constancia, whether a supplier's exemption can override a product's IVA at all — and not a column to
add under a bug. Issue #855 (frontier IVA 8%) is untouched and stays open.
