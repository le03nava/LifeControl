# ODD feature: cfdi-fiscal-document

**Repository**: LifeControl — `life-control-api` (migrations, a new `fiscal` module, the `tax` and `customer` models), `life-control-app-angular` (the fiscal profile screens), `odd/tasks/`
**Branch (planned)**: `feat/cfdi-fiscal-document`, cut from `origin/main` after `docs/adr-fiscal-and-accounting` merges
**Status**: **deferred** — the unit implements `docs/adr/0007-fiscal-document-is-separate.md`, accepted 2026-11-12, in nine tasks and four slices. **A1 delivers T1 and T3**: the issuer's regime and place of issue (`V102`), and the tax vocabulary's SAT coordinates plus the product's fiscal key (`V103`, `V104`). **T2 waits** for `odd/tasks/customer-tenant-isolation.md`, because `customers` has no `company_id` and no policy, and the most sensitive personal data in this tree does not land behind a query with no tenant predicate (`L6`, `L7`). A2, A3 and A4 are unwritten; A3 is the only slice that needs a stamping provider, which is why it comes last.
**Created**: 2026-11-12 · **Risk**: items 1, 3 and 5 — item 1 because a stamped document is irreversible: the UUID and the sellos cannot be recomputed, so a wrong frozen snapshot is a wrong fiscal document forever; item 3 because `CustomerResponse`, `CompanyResponse` and the customer form change shape under an Angular app that already calls them; item 5 because A3 introduces an external dependency with credentials, a sandbox and a partial-failure state into a path that today has no network call at all.

## Objective

Give the sale a fiscal document of its own — CFDI 4.0, stamped by a provider behind a port — with the issuer's and receiver's fiscal identities as first-class profiles, the authority's keys mapped once onto the tax vocabulary that already exists, and every fact the document states frozen on it at stamp time.

## Problem — measured in the tree, not quoted from the ADR

| Fact | Evidence |
| --- | --- |
| No fiscal document exists anywhere | Zero matches for timbre, sello, certificado, régimen, uso CFDI, forma or método de pago, in migrations, Java and the Angular app |
| The money an invoice states already exists | The document carries its four parts and its currency under a constraint; each line carries a frozen `tax_code`, `tax_rate` and a derived `tax_amount` |
| The issuer is half fiscal | `companies` has `rfc`, `razon_social` and an address, and nothing that says its regime; `tipo_persona_id` is a bare integer with no catalog, no foreign key and no reader |
| The receiver is less | `customers.rfc` is nullable, unvalidated, and is the only fiscal column that exists |
| Three of the authority's keys are already columns | The measure unit's key is required and unique, the category's is constrained to eight digits, and the product's is twenty characters of free text with no check and no catalog |
| The concept's key is never copied to the line | The line snapshots the product's name and the variant's name, and not the fiscal key — the omission that makes a reprint read today's catalog against last year's sale |
| One tax per line, and no factor | `tax_code`, `tax_rate` and `tax_amount` are scalars; `EXENTO` and `TASA_0` differ only by a string in a code |
| The tree can only express a sale paid when it happens | The charge refuses unless the payments cover the total exactly, and a zero total is refused |

## Specs

**S1** — The issuer states its fiscal regime and its place of issue: `companies` gains a regime with a closed vocabulary and a postal code, both required to issue and neither required to sell.

**S2** — The receiver carries a fiscal profile, not a name and an identifier: legal name, regime, fiscal address with a postal code, and a default use — and `rfc`, when present, is validated for shape. The profile is required **at issue**, not at the till: a cash sale needs none and an invoice cannot exist without one.

**S3** — The tax vocabulary gains the authority's two coordinates as mapped columns on `tax_code` — `sat_impuesto` and `sat_tipo_factor` — rather than a parallel catalog. With them, a zero rate, an exempt item and a not-applicable one stop being the same row distinguished by a string, which is the first time this tree can tell `EXENTO` from `TASA_0` in a document.

**S4** — The product's fiscal key is constrained, not free text: a format check with the shape the category's key already has, in the same slice that starts reading it. The concept is the one element a stamp cannot omit, and it is the weakest fiscal column in the tree.

**S5** — The document is a row set of its own — `fiscal_document`, `fiscal_document_line`, `fiscal_document_artifact` — referencing the sale by `(source_type, source_id)`, never a column on the order. Every tenant table carries `company_id` and is armed; the two artefact tables are append-only on the pair `V83` established.

**S6** — At most one live stamped document per sale: a partial unique index over `(company_id, source_type, source_id)` where the status is the stamped one. A cancelled document and its replacement are two rows, and the pair is legal.

