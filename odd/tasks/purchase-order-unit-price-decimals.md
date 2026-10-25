# ODD feature: purchase-order-unit-price-decimals

**Repository**: LifeControl — `life-control-api/` only: one constraint on a record DTO and the
integration test that pins it. No migration, no entity, no service rule, no wire field, no stored data.
**Status**: implemented — `unitPrice` on a purchase order line accepts at most two decimals, so the price
the write routes echo is a price `DECIMAL(12,2)` can hold; a third decimal answers 400 with nothing
persisted. Nothing is deferred.
**Created**: 2026-10-25 · **Base**: `main` @ `99190423` · **Branch**:
`fix/purchase-order-unit-price-decimals` · **Worktree**:
`~/workspace/LifeControl-worktrees/fix-purchase-order-unit-price-decimals`
**Risk**: **none**. One Jakarta constraint on a field of a request DTO: no stored data or format changes,
no secret, no permission, no concurrency, no pipeline, no dependency. Tightening this field *is* a contract
change for the Angular client, and it is the change issue #594 asked for, which is the explicit exception
in item 3; the pre-existing behaviours that share the field are pinned by tests that pass on both trees
(`S5`), so a regression is detectable rather than silent.

## Specs

**S1 — the request, verbatim**: `si dale a 594`.

**S2 — the outcome, verbatim from issue #594** (`status:approved` before the pull request opened):

> `PurchaseOrderDetailRequest.unitPrice` valida `@DecimalMin("0.01")` pero **no lleva `@Digits`**
> (`life-control-api/src/main/java/com/lifecontrol/api/purchaseorder/dto/PurchaseOrderDetailRequest.java:20-22`),
> así que un precio de tres decimales se acepta.
>
> La columna `unit_price`/`total` es `DECIMAL(12,2)`: el precio se guarda redondeado, mientras la respuesta
> del POST/PUT repite el valor sin redondear. La misma discrepancia de dinero que el PR de fracciones cerró
> para `total` (`PurchaseOrderService.lineTotal`), pero del lado del precio de entrada.
>
> `@Digits(integer = 10, fraction = 2)` en `unitPrice`, igual que ya se hizo con `quantity`, más un test de
> 400 con un precio de tres decimales y nada persistido.

**S3 — the defect, measured on this tree, not read.** Before any source edit the new rejection test ran
against `main` @ `99190423` and failed with `Status expected:<400> but was:<201>`
(`PurchaseOrderDetailVariantIntegrationTest$UnitPriceScaleTests.moreThanTwoDecimalsRejected`, 21 tests in
the class, 1 failure). The two halves of the discrepancy come from two places that agree on the schema and
not on the response: `V1__baseline_schema.sql:346` declares `unit_price DECIMAL(12,2) NOT NULL CHECK
(unit_price >= 0)`, so PostgreSQL rounds what Hibernate writes verbatim; and every write route answers with
`toResponse(saved)`, which maps `detail.getUnitPrice()` from the **in-transaction entity**
(`PurchaseOrderService:781-868`), whose `BigDecimal` still carries the third decimal the caller sent
because nothing re-reads the row. The client therefore received a price the database never stored.

**S4 — the fix, and the bound that justifies its numbers.** `@Digits(integer = 10, fraction = 2, message =
"unitPrice must have at most two decimals")`, the same shape `quantity` already carries
(`PurchaseOrderDetailRequest:15-18`). `10 + 2` is not a round number chosen to fit the test: it is the
precision of the column, whose maximum is `9999999999.99`. The annotation is on the DTO, so it covers
**all four** write routes that take it — create order, update order, add line, update line
(`PurchaseOrderService:212, 288, 384, 423`) — and it also turns a price wider than the column into the 400
it always should have been instead of the numeric-overflow `500` it produced before.

**S5 — what must keep working, and what proves it.** A two-decimal price still answers 201, echoes
`123.45`, and persists `123.45` (`twoDecimalPriceUnchanged`); a whole price behaves as before. That test
was written in the same batch as the rejection test and **passed on the pre-fix tree**, which is the
differential check: the same assertion, the same result, on both sides of the change. Everything else that
shares the DTO — the nested-line create and update routes, the default-`Pending` derivation, the variant
ownership rule, the whole goods-receipt boundary — is covered by the suites listed in the evidence log.

