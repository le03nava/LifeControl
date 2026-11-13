# configuration-tax-rates

**Status**: implemented — this record is the branch's content: the specs, the task plan and the measurements
behind them. The screen itself (S1–S3) is the work this plan breaks down, and the uniqueness change that would let
a mistyped rate be corrected on its own day (S11) is **deferred** to its own issue, because it changes a fiscal
invariant on a table documents resolve from. Branch `feat/configuration-tax-rates`, base `origin/main` @ `8eb74638`.
**Created**: 2026-11-12 · **Risk**: none for S1–S3 — the API, the schema and every contract are untouched: the
screen composes `GET`/`POST`/`PUT`/`PATCH` that already exist, so item 3 does not apply, and each rule below
carries a test that fails on the previous tree, so item 6 does not either. S11 is deferred **precisely because**
it carries item 3 (a unique invariant others resolve from) and item 1 (a migration over `tax_rate`).

## Specs

Quoted verbatim from the conversation where the operator states the requirement; the rest are decisions, taken
2026-11-12, and each one names the measurement it rests on.

- **S1** — where it lives and how it is reached: «1 vive en un nuevo menu Configuración y que se abra un page como
  /companies para seleccionar menus, sería configuration/ y el menú que aparecería por lo pronto sería el de
  impuestos». So: **one** new top-level menu entry, `Configuration`, opening a landing page shaped like
  `/companies`' dashboard — a grid of cards, one per configuration catalog — whose first card is **Taxes** and
  navigates to `/configuration/taxes`. Adding the next catalog (currencies, which already has the ABM and no
  screen) is a card, not a second entry in `header.ts`.
- **S2** — the server is already complete and this unit adds **no** endpoint, no field and no migration. Measured
  against `TaxRateController`: `GET /api/companies/{companyId}/tax-rates?includeDisabled`, `GET …/{id}`,
  `POST …`, `PUT …/{id}` (supersede), `PATCH …/{id}/enable` (retire/reinstate). `TaxRateResponse` already projects
  `regionId` + `regionName`, so the list needs no second lookup per row.
- **S3** — the read surface: a table of the company's rates carrying **code, scope, rate, `effectiveFrom` and
  state**, ordered by `effectiveFrom` descending **within `taxCode` + scope**, with the row that resolves for
  today marked, and a `includeDisabled` toggle to see retired rows. The scope is an explicit column — "Toda la
  compañía" or the `regionName` — never implied, because it is the half of the key that decides precedence. An
  unmarked list is the failure this row exists to prevent: the operator finds the newest row, "edits" it, and the
  number he watches does not move.
- **S4** — declaring appends: `POST` with `taxCode`, optional `regionId` (absent = company-wide), `rate`,
  `effectiveFrom`, `enabled`. The rate is **entered as a percentage with a fixed `%` suffix and converted to the
  fraction the contract takes** (`16` → `0.16`), because `CHECK (rate >= 0 AND rate <= 1)` makes `0.16` a legal
  **0,16 %**: a bare number field lets an operator declare a rate a hundred times smaller without any error.
  `enabled` is sent explicitly — the server refuses to invent it.
- **S5** — there is no editor, and the UI never pretends otherwise. `PUT …/{id}` **appends a successor**: the
  superseded row is read-only on screen, and the form asks only for the new rate, a date **strictly after** the
  superseded `effectiveFrom`, and `enabled`. The scope and the code are inherited by the server, so the form must
  not offer them. Why this is not "edit": `rate`, `tax_code`, `company_region_id` and `effective_from` are
  `updatable = false` on the entity, and the superseded row is left untouched — it is still the answer for every
  document dated between its `effectiveFrom` and the successor's day.
- **S6** — retiring is `PATCH …/{id}/enable` and is labelled **"Retirar"**, never "Eliminar": the row stays and
  stops being answered by the resolution. Reinstating is the same call in the other direction.
- **S7** — the scope commitment is stated **where the region is chosen**, not in documentation, with this
  sentence: «Declarar una tasa por región la vuelve autoritativa para ese código: desde ese día, un documento de
  esa región con una fecha sin tasa aplicable se rechaza (400) en lugar de usar la de compañía.» The rule it
  states (`TaxRateService.resolve`, step 2) ignores `enabled` and the date on purpose, so the warning cannot be
  softened to "a partir de ahora".
