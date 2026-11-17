# ODD feature: customers-fiscal-form

**Repository**: LifeControl — `life-control-app-angular` (the create/edit pages of the `customers` feature and the receiver's fiscal profile, plus the CFDI-use vocabulary in `shared/fiscal`), `odd/tasks/`
**Branch**: `feat/customer-fiscal-form`, cut from `origin/main @ 266742ec`
**Status**: **implemented** — the second and last half of issue #940: a customer is created and edited from the product, with the receiver's fiscal profile and the all-or-nothing rule the API enforces stated **before** the request leaves the browser. It finishes what `odd/tasks/customers-screen.md` started (the list, its route and its menu entry, merged as `266742ec`) and so closes #940. Nothing is deferred.
**Created**: 2026-11-17 · **Risk**: none of the six. It adds routes, handlers and validation to a feature that is one day old; no existing contract changes shape, and the write path is validated by the API on arrival — a partial fiscal profile is a 400 that names the field, so this form cannot store one.

## Objective

Close the loop the fiscal document opened. `FiscalReceiverProfileIncompleteException` refuses a document whose receiver has no fiscal profile, and until now the profile could only be written with a hand-made request: `#940` exists because the app had no customer screen, and `odd/tasks/customers-screen.md` delivered only its read half. This unit delivers the write half.

## Problem — measured, not assumed

| Fact | Evidence |
| --- | --- |
| `salesChannel` is required and has no vocabulary | `CustomerRequest.java` `@NotBlank("salesChannel is required")`; column `VARCHAR(50) NOT NULL` with no enum, no `CHECK`, no catalog endpoint; the only value seeded anywhere is `'TODOS'` (`V3__seed_reference_data.sql:404`) |
| The fiscal rule is all-or-nothing over **four** fields | `FiscalProfileCompleteValidator`: `legalName`, `regimenFiscal`, `fiscalZipCode`, `defaultUsoCfdi` — stated together or not at all; `fiscalStreet`, `fiscalState`, `fiscalCountryId` are never consulted |
| The refusal names each missing field | one violation per missing property, whose key is the field name the form must highlight |
| The API refuses a partial profile at write time | so the screen's rule is a copy of a rule that is also enforced, never its only enforcement |
| Update is per field | `CustomerService` writes a field only when the request carries it; an unknown `regimenFiscal`/`defaultUsoCfdi` is a 400 naming the field |
| The CFDI-use vocabulary is the same shape as the régime's | `UsoCfdi.java:43-…`: `/** {@code G01} — Adquisición de mercancías. */`, 25 codes, two of them four characters (`CP01`, `CN01`) |
| A countries service already exists | `features/countries/data/country.service.ts` `getCountries()` over `GET /api/countries` — no new plumbing for the fiscal country select |

## Specs

Quoted from issue #940's acceptance criteria, verbatim; the derived consequences say so.

**S1** — "A `customers` feature: list with search, **create and edit**, a route file under `features/customers/`, and a menu entry…" — the create and edit half, which `odd/tasks/customers-screen.md` did not deliver.

**S2** — "The form round-trips `CustomerRequest`/`CustomerResponse`: `name`, `email`, `phone`, `rfc`, `salesChannel`, `enabled` and the seven fiscal fields."

**S3** — "The seven fiscal fields obey the API's all-or-nothing rule **before** submitting, naming each missing field the way `FiscalProfileComplete` does, and a customer that declares no fiscal profile is accepted — the profile is required to issue, never to sell."

**S4** — "The régime vocabulary is the **same** shared const the issuer screen uses, one copy, and the CFDI use vocabulary gets the same kind of parity test against `UsoCfdi.java` that `RegimenFiscal` has."

**S5** — "Unit tests cover the round trip, the all-or-nothing refusal and the empty-profile acceptance; `npm run lint` and `npm run test:coverage:check` pass."

**S6** — Non-goals, verbatim: "Issuing, reprinting or cancelling a document — that is #932." · "Validating an RFC beyond the shape the server already validates." · "Changing the API's contracts: the columns and the endpoints already exist."

**S7** — (derived from the API, `L3`(a)) `salesChannel` is a required free-text field with no default and no invented catalog, whose hint names the only value the tree seeds.

**S8** — (derived from S2, `L3`(b)) The fiscal country is a select over the existing `GET /api/countries`, because `fiscalCountryId` is a `UUID` and a free-text field would let the operator type a non-existent country that the API answers with a constraint failure instead of a named refusal.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S4 | The CFDI-use vocabulary as one shared const beside the régime's, ordered as the enum orders it, with its parity spec against `UsoCfdi.java` — and a code pattern that admits `CP01` and `CN01`, or two of the 25 disappear in silence | — | inline | `feat(customers)` |
| T2 | S1, S2, S7, S8 | The create/edit pages: one component for both modes, the wire-body builder, the `create` and `edit/:id` routes, the card action that finally goes somewhere, and the `salesChannel`/country fields | T1 | inline | `feat(customers)` |
| T3 | S3, S5 | The all-or-nothing rule: a group validator that emits one violation per missing field, keyed by the API's own property names, plus the specs for the round trip, the refusal and the empty-profile acceptance | T2 | inline | `feat(customers)` |

## Log

**L1 (2026-11-17) — the maintainer's decision, verbatim.** «Mergeá #942 y seguí desde main», which merged the list slice as `266742ec` and cut this branch from it. The issue's approval is the one recorded in `odd/tasks/customers-screen.md` `L1`.

**L2 (2026-11-17) — what this unit inherits and must change.** Slice 1 left two deliberate pins that this unit **has to move, and moving them is the proof it did its job**: `customers.routes.spec.ts` asserts "declares no create or edit route in this unit" and `customer-list.spec.ts` asserts the cards carry no action. Both were true and both stop being true here; a writer that "fixes" the failure by deleting the assertion instead of inverting it has broken the record.

**L3 (2026-11-17) — three decisions, and the reason for each.**
(a) **`salesChannel` is free text, required, with no default.** The API requires it; no endpoint serves a vocabulary; the column is `VARCHAR(50)` with no `CHECK`; the only value the tree ever seeds is `'TODOS'`. A closed select would be a catalog invented in the UI, and pre-selecting `'TODOS'` would write a meaning nobody chose — the argument `V57`/ADR-0002 decision 2 makes against a default currency, applied to a field with even less authority behind it. The hint names `'TODOS'` as what the tree seeds and leaves the choice to the operator.

(b) **The fiscal country is a select over the countries service, not a text field.** `fiscalCountryId` is a `UUID`; a typo in a text field reaches the database as a foreign-key failure, which is the class of answer `V102`'s own javadoc argues against — a named 400 in the service beats a constraint violation that names only the rule. `fiscalStreet` and `fiscalState` stay free text because that is all the API asks of them.

(c) **The CFDI-use regex admits one or two letters plus two digits.** `UsoCfdi` holds `G01…G03`, `I01…I08`, `D01…D10`, `P01`, `S01`, `CP01` and `CN01`. A pattern written from the first three groups (`[A-Z]\d{2}`) matches 23 of 25 and drops `CP01`/`CN01` — silently, because the parity spec would then compare 23 against 23 and pass. The pattern and the count are therefore asserted together.

**L4 (2026-11-17) — what this unit does not touch.** The list, the service, the models and the menu entry from slice 1 keep their behaviour; the card gains one output and the page wires it. No API contract moves (`S6`), and a customer with no fiscal profile stays a complete row: the empty profile is a case the specs assert, not a state the form warns about.

**L5 (2026-11-17) — what the tree forced, and what this screen cannot do.** Findings from building T1–T3 that this record did not already name.

(a) **A fiscal field cannot be cleared through this screen, and the screen says so instead of pretending.** `CustomerService.updateCustomer` writes `regimenFiscal`, `fiscalStreet`, `fiscalZipCode`, `fiscalState`, `fiscalCountryId` and `defaultUsoCfdi` **only when the request carries them** (`CustomerService.java:135-152`), and `CustomerRequest`'s canonical constructor normalizes blank to `null`. So `null` means *leave what is stored*, and emptying one fiscal field — or all four — of a row that already declares a profile is not expressible: the row keeps its value. The alternative is a delete endpoint, which `S6` excludes, so the form offers «— No declarado —» only while nothing is declared (the shape `CompaniesForm.hasDeclaredRegimen` already had) and the all-or-nothing rule keeps the result consistent: a row is empty or complete, never partial. Stated in `customer-edit.ts` beside the `updateCustomer` call.

(b) **The body sends `null`, never `''`, for a blank — and for `fiscalCountryId` that is not a style choice.** `CustomerRequest.fiscalCountryId` is a `UUID`, so an empty string fails deserialization **before** any validation (`400 Malformed or unreadable request body`), which is exactly the answer `L3(b)` refuses for a typed country. `CompanyRequest`'s two fiscal fields are `String`s, which is why the issuer page could send `''`; here `blankToNull` trims and maps to `null` while the key stays present, so a `PUT` replacement is still explicit.

(c) **The refusal cannot live on the group alone.** Angular Material renders `<mat-error>` from its **control's** error state — `_form-field-chunk.mjs` projects the error content in the `@case ('error')` branch of `_getSubscriptMessageType()`, which reads `_control.errorState` — so a group error would mark the form invalid and paint nothing. The rule therefore publishes `{ fiscalProfileIncomplete: { legalName: '…', regimenFiscal: '…' } }` on the fiscal sub-group, keyed by the API's own property names, and `CustomerForm` moves each sentence onto the control it names. The paint recomputes the violations from the group's value with the same `fiscalProfileViolations` the validator calls, so it does not depend on whether the group's validator ran before or after the `valueChanges` subscription. One derivation behind both: `mat-error` beside the field, and `form.invalid` for the save.

(d) **The list route had to state `pathMatch: 'full'`.** `''` now sits beside `create` and `edit/:id`: the two new children are declared first and the list child says `pathMatch: 'full'`, which the inverted route spec pins. Without it the empty path is a prefix route that answers `/customers/create` itself.

(e) **A create opens the row it created, and only an update returns to the list.** The task allowed either destination; `/customers/edit/{id}` is chosen because the receiver's fiscal profile is often the fact the operator does not have in hand while typing the trade fields, and because `company-edit.ts:204-230` already chose that direction for the issuer's page. Both modes are pinned by their own assertion.

(f) **Two test mechanisms, measured rather than assumed.** A `mat-select` renders no `mat-option` until it is opened, so the vocabulary assertions open the select and read `document.querySelectorAll('mat-option')` — the mechanism `companies-form.spec.ts:176-188` already used for the currency and régime selects. And `RouterLink` hands the router a `UrlTree`, not a string, so the header's «Nuevo cliente» destination is read back as `String(navigateByUrl.mock.calls[0][0])` instead of a literal.

(g) **What was verified, and the one gate this task did not run.** RED observed as the missing modules (vocabulary, rule, form, page) before any implementation. GREEN: the focused run `npx ng test --no-watch --include='src/features/customers/**/*.spec.ts' --include='src/shared/fiscal/**/*.spec.ts'` → **9 files, exit 0**; the whole suite `npx ng test --no-watch` → **214 files / 4390 tests, exit 0**; `npx ng lint` → exit 0, «All files pass linting»; `npx prettier --check` over the touched files → exit 0. `S5`'s `npm run test:coverage:check` is the one gate **not** run here, because the delegated verification named `ng test --no-watch`, `ng lint` and the prettier check; the suite does run with the project's coverage configuration and printed its summary, but the threshold script is a separate command.