**S6 — what this deliberately does not do.** It does not add a client-side validator to
`detail-table.html`: the price input already carries `step="0.01"`, and the quantity input next to it has
had the same shape since `feat/purchase-order-fractional-quantity` — a hand-typed third decimal reaches the
API and the API refuses it. Making the Angular form reject it before the round trip is a separate unit with
its own tests, and doing it here would change a screen nobody asked this slice to change.

## Tasks

| ID | Spec | Task | Commit |
| --- | --- | --- | --- |
| T1 | S1 | Approve #594 (`status:approved`) and create the unit's worktree off `origin/main` @ `99190423` | — |
| T2 | S3 | RED: the rejection test in the `unit price scale` nested class | (lands with T3) |
| T3 | S4 | GREEN: `@Digits(integer = 10, fraction = 2)` on `unitPrice` | `fix(purchases)` |
| T4 | S5 | Focused suites, the static-analysis gate and the full API suite | — (checks) |
| T5 | — | This record, with the evidence it cites | `docs(odd)` |
| T6 | S6 | The Angular-side precision guard, on its own decision | — (not scheduled) |

## Log

**L1** *(the request, verbatim — `S1`)*: `si dale a 594`.

**L2** *(where #594 came from, so the record is not a root)*: the issue is the follow-up the independent
verification of `feat/purchase-order-fractional-quantity` declared — `odd/tasks/purchase-order-fractional-quantity.md`
(`L12`) — because that unit closed the `total` half of the money discrepancy and left the input price as it
was. Nothing in that record is rewritten here.

**L3** *(the two scoping decisions — `S3`, `S6`)*: the fix is a rejection, not a silent rounding, because
the column scale is the contract and a price the caller cannot see is worse than a refused write; and the
Angular form stays out, because the sibling field already sets that precedent and changing it would touch a
screen this issue does not name.

## Evidence log

**2026-10-25 — the unit, and the pre-edit measurement.** `./.github/scripts/new-unit.sh
fix/purchase-order-unit-price-decimals` created the worktree off `origin/main` @ `99190423` (anchor clean,
`git status --short` empty). The rejection test was written first, in a new `unit price scale` nested class
inside `PurchaseOrderDetailVariantIntegrationTest`, next to the `fractional ordered quantity` class that
pins the sibling field. `./gradlew test --tests
"com.lifecontrol.api.purchaseorder.PurchaseOrderDetailVariantIntegrationTest"` → `BUILD FAILED`,
`Status expected:<400> but was:<201>`: the class ran **21 tests, 1 failure**, and the failure is the new
one. The pre-existing pin (`twoDecimalPriceUnchanged`) passed there, on the tree without the fix.

**2026-10-25 — GREEN, focused.** The same suites after adding the annotation:
`./gradlew test --tests "com.lifecontrol.api.purchaseorder.*" --tests "com.lifecontrol.api.goodsreceipt.*"`
→ `BUILD SUCCESSFUL in 42s`, **212 tests, 0 failures, 0 errors, 0 skipped** across 59 classes, including
`unit price scale` (2), `fractional ordered quantity` (4), `line validation` (12) and `Goods Receipt
Integration Tests` (8).

**2026-10-25 — the gates CI runs.** `./gradlew spotlessCheck spotbugsMain` → `BUILD SUCCESSFUL in 33s`.
`./gradlew test` (the full API suite, the same command as the `Test` job of `api-ci.yml`) → `BUILD
SUCCESSFUL in 3m 22s`, **4001 tests, 0 failures, 0 errors, 0 skipped**.

**2026-10-25 — the diff.** Two files: the DTO (+1 line, the annotation) and the integration test (+51: one
nested class, the rejection test and the pin), plus this record. `git diff --stat` shows nothing else
moved — in particular no migration, no entity and no Angular file, which `S6` requires.