- **S8** — the gate: `lc-tax` **already exists in the realm** (`docker/scripts/keycloak-setup.sh:492`) but is
  **absent from `life-control-app-angular/src/core/security/roles.ts`** (measured: no `lc-tax`, no
  `lc-currency`). The landing and its menu entry are gated on `lc-admin` + `lc-tax`; writes on the same pair;
  reads stay `isAuthenticated()` as the endpoint already is.
- **S9** — the empty state is the explanation of a refusal, not a shrug. A company with no rates gets the text of
  the error the operator actually hit, quoted: «Cannot write the line: tax code IVA has no enabled rate applicable
  on 2026-11-10, the document's date.» plus the action that resolves it (declare the rate its product's code
  needs).
- **S10** — the documentation is corrected **in the slice that makes it false**: `AGENTS.md` states "No hay
  pantalla de tasas (ningún catálogo global de este repo tiene ABM de UI)" in two places, and that stops being
  true the moment S1 lands.
- **S11** — **deferred**: the correction path for a mistyped rate. Measured, on this tree: both partial unique
  indexes (`uq_tax_rate_scope_from`, `uq_tax_rate_region_scope_from`) and `TaxRateService.requireNoRateForTheSameDay`
  (through `existsByCompanyIdAndTaxCode…AndEffectiveFrom`) ignore `enabled`, so a retired row keeps occupying its
  `(code, scope, day)`. Fixing a typo today means superseding it from a later day — leaving the wrong rate
  applying in between — or SQL. The fix is to make uniqueness mean what the resolution already means (only
  `enabled` rows compete), **not** to add a `DELETE`: the line's snapshot is `code + rate` without the region, so
  "has this rate been used" is answerable only heuristically, and deleting on a heuristic is how fiscal history
  gets rewritten unnoticed.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S8, S10 | parent | `feat(config): open a configuration landing and read the company's tax rates` — `LC_TAX` in `roles.ts`, `features/configuration/` landing + `configuration.routes.ts` + `header.ts` entry, `features/tax/data/tax-rate.service.ts` + models, `pages/tax-rate-list` (read-only, S3), `app.routes.ts` wiring, `AGENTS.md` rows |
| T2 | S4, S9 | worker | `feat(config): declare a tax rate from the configuration screen` — the declare form with percent entry, the scope select with the S7 warning, the S9 empty state |
| T3 | S5, S6, S7 | worker | `feat(config): supersede and retire a tax rate without editing history` — the supersede dialog (superseded row read-only, later date enforced) and the retire/reinstate action |
| T4 | S2, S3 | parent | verification: the five endpoints exercised against the running dev stack, and `git diff --stat` proving no backend file and no migration moved |
| T5 | S1–S10 | parent | the full set: `npm run lint`, `npm run test:coverage:check`, and this record's own evidence |
| T6 | S11 | — | **deferred** — its own issue: uniqueness that considers only enabled rows, `V97` + the two repository derivations, high risk, alone in its pull request |

## Log

**L1 (2026-11-12) — the request, verbatim.** «puedes revisar en las ordenes de compra Cannot write the line: tax
code IVA has no enabled rate applicable on 2026-11-10, the document's date. The purchase is refused rather than
taxed at zero (ADR-0002 decision 6): if the line is not taxable its product must declare a code that is zero by
definition (NA, TASA_0, EXENTO), and otherwise the company has to state the rate that applies from 2026-11-10
onward.» The message is the rule working, not a bug: `PurchaseOrderService.lineTaxFor`
(`life-control-api/…/purchaseorder/service/PurchaseOrderService.java:1232-1252`) asks
`TaxRateService.resolve` and turns an empty answer into that 400.

**L2 (2026-11-12) — the measurement that turned it into a product gap.** Read as superuser, because `tax_rate`
carries `FORCE ROW LEVEL SECURITY`: the table held **zero rows** for all three companies. One product declared a
taxable code (`Martillo`, company `5dc1df8a-…`, `tax_code = IVA`), the `tax_code` catalog had all four codes
enabled, and the order's store chain resolved `Zacatecas` → zone `Monterrey Norte` → region `Norte`
(`2d550fe6-…`). So the refusal was correct and the missing thing was configuration — for every company, not just
this order.

**L3 (2026-11-12) — the operator's question, verbatim.** «en pantalls no se pueden declarar?» Measured:
`life-control-app-angular/src/features/tax/` holds four files, all of them `data/tax-code.service.ts` and its
model/spec/index — a read of `GET /api/tax-codes` consumed only by `product-edit.ts` to fill a select. No page, no
route, no menu entry. The server side has had the whole ABM since issue #858, so the gap is **UI only**.

