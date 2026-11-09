# ODD feature: product-tax-code

**Repository**: LifeControl — `life-control-api` (migrations, `product`, `tax`, `salesorder`), `life-control-app-angular` (product form), `odd/tasks/`
**Branch**: `feat/sales-line-tax-rate` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-sales-line-tax-rate`
**Status**: implemented — the fiscal code lives on the product, the platform catalog has the read `V82` said it
lacked, and a sales line copies the resolved rate with its code at write time, so `tax_amount`, the line total
and the order's `tax_total` stop being structurally-present-but-always-zero. Two things stay owed and are named
rather than implied: the catalog's administration screen (codes are still edited by migration, like `currency`)
and a per-line override, which this unit deliberately does not add — a line states the code its product
declares.
**Created**: 2026-11-08
**Risk**: items 1, 3 and 6 — item 1 because `tax_amount` moves stored money on financial rows and a wrong
resolution is not detectable after the fact; item 3 because `ProductRequest`/`ProductResponse` gain a field
under an Angular product form that constructs them positionally, and a sale can now be refused where it was
not; item 6 because until T4 lands, `tax_amount` is a column whose only writer is a trigger fed by a rate
nobody sets.

## Specs

S1. `docs/adr/0002-money-first-class.md`, decision 6, **verbatim**: «**Tax is a rate snapshot, not a
    calculation in the UI.** The rate is resolved server-side from the latest `tax_rate` row with
    `effective_from ≤ the document's date` for the company and tax code, and is copied onto the line at write
    time, so a rate change never rewrites history.»

S2. The maintainer's decision of 2026-11-08, **verbatim**: «El código vive en el catálogo del artículo». The
    code is a column on the product, not on the line's request and not a company default, so a client cannot
    state one and the server never guesses one.

S3. The same ADR's `## Schema` block, **verbatim**, is the shape the line already carries:
    `ADD COLUMN tax_code VARCHAR(20) NOT NULL DEFAULT 'NA'`, `ADD COLUMN tax_rate NUMERIC(9,6) NOT NULL
    DEFAULT 0`, `ADD COLUMN tax_amount NUMERIC(19,4) NOT NULL DEFAULT 0`,
    `ADD CONSTRAINT ck_item_tax CHECK (tax_amount = round((subtotal - discount_applied) * tax_rate, 4))`,
    `ADD CONSTRAINT ck_item_total CHECK (final_price = subtotal - discount_applied + tax_amount)`.

S4. `V82__tax_rate.sql:22` and `:26-29`, **verbatim for the shape**: `tax_code (code VARCHAR(20) PRIMARY KEY,
    label VARCHAR(120) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT true)`, seeded with exactly four codes
    (`NA`, `IVA`, `TASA_0`, `EXENTO`), and deliberately not armed because it is a platform catalog like
    `currency`. The file's own words for what is missing are «the read that nobody has yet».

S5. `V85__sales_order_items_line_total.sql:69-71`, **verbatim**: same-event triggers fire in name order, so
    `trg_sales_order_items_final_price` sorts before `trg_sales_order_items_subtotal` and its function
    recomputes `list_price * quantity` instead of reading `NEW.subtotal`. A third trigger for `tax_amount`
    would sort **after** `final_price` and be read stale, so the derivation of `tax_amount` and `final_price`
    is one function, and the trigger listens to `tax_rate` rather than to `tax_amount`.

S6. Decision **D-a** (2026-11-08, taken here because decision 6 does not answer it): a code is either
    **zero by definition** or **needs a rate**. `NA`, `TASA_0` and `EXENTO` are the first kind — D-d's own
    reason for the catalog existing is that CFDI separates Tasa 0% from Exento and both are `rate = 0` — so
    they resolve to `0` without a `tax_rate` row. Every other code resolves through `TaxRateService` and a
    document refuses the line with **400** naming the code and the order date when no enabled rate applies.
    Reason: `tax_rate` is empty in every environment today, and `NA` is the column's default, so "no rate
    means refuse" applied uniformly would refuse every sale in the tree; and "no rate means zero" applied
    uniformly is the silent zero the ADR exists to forbid.

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | `products.tax_code` (FK to the catalog, `NOT NULL DEFAULT 'NA'`), mapped in `Product`, `ProductRequest`, `ProductResponse` and `ProductService`, with the schema suite and the positional-constructor tests it moves | S2, S4 | worker | `e251d323` |
| T2 | `GET /api/tax-codes`: `TaxCode` entity, repository, service, cache region, tenant-agnostic path entry, **the gateway route**, controller test and integration test | S4 | worker | `6f1831e3` |
| T3 | The derivation: one function sets `tax_amount` and `final_price`, the trigger listens to `tax_rate`, with the migration suite that pins `ck_item_tax` refusing a hand-written amount | S3, S5 | worker | T3 |
| T4 | The copy: every site that **creates** a line row resolves `(company, product's code, order_date)` and writes `tax_code` + `tax_rate`; D-a's refusal; integration test on a real `POST /api/sales-orders` | S1, S3, S6 | worker | `da055432` |
| T5 | Angular: the catalog service, the select in `products-form`, models and spec | S2, S4 | worker | `aca0739b` |
| T6 | An unknown or retired code from a client is refused by the service (404 / 400 naming the code), not by the FK as a 500 — the `resolveCurrency` shape | S2 | worker | `e9fe0bd5` |
| T8 | `V91` seeds the `TAX` activity process: T2's controller derives it from its package and `ActivityLogService` **discards** entries for an unseeded process with only a WARN. Caught by `ActivityProcessSeedTests` in the full suite, not by the focused ones | S4 | inline | T8 |
| T7 | A line **moved to another variant** re-resolves its code and rate: it is no longer the article whose tax was copied, so the "an edit never re-resolves" rule does not reach it. Found by T4's worker as an unsettled gap, not as a bug report, and fixed with the rule stated where the other one is | S1, S2 | inline | T7 |