**S7** — A stamped document's facts cannot be updated or deleted, and its UUID is unique and non-null on a stamped row. Cancellation is a new row naming the motive and, for a substitution, the folio that replaces it.

**S8** — The invoice's line amounts equal the sale line's within one minor unit, checked by a constraint trigger **at stamp time**. It is the only place two documents are compared, and it is what keeps a deliberate duplication honest.

**S9** — Every fact the document states is frozen on it: key of product, key of unit, description, quantity, unit value, discount, tax object, base, tax, factor and rate — plus the receiver's identity as it was. Nothing is read from the live catalogs when a document is re-issued, reprinted or cancelled.

**S10** — The artefact returns the bytes that were stamped: a reprint reads the stored XML and never regenerates it, because a regenerated document cannot be proven to be the original.

**S11** — A stamp attempt is idempotent: the same attempt never stamps twice, and a failure leaves no stamped row.

**S12** — The document declares its type, its place of issue and its export status as columns with a closed vocabulary, because two of the three are required on every document by the schema's fourth revision. Catalog **values** are read from the version in force at stamp time and are never pinned in code or in the ADR.

**S13** — Only a sale paid when it happens can be invoiced. The payment form and the payment method are conditional in the authority's schema and the deferred method is what forces a placeholder plus a payment complement, so the deferred method is refused as a validation — named, not half-modelled.

**S14** — The fiscal document never creates revenue. ADR-0008 posts from the sale, so a stamp in a later month, a cancellation, or a sale never invoiced all leave the books describing what happened commercially.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S1 | The issuer's regime and postal code, with their closed vocabularies | — | inline | `feat(fiscal)` |
| T2 | S2 | The receiver's fiscal profile, its validation, and the refusal when it is incomplete at issue | T1 | worker | `feat(fiscal)` |
| T3 | S3, S4 | The two mapped columns on `tax_code` and the format check on the product's key | — | worker | `feat(fiscal)` |
| T4 | S5, S6, S7 | The three tables, the policies, the append-only pair, the partial unique index and the state machine | T1, T3 | worker | `feat(fiscal)` |
| T5 | S8, S9 | The frozen snapshot and the trigger that compares it with the sale at stamp time | T4 | worker | `feat(fiscal)` |
| T6 | S10, S11 | The stamping port, the XML builder without a network, and the idempotent attempt | T5 | worker | `feat(fiscal)` |
| T7 | S10, S11 | The provider adapter behind the port, with its credentials and its partial-failure state | T6 | worker | `feat(fiscal)` |
| T8 | S7, S12 | Cancellation, substitution, and the artefact read path that reprints | T7 | worker | `feat(fiscal)` |
| T9 | S13, S14 | The PUE-only validation, and the test that pins the books against a stamp in another month | T8 | inline | `feat(fiscal)` |

**Total task count: 9.**

## Slices

Four chains. A1 and A2 have no external dependency and can ship alone; A3 is the only one that cannot be verified without a provider.

| Slice | Tasks | What it makes true | Ships alone? |
| --- | --- | --- | --- |
| **A1** Fiscal profiles | T1, T2, T3 | S1–S4. A sale still works exactly as today; issuing becomes expressible | Yes |
| **A2** The document | T4, T5 | S5–S9. A document can be built and refused, and nothing is stamped | Yes |
| **A3** Stamping | T6, T7 | S10, S11, S12 | No — needs a provider |
| **A4** Lifecycle | T8, T9 | S7, S13, S14 — cancellation, substitution, reprint, and the seam with the books | No |

## Log

**L1 (2026-11-12) — the request that produced this unit, in the operator's words.** «puedes analizar el modulo de ventas y ver que le faltaria para generar una factura electronica, y en temas de contabilidad que falta crear o mejorar, simplificar». The "simplificar" is load-bearing here too: the document receives a frozen snapshot instead of reading catalogs at print time, and the tax vocabulary receives two mapped columns instead of a second catalog beside it.

**L2 (2026-11-12) — the decision, and where it lives.** `docs/adr/0007-fiscal-document-is-separate.md`, accepted by the maintainer that day, together with `docs/adr/0008-accounting-is-a-projection.md`. Both shipped in the pull request that carries this record.

**L3 (2026-11-12) — the three corrections the authority's own schema forced before acceptance, recorded because they are the argument for S12.** Series and folio were demoted from identity to an optional local convention, because the schema makes both optional; the export declaration was added as a required column, because the fourth revision requires it on every document; and the product's key was scheduled for a format check, because it is free text while its category counterpart is not. The shape of a fiscal document is a fact to look up, not one to recall.

