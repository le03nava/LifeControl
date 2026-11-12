# tax-single-expression-and-citation

**Status**: implemented — the tax rule `tax_amount = round(base × rate, 4)` stops having a third statement in Java
(`TaxRateService#lineTaxAmount` is deleted, its only consumer was its own test), and the two refusals that read it
out loud stop citing `ADR-0002 decision 6, D-a`, because `D-a` is the payment backfill and says nothing about tax.
Issues #854 and #856. Branch `chore/tax-single-expression-and-citation`, base `origin/main` @ `23e8bc09`. Nothing
is deferred. What the two schema expressions still say is stated in `S3` rather than left implicit.
**Created**: 2026-11-12 · **Risk**: none — no stored data changes shape, no migration, no credential and no
contract: the schema stays authoritative for the arithmetic (`ck_item_tax` and `purchase_order_detail_money()` are
untouched) and the refusal keeps its status, its code, its date and its zero-by-definition codes. Deleting a
`public static` helper with zero production callers does not change any caller; the measurement is in `L2`.

## Specs

Quoted verbatim from the two issues, which are the request:

**#854 — `chore: the tax arithmetic exists in three expressions, and one of them has no caller`**

- **S1** — "A test fails when the Java helper and the plpgsql function disagree on a chosen set of `(base, rate)`
  pairs, including the 4-decimal case where two decimals lose a digit: `37.6675 × 0.1625 = 6.1210`, not `6.12`."
- **S2** — "Or the helper is deleted or rewired onto the real path, and the duplication is gone — with
  `ck_item_tax` unchanged and a test proving sales' stored rows still satisfy it." **This is the path taken**,
  chosen because the other one keeps a dead helper alive only to test it: a second Java statement of a rule the
  schema owns is the duplication, not the fix for it.
- **S3** — the two expressions that remain are the database's and are **not** one text: `ck_item_tax` states
  `round((subtotal - discount_applied) * tax_rate, 4)` on sales and `purchase_order_detail_money()` states
  `round(NEW.subtotal * NEW.tax_rate, 4)` on purchases, because a purchase line has no `discount_applied`. They are
  the same rule with the discount term at zero, and neither is Java's to restate.
- **S4** — "The zero-by-definition behaviour (rate 0 without consulting `tax_rate`) keeps its existing test green
  before and after."

**#856 — `chore: the tax refusal message cites ADR-0002 decision 6 with a D-a tag that is about the payment backfill`**

- **S5** — "One sentence, correct in both places: decision 6 alone, or a citation that resolves to a statement
  about tax." The `D-a` half goes; the behaviour of the refusal does not change — the code, the date and the
  zero-by-definition codes stay, and both still answer 400.
- **S6** — "No occurrence of `decision 6, D-a` remains under `life-control-api/`."
- **S7** — "A check fails if the pair comes back — a repository grep suite or a message assertion, whichever the
  tree already uses for this kind of rule." The message assertion is the arm this tree already has in both refusal
  tests, so the guard is two assertions rather than a new shell suite.

## Tasks

| ID | Spec | Route | Evidence |
| --- | --- | --- | --- |
| T1 | S2 | worker | caller count measured before any deletion |
| T2 | S1, S2 | worker | the helper and its orphaned cases deleted |
| T3 | S2, S3 | worker | the stored `tax_amount` proven by a DB-reading case, not by a second formula |
| T4 | S4 | worker | the zero-by-definition cases unchanged and green |
| T5 | S5, S6 | worker | both messages cite the decision alone; the pair is gone from `life-control-api/` |
| T6 | S7 | worker | both refusal assertions pin the citation and refuse `D-a` |
| T7 | S1–S7 | parent | full `./gradlew test`, `spotlessCheck`, the shell suites |

## Log

**L1 (2026-11-12) — the request, verbatim.** «ok dale con 854 y 856 juntas». The two issues travel in one branch and
one pull request because that is the order the operator gave; the commits stay separate so each can be read alone.