Allowed edit surfaces are disjoint by wave: T1+T2 in parallel, then T3+T5, then T4+T6, then T7 after T4's own
review of its rule found the exception.

Slice boundaries, because each is over the ~400-line review budget on its own and none of them should
become one PR: `e251d323` is the schema-plus-mapping slice, `6f1831e3` the catalog-read slice, and T3/T4/T5/T6
each get their own commit. A chained-PR delivery can cut at any of those boundaries without a rewrite.

## Log

**L1** — the request that produced this unit, verbatim:

> ok con que seguimos?

followed by the choice «T5: copiar la tasa a la línea de venta» and then, when asked where a line's tax code
comes from, «El código vive en el catálogo del artículo» (S2).

**L2** — 2026-11-08 — the map that sized it, measured and not assumed. The tax package is **complete and
unwired**: `TaxRateService.resolve` and `lineTaxAmount` exist with no production caller, and
`sales_order_items.tax_code/tax_rate/tax_amount` are `insertable=false, updatable=false`, so they keep the
`V81` defaults `'NA' / 0 / 0` and `ck_item_tax` holds trivially. `recomputeBreakdown` already sums
`tax_amount` into `tax_total` and `ck_order_total` already includes it, so the arithmetic above the line needs
no change: what is missing is where the code comes from, which is S2's decision, and the copy.

**L3** — 2026-11-08 — `products` has `sat_code` and **no** `tax_code`, and `Product`, `ProductRequest`,
`ProductResponse`, `Product.builder()`, `ProductService`'s two mappings and the Angular wire body each list
fields explicitly, so T1 is a change in seven places and not a column. `tax_code` has no entity, repository,
service, endpoint, cache region or tenant-agnostic path entry — T2 builds the reader from the
`CurrencyController`/`CurrencyService` precedent, which is the platform-catalog shape the ADR already chose
for `currency`.

**L4** — 2026-11-08 — **the gateway route is part of T2, not a follow-up.** T2's worker delivered a green
controller at `/api/tax-codes` and correctly reported that `api-gateway/.../Routes.java:56` lists every
proxied `/api/**` prefix by hand, so the endpoint is a **404 in a deployed stack** while answering in every
backend test — the backend suites call controllers directly and never cross the gateway. That is the exact
defect `odd/tasks/gateway-route-coverage.md` and `GatewayRouteCoverageTests` exist for, and the guard is
source-based, so T2's new controller turned it **red in a module its surfaces excluded**. The parent added the
route beside `/api/currencies/**`; a task whose deliverable is an endpoint is not done while the guard that
proves the endpoint is reachable is failing. Recorded as the reason T2's surface list was wrong, not as the
worker's error: the spec's list was.

**L5** — 2026-11-08 — the second surface mistake of the same kind, recorded because it is the recurring one.
T5's authorized surfaces said `life-control-app-angular/src/app/features/**`; the app keeps them at
`src/features/**`, and `src/app/` holds only the config and HTTP plumbing. The worker **stopped and asked**
instead of writing outside the literal authorization, which is the behaviour the contract wants and the reason
no half-typed code landed anywhere. The parent corrected the block and relaunched; the derived path list the
worker produced matched the code the spec had already named, so nothing about the work changed — only who was
allowed to touch it. Lesson for the next unit: surfaces come from a `ls` of the tree, never from memory of a
path another agent abbreviated.

**L6** — 2026-11-08 — T5's gates, and the caveat that survives them. The Angular worker ran `ng test`,
`ng lint` and prettier against a **byte-identical mirror of `src/` plus the anchor's installed
`node_modules`** (`/tmp/ang-gate-HobuV8`), because a linked worktree does not inherit the anchor's
`node_modules` and this one had none: 33 files, 715 tests passed, up from 697 with none lost. That is evidence
about the source, not about this checkout, and the record says so instead of calling it "gates green". The
same gap showed up one layer lower: `.githooks/pre-commit` runs `lint-staged`, which spawns eslint and
prettier from `node_modules`, so the first commit attempt died with `ENOENT` and **reverted the commit
itself** — the hook refused to bless code it could not check, which is the behaviour it exists for. Resolved
by running `npm ci` in the worktree, which is what makes the T5 evidence in-place; the lesson for a fresh
worktree is that `npm ci` comes before touching Angular, or the pre-commit hook cannot run by design.
In-place re-run after `npm ci`: `ng test` reports **33 files / 715 tests passed** and `npm run lint` "All files pass linting" — the mirror and this checkout agree, so T5's evidence is no longer second-hand.