**L4 (2026-11-12) — the unblocking declaration, and its residual scope.** Two rows inserted in `dev`, `IVA`
16 % company-wide (id `115a6970-…`) and `IVA` 8 % for region `Norte` (id `aefb5623-…`), both `effective_from
2026-11-10` — the day of the refused document and the oldest document in the tree, so no history is invented. The
region belongs to the same company (checked: a foreign key proves the region exists, not that it belongs to this
tenant). Verified against the resolver's own predicates: region step `0.080000`, company step `0.160000`,
"the region has rows" `1`, and a date before the window resolving to nothing. Declared by SQL as superuser
because `tax_rate` forces RLS and no operator credential was available; the sanctioned writer remains
`POST …/tax-rates`, and the screen built here is what makes it reachable without one.

**L5 (2026-11-12) — the structure decision, verbatim.** Quoted in S1. The reason it is the right shape: `header.ts`
builds `menuItems` by hand with one role gate per entry (`src/core/layout/header/header.ts:68-192`), so a top-level
entry per catalog grows the menu linearly with the catalog list. One entry plus a card grid keeps `features/tax/`
as the domain and makes `features/configuration/` an index rather than a junk drawer — the route tree nests the
domain under the landing (`configuration` → `taxes` → `@features/tax/tax.routes`).

**L6 (2026-11-12) — the two decisions explained to the operator, and what they force on the screen.** Succession
explained: no editor exists because a rate row is history, not a current price, and `supersedeTaxRate` inserts the
successor **without touching** the superseded row (it does not even disable it) — disabling it would break every
document dated before the successor. Precedence explained: after `Norte` states a row for `IVA`, a document of that
store dated **before** 2026-11-10 stops resolving the company-wide 16 % and becomes a 400 gap; documents already
written keep their snapshot, so what changes is what a *new* document with an old date resolves. That is why S5
forbids an editor and S7 puts the warning where the region is chosen.

**L7 (2026-11-12) — the recommendations the operator accepted with this record, verbatim.** «ok genera el odr»,
after «cuales son tus recomendaciones?». Adopted: three chained slices of ~400 authored lines rather than two (S1
read-only first, because it makes the refusal diagnosable at the lowest risk; S2 and S3 are the writes); percent
entry instead of a fraction (S4); no default seeding of `tax_rate` by migration, and one of the three dev companies
deliberately left without rates as the live fixture of the refusal path; the typo correction **deferred** (S11) to
its own issue rather than bundled, and explicitly not solved by a `DELETE`.

**L8 (2026-11-13) — T1 delivered as `b605d6f3`, and the three tests it had never run.** The slice was written on
2026-11-12 and left uncommitted; its suite had never been executed. Running it found four red tests in three
files, each a real defect and none of them a behaviour change: (a) `tax-rate-list.spec.ts` asserted the display
order by slicing each row's flattened text to three characters, so the last row's "NA" read as "NAT" — its own
neighbour's column; fixed by reading the code cell. (b) `tax-rate.service.spec.ts` never subscribed-and-flushed
the second read, so `httpMock.verify()` reported the pending request as a leak instead of the cache assertion the
test meant; fixed to assert a second GET with a changed body. (c) `header.spec.ts` pinned `/reports` as the last
menu entry and listed the pre-existing ids, both of which the new appended entry legitimately changes; the
PRESERVE pin now lists ten entries and the reports case asserts relative order instead of the tail. Lint also
found an unused `component` local in the list spec. Green at commit: `npm run lint` clean, 138 tests across the
seven touched suites. **The lesson, not the detail**: an uncommitted slice is an unverified slice, and "the tests
exist" is not "the tests pass" — the worktree had both, and only the second is evidence.

**L9 (2026-11-13) — T2 delivered, and the one UI decision the record had not made.** The declare form needs a
region to offer, and `CompanyRegionController` keys regions on `/companies/{companyId}/countries/{companyCountryId}/regions`
— there is no "regions of a company" read. So the dialog walks the same cascade every existing screen walks
(countries, then each country's regions) and flattens it into one `Alcance` select, labelling each option with its
country name only when the company has more than one. No country picker is added to the form: the operator chooses
a scope, not a geography, and the country only exists to reach the region. The tax code is a select over
`GET /api/tax-codes` (enabled rows) rather than a free text field, because an unknown code is a 404 on a form that
looks complete. RED observed for both halves before the implementation: the three page rules failed against the
previous page, and after wiring, the three payload tests failed with `rate: 16` where `0.16` was expected — the
defect `CHECK (rate >= 0 AND rate <= 1)` cannot catch.