**L4 (2026-11-12) — the deliberate separation from the books.** Revenue accrues at the sale, not at the stamp, which is why this unit can ship before, after or entirely without `derived-accounting`. The seam is stated in both records and in both ADRs; a future reader should find it in three places rather than infer it from a date.

**L5 (2026-11-12) — why this record opens with `deferred`.** Its work is fully specified and none of it is written, so `implemented` would be false in the one place nobody revisits; and nothing is `blocked`, because A1 and A2 depend on no service and no decision. Delivery evidence belongs in dated rows appended here, one per slice as each lands.

**L6 (2026-11-14) — A1 decided before the code, and the reason T2 waits for a decision.** (a) **The issuer gains two facts, both nullable** — `companies.regimen_fiscal` with a closed vocabulary and `companies.fiscal_zip_code`, a five-digit place of issue — because S1 is explicit that they are "required to issue and neither required to sell". Neither needs a backfill, and therefore neither opens the `NO FORCE` window `V57` needed: a nullable column with a predicate that admits `NULL` can be added to a table the owner cannot read across. (b) **S1 and ADR-0007 disagree about where the postal code lives, and this is the reading kept.** Decision 8 of the ADR puts the postal code of the place of issue on the **document** — frozen at stamp, which `S9` restates; `S1` puts it on `companies`. Both hold, because they are different rows of the same fact: the company declares its place of issue once and the document copies it at stamp time, exactly as a tax rate is frozen on a line. Reusing `companies.zip_code` was refused: it is a twenty-character mailing field whose shape nothing checks, and a fiscal fact that shares a column with a mailing address is one nobody can rely on later. The ADR carries a dated amendment in this slice for the column it omitted. (c) **The closed vocabulary is an enum plus a mirrored `CHECK`** — the `ProductAttributeKind` shape — and not a list in the ADR, per decision 11: the values belong to the authority's catalog, so a reissue lands as a migration and never as a silent acceptance. (d) **`tax_code`'s two coordinates are nullable and unconstrained.** They are the authority's codes, decision 11 refuses to pin them, and a code with no mapping has to be *visible as null* so the stamp refuses it instead of guessing. The four declared codes are mapped in the same migration: `IVA` and `TASA_0` both to `(002, Tasa)` — the rate is what separates them, not the coordinates —, `EXENTO` to `(002, Exento)`, and `NA` to both null, because "not subject to the tax" is the absence of a tax node and not a tax of zero. (e) **The product's fiscal key becomes constrained the way the category's already is**: `^[0-9]{8}$` in a named `CHECK` and a `@Pattern` on the request. Because `products.sat_code` has been free text since `V1`, the migration **refuses loudly** — naming the count and a sample of the offending rows — rather than aborting with a constraint violation nobody can act on; `NOT VALID` was refused because a constraint that only judges new rows is a claim this tree cannot make about its own data. (f) **T2 waits, and the reason is not the profile.** `customers` has no `company_id`, no policy and no entry in `V53`'s armed list: `GET /api/customers` returns **every tenant's** customers to any `lc-sales` caller, with name, email, phone and `rfc`. That is a pre-existing cross-tenant read, and T2 asks to add to that same table the fiscal profile — legal name, fiscal address, postal code. Shipping it unarmed would put the most sensitive personal data this tree holds behind a query with no tenant predicate, so T2 is held until the maintainer chooses between arming `customers` as its own prerequisite unit and recording the hole as a blocking issue. A1 delivers T1 and T3 meanwhile.

**L7 (2026-11-14) — the maintainer armed `customers` first, so `T2` waits on a unit of its own.** Asked to choose between arming the receiver table, shipping the profile on it and filing the hole, or leaving T2 out, the answer was the first: **arm `customers` as its own prerequisite unit** before the fiscal profile lands on it. The reasoning is the one `L6`(f) states — the table has no `company_id`, no policy and no entry in `V53`, and `GET /api/customers` returns every tenant's customers to any `lc-sales` caller — and the consequence is that A1 delivers **T1 and T3 only**: the issuer's regime and place of issue, and the tax vocabulary's SAT coordinates plus the product's key. `S2` is not abandoned, it is sequenced, and its first obligation is the tenant column on the table it describes. The prerequisite is recorded as `odd/tasks/customer-tenant-isolation.md`, and T2 does not start until that lands, because a fiscal profile written into a table no policy reaches would be the most sensitive personal data in this tree behind a query with no tenant predicate.