**L7** — 2026-11-08 — T4's RED is the finding this unit exists for, and it was not a refusal.
Before the copy, an `IVA` product's line stored `taxCode "NA"` and `taxRate 0.0`, `TASA_0`/`EXENTO` never
travelled at all, an `IVA` line with no configured rate was **accepted with 201**, and an edit restated
`0.0` where 16% belonged. Nothing failed; the sale was simply untaxed. That is the silent zero
decision 6 names, measured instead of argued. GREEN: `salesorder` 245 → 254. T6's RED was the mirror image
on the product side: the unknown-code case threw nothing and reached `save` — the foreign key was the only
thing in the way — and three cases failed on `UnnecessaryStubbingException`, which is itself the proof the
catalogue was never consulted. GREEN: `product` 538 → 542.

**L8** — 2026-11-08 — T7, from a gap T4's worker reported rather than hid. Both update paths reassign
`productVariantId` on an existing row, so under "an edit never re-resolves" a line moved to another variant
kept the previous article's code and rate: the sale would then be taxed with a code its product does not
declare. The rule is narrowed instead of dropped — a **different article is a different fact, not a rate
change** — and both branches now resolve when the variant moves, with the exception stated where the rule
is. Pinned by a case whose new answer is 8%: the stored snapshot says 16%, so that number is only reachable
if the resolution actually ran. It also needed two setters on the entity, which existed only as builder
methods: the compile error is what proved the mutation had no path at all.

**L9** — 2026-11-08 — named, not invented, and left as they are:
- the default `NA` is **not** catalogue-resolved, so if `NA` were ever retired a defaulting product would
  still store it. Only a stated code is refused;
- D-a's zero-by-definition list lives in `SalesOrderService`, where the line's rule is, and not beside the
  catalog. If a fifth zero-by-definition code ever appears, the constant is the place that has to say so;
- the create path now answers **404** for an unreadable variant instead of letting the foreign key produce a
  500, because the tax read needs the variant's product. Same exception and status the other two line-write
  sites already gave; benign, but it is a change nobody asked for and a reviewer should see it named.

**L10** — 2026-11-08 — the full suite found a real defect of this branch, and it is worth writing down
because the focused runs could not have: `TaxCodeController` lives in `com.lifecontrol.api.tax.controller`, the
audit aspect derives the process name from that package segment (`TAX`), `activity_processes` had no such row,
and `ActivityLogService.save` **discards** the entry with only a WARN — a discarded entry is indistinguishable
from one that was never produced. Two assertions in `ActivityLogIntegrationTest#ActivityProcessSeedTests` went
red, and both are one cause: the discovery assertion, and the count assertion behind it. `salesorder` and
`product` were green the whole time because neither package loads the audit guard. Fixed by `V91`, which is
`V78`'s file one catalog over, with the reason stated in its header. T8 in the table, and the lesson is not
"run more suites by hand": it is that a new controller is a new audit process, and the guard that knows it is
the one that scans packages.

**L11** — 2026-11-08 — the gates that actually decide this unit, with the numbers and not the adjective.
`life-control-api`, full suite, **alone** (a concurrent Angular run had already produced a worthless
measurement — see L12): **1090 suites, 4650 tests, 0 failures**, 10m42s, with `V89`, `V90` and `V91` applied by
every integration container's boot. Gateway: `GatewayRouteCoverageTests` green in its own build, so
`/api/tax-codes` is reachable and not only answerable. `odd-status-headers.test.sh`: 78 passed, 0 failed over
149 records, which is the check this file's header has to survive.

**L12** — 2026-11-08 — the Angular half, and the two measurements it took to be honest about it. First run,
**in parallel with the API suite**: 26 files and 117 tests red, all under `src/features/purchases/**`, which
this branch does not touch — a worthless measurement, mine to own, because those specs assert loading
skeletons and debounce windows and the machine was running 4650 backend tests at the same time. Second run,
alone: 198 files passed, one failed —
`scheduling-activity-availability`'s "block the add at the server cap of 50" — in a package this branch does
not touch either (`git diff --name-only origin/main..HEAD | grep schedul` is empty). That spec passes in
isolation (28/28), so it is order-dependent instability in the suite and not a defect of this change. Third
run, alone: **199 files, 4155 tests, 0 failures**, 1m52s — the same suite that took 25 minutes under load.
Both numbers belong in the record: the gate is green, and the flake is named rather than buried.
