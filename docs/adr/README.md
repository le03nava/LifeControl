# Architecture Decision Records

This directory records the decisions that shape the product, inventory and commerce core of
LifeControl. Each ADR is one decision, with the evidence that forced it, the target shape, and the
migration from the tree as it is today. An ADR is not a specification: it states what must be true and
why, and leaves the slice-by-slice work to `odd/tasks/`.

## Why these exist

The repository is pre-production, which is the only moment a model change is cheap. The tree already
uses `TRUNCATE` as a design tool — `V14__product_variant_definition.sql` emptied the whole operational
chain, `V40__product_catalog_company_contract.sql` emptied the catalog — and that is a one-time
opportunity. These ADRs close the model now, so the same work does not have to happen against a live
database.

They were written from a single architecture review, recorded in
`odd/tasks/architecture-redesign-adrs.md` together with the request that authorized it and the answers
that settled the open questions.

## Status vocabulary

| Status | Meaning |
| --- | --- |
| `proposed` | Written, evidenced, not yet accepted. Nothing implements it. |
| `accepted` | The maintainer accepted it. Slices may cite it as authority. |
| `rejected` | Considered and refused. The reasoning stays so it is not re-opened from memory. |
| `superseded by ADR-nnnn` | Replaced. The file stays; a superseding ADR names it. |

`accepted` is the maintainer's decision and nobody else's. An ADR written by an agent starts at
`proposed`, and a session that finds one at `proposed` may cite it as a proposal, never as authority.

## The set

| ADR | Decision | Status | Depends on | Blocks |
| --- | --- | --- | --- | --- |
| [0001](0001-inventory-ledger-single-source.md) | Inventory is a single append-only ledger; balances are disposable projections. Costing and valuation are part of it. | proposed | 0005, 0006 | 0002 |
| [0002](0002-money-first-class.md) | Money is a first-class value: currency, breakdown, payment rows, tax. | accepted | 0001, 0003, 0004, 0005, 0006 | — |
| [0003](0003-document-identity-per-tenant.md) | Document numbers come from a per-tenant sequence, not from `max + 1` over a global `UNIQUE`. | proposed | 0005 | 0002 |
| [0004](0004-typed-state-machine.md) | Document state is a typed machine with a history, not a string in a lookup table. | proposed | 0003, 0005 | 0002 |
| [0005](0005-tenant-isolation-rls.md) | Tenant isolation is enforced by Row-Level Security, not by 150 call-site checks. | accepted | — | 0001, 0002, 0003, 0004 |
| [0006](0006-catalog-identity.md) | Catalog identity: `variant_mode` on the product, a required measure unit, a barcode per mode, and `variant_listing` split from stock. | proposed | 0005 | 0001 |

Read the dependency column literally: an ADR whose dependency is not accepted cannot be implemented,
and an ADR that blocks another one changes that other ADR's schema. The `Blocks` column is the same edge
read backwards: ADR-0004's row names 0002 because ADR-0002's `Depends on` line has named 0004 since
2026-10-30, and a graph that states one direction only is how the order below came to implement money
before the states it consumes.

## Adoption order

The order is not the ADR number; it is what minimizes rework. Each row is one slice or a small chain,
never a big-bang rewrite.

**Accepted as written by the maintainer on 2026-10-29**, in the session that delivered this set. It is
therefore the agreed plan for what gets implemented first, not a suggestion: a slice that proposes a
different order has to say why, because the reason the order exists is that doing it in the other
direction writes the same work twice. Accepting the order accepts no ADR: the status column above is the
only authority on which ones have been accepted, and each ADR still needs its own decision. ADR-0005 was
accepted on 2026-10-29 and ADR-0002 on 2026-10-30; the other four are still `proposed`.

1. **ADR-0005** (tenant isolation) first, because it touches every table and every policy, and doing
   it after the new tables exist means writing the policies twice.
2. **ADR-0006** (catalog) second, because it defines the identity every other table references —
   `variant` is the key of the stock ledger.
3. **ADR-0001** (inventory ledger) third, the largest gain: it retires the three-representations
   problem and brings costing with it.
4. **ADR-0003** (document identity) fourth: small, independent, and a prerequisite for payments that
   must be numbered per tenant.
5. **ADR-0004** (typed states) fifth: it is an **input** of the money decision, not a consumer of it.
   ADR-0002 validates its state guard (decision 7), stands an append-only row in for a void payment
   where 0004 has the transition history (decision 5) and reads its `is_cash` flag (M4), so writing
   the charge before the machine means writing `chargeSalesOrder` twice — the second time under a
   different state mechanism, and with the window open precisely while the payment code is least
   proven.
6. **ADR-0002** (money) sixth, and last: it consumes the ledger (0001), the per-tenant number (0003)
   and the guard (0004), which is why it is the one ADR that cannot be started early without paying
   for the same code twice. It reads better once 0001 is in place, as the original order said; what
   changed is that it also has to read after 0004.

**Revised on 2026-11-01 by the maintainer:** steps 5 and 6 swapped, because the accepted dependency
edge 0002 → 0004 (added 2026-10-30) contradicted the sequence this list carried — the document said
money depends on states while the plan implemented money first. The evidence and the two rejected
shapes are recorded as **D-e** in `odd/tasks/money-first-class.md`; splitting the charge rewrite so the
payments could land against today's status flip was refused there, because the properties that make
taking money safe (no `Completed` order uncovered by payments, change only on cash, no double charge on
a retry) are exactly the guard being moved. This revision changes no ADR's status and no ADR's text:
the edge was already written into ADR-0002's `Depends on` line, which is a fact; only the order was left.

## Writing a new one

1. Copy the header block of the most recent ADR and use the next free number.
2. State the decision in one sentence at the top, then the evidence that forced it — `path:line`, a
   measured count, a migration that exists because the problem happened. Evidence, not adjectives.
3. Write the target shape as DDL, invariants or both. An invariant nobody can test is a wish.
4. Write the migration from the tree as it is, including what gets discarded and why that is
   authorized.
5. Write the rejected alternatives with the reason each was refused. This is the section that stops
   the same debate from happening twice.
6. Start at `proposed`. Do not accept your own ADR.
