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
| [0002](0002-money-first-class.md) | Money is a first-class value: currency, breakdown, payment rows, tax. | proposed | 0001, 0003, 0005 | — |
| [0003](0003-document-identity-per-tenant.md) | Document numbers come from a per-tenant sequence, not from `max + 1` over a global `UNIQUE`. | proposed | — | 0002 |
| [0004](0004-typed-state-machine.md) | Document state is a typed machine with a history, not a string in a lookup table. | proposed | 0003, 0005 | — |
| [0005](0005-tenant-isolation-rls.md) | Tenant isolation is enforced by Row-Level Security, not by 150 call-site checks. | accepted | — | 0001, 0002, 0003, 0004 |
| [0006](0006-catalog-identity.md) | Catalog identity: `variant_mode` on the product, a required measure unit, a barcode per mode, and `variant_listing` split from stock. | proposed | 0005 | 0001 |

Read the dependency column literally: an ADR whose dependency is not accepted cannot be implemented,
and an ADR that blocks another one changes that other ADR's schema.

## Adoption order

The order is not the ADR number; it is what minimizes rework. Each row is one slice or a small chain,
never a big-bang rewrite.

**Accepted as written by the maintainer on 2026-10-29**, in the session that delivered this set. It is
therefore the agreed plan for what gets implemented first, not a suggestion: a slice that proposes a
different order has to say why, because the reason the order exists is that doing it in the other
direction writes the same work twice. Accepting the order accepts no ADR: the status column above is the
only authority on which ones have been accepted, and each ADR still needs its own decision. ADR-0005 was
accepted on 2026-10-29; the other five are still `proposed`.

1. **ADR-0005** (tenant isolation) first, because it touches every table and every policy, and doing
   it after the new tables exist means writing the policies twice.
2. **ADR-0006** (catalog) second, because it defines the identity every other table references —
   `variant` is the key of the stock ledger.
3. **ADR-0001** (inventory ledger) third, the largest gain: it retires the three-representations
   problem and brings costing with it.
4. **ADR-0003** (document identity) fourth: small, independent, and a prerequisite for payments that
   must be numbered per tenant.
5. **ADR-0002** (money) fifth: it depends on a sale that books stock in the same transaction, so it
   reads better once 0001 is in place.
6. **ADR-0004** (typed states) sixth: it is orthogonal and can be done at any point, but it is the
   cheapest place to end, because it touches every document and benefits from a settled shape.

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
