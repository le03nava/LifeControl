# Settle whether a listing freezes `variant_mode` (issue #778)

**Status**: implemented — decision 1 of ADR-0006 now states that stock movements are the only freeze condition, and the `V76` suite pins that a product whose variants hold only a listing is still reclassifiable. Nothing is deferred.

## Specs

The requirement is issue #778, whose acceptance criteria are quoted verbatim. The maintainer chose the issue's
option 1 ("not a freeze condition"), so the criteria that apply are the ones written for that branch.

- **S1**
  > `docs/adr/0006-catalog-identity.md` resolves decision 1 against step 6 in one sentence, in the section a
  > reader of the guard reaches first.

- **S2**
  > If the guard does not widen: the ADR sentence and `V76`'s header comment agree, and a test pins the
  > current behaviour — a product with only a listing is still reclassifiable — so the gap is a decision
  > instead of an accident.

- **S3**
  > No case regresses: the `BEFORE UPDATE OF variant_mode` early return (a full-row update that does not
  > change the mode is accepted) keeps its pin, and a writer scoped to another company still changes 0 rows
  > because the policy, not the trigger, is what it meets.

- **S4**
  > The migration suite that covers `V76` is extended, not duplicated.

Non-goals, from the issue: `variant_listing.enabled` as a condition (that is #777); backfilling or re-interpreting existing rows; the ledger's own booking rules.

## Tasks

| ID | Specs | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S2 | Decision 1 of the ADR gains the reconciliation; step 6 gains the pointer to it | `docs(adr): stock movements are the only variant_mode freeze condition` |
| T2 | S2, S3, S4 | One case added to `ProductVariantModeImmutabilityMigrationIntegrationTest`, plus its helper | same commit |

## Log

- **L1** — maintainer's request, verbatim: "ok con que issue continuamos?" and then, choosing from the three open issues, "#778 — segunda condición de freeze (recomendado)".
- **L2** — the decision itself, taken by the maintainer: **the listing is not a freeze condition**. The guard does not widen; no migration is added.
- **L3** — the reason the decision went that way, recorded because it is the argument the ADR now has to survive: `variant_listing` is **mutable** (an upsert, and a row that can be deleted) while `stock_movement` is **append-only** behind a trigger that refuses `UPDATE` and `DELETE`. Freezing on a reversible fact would make the guard stricter than the fact it protects. And because `variant_listing.enabled` has no writer yet (#777) and the API has no operation that removes a listing, widening the guard today would freeze a product with no way to unfreeze it.
- **L4** — **`V76` is not edited, on purpose.** Its header already carries the answer (`V76:26-29`, "WHY `variant_listing` IS NOT A FREEZE CONDITION (#774)"), so S2's "the ADR sentence and `V76`'s header comment agree" is satisfied by the ADR catching up to a header that was already right. Editing a shipped migration's comments changes its Flyway checksum and would fail validation on every database that already ran it — the one thing a documentation fix must not do.
- **L5** — the case is added to `ProductVariantModeImmutabilityMigrationIntegrationTest` rather than a new class (S4). It contrasts with the existing refusal cases by construction: same fixture, one `variant_listing` row and no `stock_movement`, and the reclassification is accepted.