**L2 (2026-11-12) — the measurement that licenses the deletion, taken before it.** `lineTaxAmount` had **5**
references in the whole worktree and none of them was production code: the declaration
(`TaxRateService.java:275`) plus four test-side hits (`TaxRateServiceTest.java:31` javadoc, `:135`, `:146`,
`:148`). `api-gateway/` has zero occurrences. The two hits under `life-control-app-angular/` are an unrelated
TypeScript component method (`sales-order-item-table.ts`/`.html`) that shares a name and nothing else. So the
issue's premise — "one of them has no caller" — is confirmed by counting rather than by trusting the table.

**L3 (2026-11-12) — S1 was the other path, and the reason it was not taken.** A test that compares the Java helper
with the plpgsql function keeps the dead helper alive and gives it a job: being checked against the thing that
actually runs. That inverts the cost — a second statement of the rule would be preserved *because* it is
duplicated. Deleting it leaves the rule where it is enforced, and the schema was never the party to fix here.

**L4 (2026-11-12) — the stored-rows proof already existed, and it is the right kind.** Cited instead of written:
`TaxRateSaleAcceptanceTest.java:277-285` states a 16% rate through the tax-rate API, posts a sale through
`POST /api/sales-orders`, and then reads the committed line row back with `itemRepository.findById`, asserting
`getTaxAmount()` is `32.0000` — the trigger's derivation, not a number the test recomputed. A second case at the
document level does the same through `jdbcTemplate` (`SalesOrderIntegrationTest.java:3666`). Re-stating the formula
in Java to "prove" the schema would have re-created exactly the third expression this unit removes.

**L5 (2026-11-12) — the two sentences, and the four assertions that had to move with them.** Both refusals now read
`(ADR-0002 decision 6)`, with the code, the document's date and the zero-by-definition codes untouched and both
still answering 400 (`SalesOrderService.java:1069-1078`, `PurchaseOrderService.java:1222-1229`). The pin is in the
two service tests, which gained `hasMessageContaining("ADR-0002 decision 6")` **and**
`hasMessageNotContaining("D-a")` — the second half is what makes a future copy fail rather than pass, which is the
check S7 asks for. Two integration assertions that quoted the old sentence were updated rather than left to fail:
`SalesOrderIntegrationTest.java:3730` and `TaxRateSaleAcceptanceTest.java:214`. Observed **RED** first: with the two
new checks added and the messages still old, `SalesOrderServiceTest` and `PurchaseOrderServiceTest` reported
`193 tests completed, 2 failed`, both `AssertionFailedError` on "not to contain: D-a", and the exact two cases were
the refusal ones. The `(D-a)` mentions that belong to `odd/tasks/product-tax-code.md` — `SalesOrderService.java:1027`
and the zero-rate display names — are a different decision with a different record and were left alone.

**L6 (2026-11-12) — what this does not clean up, and why it must not be cleaned up in place.**
`V94__purchase_order_details_tax.sql:114-116` still says *"That is what D4 means by reusing `lineTaxAmount` rather
than re-expressing it"*, and the method it names no longer exists. Editing that file is the wrong repair: Flyway
checksums the whole migration including comments, so a comment fix would fail `validate` on every database that
already applied `V94` and would need a `repair` against the schema history — a real operational cost paid for prose.
What the sentence claims was already untrue when it was written (`L13` of `purchase-order-line-tax.md` corrected the
`S6` that said the same thing), and it is now simply unanswered rather than false: this record is where the reader
of that line lands. It is recorded here instead of being silently left as a dangling name.

**L7 (2026-11-12) — the gates, run by the parent on the real tree.** Full `./gradlew test` and `spotlessCheck` on
`life-control-api`, plus the shell suites that read this repository's own rules. The numbers are in the pull
request; what matters here is that they were run on the worktree that is being proposed and not on a scratch copy.
