# ODD feature: fiscal-issuer-profile

**Repository**: LifeControl — `life-control-app-angular` (the company form and its models), `odd/tasks/`
**Branch**: `feat/fiscal-profile-screens`, cut from `origin/main @ 4a5caf6f`
**Status**: **implemented** — the issuer's half of issue #931, and only that half: the company screen now declares the régimen fiscal and the place of issue that `V102` gave the `companies` row. The receiver's half is **not** in this unit and could not be, because the app has no customer screen at all — it is filed as its own issue and its own unit. Nothing else is deferred here.
**Created**: 2026-12-01 · **Risk**: none of the six. The two new request fields are **optional and additive** (`CompanyRequest` has carried both since `V102`, nullable by design), no existing field changes shape, no route or menu entry moves, and the two rules the screen states — the vocabulary and the five-digit shape — are the same ones the API already enforces, so a mistake here is a wrong label or a refused 400, not a wrong stored fact.

## Objective

Let an administrator declare, from the product, the two facts the fiscal document freezes as the issuer: the régimen and the place of issue. Today the API refuses a document with `FiscalIssuerProfileIncompleteException` and the only way to satisfy it is a hand-written request.

## Problem — measured, not assumed

| Fact | Evidence |
| --- | --- |
| The API has both fields | `CompanyRequest.java:80` (`regimenFiscal`, nullable) and `:88` (`fiscalZipCode`, `@Pattern("\\d{5}")`) |
| The API returns both | `CompanyResponse.java:36-37` |
| The app declares neither | `company.models.ts` — zero matches for `regimenFiscal` / `fiscalZipCode` in `Company`, `CompanyRequest`, `CompanyFormValue`, `CompanyControl` |
| The form is built in two places | `company-edit.ts:155` `createForm()` and the inline rebuild inside `loadCompany()` |
| The value cannot be cleared once stored | `CompanyService.java:281-285` writes each fiscal field **only when non-null**, so a request that omits it leaves what is stored |
| The vocabulary has no HTTP reader | `RegimenFiscal` is a Java enum with 19 codes; the only fiscal GET is `/api/fiscal-documents/{id}/xml` |

## Specs

Quoted from issue #931's acceptance criteria, verbatim, for the half this unit delivers.

**S1** — "The company form captures the regime as a select over the 19 codes the API declares, and the fiscal zip as five digits, both round-tripping through `CompanyRequest`/`CompanyResponse`."

**S2** — "A profile left empty is accepted: the profile is required to issue, never to sell (`S2`), and a cash sale must keep working with a customer that states nothing." The issuer reads this as: a company that declares neither field stays a complete row and keeps saving exactly as it does today. The receiver sentence belongs to the follow-up issue.

**S3** — "The vocabularies are read from the API's enums rather than duplicated in the app, or the duplication is stated with the test that keeps the two in step."

**S4** — "Unit tests cover the round trip and the refusal; the existing customer and company suites stay green."

**S5** — Non-goal, verbatim: "Changing the API's contracts: the fields already exist." No endpoint is added to serve the vocabulary; the const is duplicated and S3's test is what keeps it honest.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S3 | The régime vocabulary as one shared const, with the spec that reads `RegimenFiscal.java` and fails when the two lists disagree | — | inline | `feat(fiscal)` |
| T2 | S1, S2, S4 | Both controls through the two form builders, the request builder, the template with its field errors, and the specs for the round trip and the refusal | T1 | inline | `feat(fiscal)` |

## Log

**L1 (2026-12-01) — the maintainer's request, verbatim.** «con que seguimos de la parte del timbrado y fiscal?», and then, choosing the track: «#931 + #932 encadenados», and on the label: «Aprobá los dos (por instrucción mía)». `status:approved` was applied to #931 and #932 on that instruction, each with the provenance recorded on the issue.

**L2 (2026-12-01) — the unit split, decided by the maintainer after the measurement.** The exploration found that `life-control-app-angular` has **no customer screen at all**: no `features/customers`, no route, no menu entry, and the only customer surface is the sale's autocomplete selector. #931's criteria say "a sales user can declare a customer's fiscal profile on the customer screen", so delivering its receiver half means building a customers feature — list, create, edit, route, menu, RBAC — which is neither 400 reviewable lines nor the unit the issue describes. Offered three ways to cut, the maintainer chose: "Sólo el emisor ahora; clientes como issue nuevo". Consequence, stated rather than hidden: **#931 closes with its receiver criterion unmet**, and the file that carries the remaining work is the follow-up issue, not this record.

**L3 (2026-12-01) — three facts the tree stated and the screen must obey.**
(a) **The labels are not invented.** `RegimenFiscal`'s javadoc carries the authority's own description for every code (`601` — General de Ley Personas Morales, … `626` — Régimen Simplificado de Confianza), so the select's options are copied from the enum, and T1's spec reads that file so a code added there without its label here is red.
(b) **A declared régime cannot be cleared, and the screen does not pretend otherwise.** `CompanyService` writes the field only when the request carries it, and the request DTO normalizes `''` to `null`, so "omit" is the only way to say nothing — which the service reads as "leave what is stored". The select therefore offers its empty option **only while the loaded company has declared nothing**: once a régime exists the operator can replace it with another code, and the screen does not offer a `null` that the server would silently ignore. The limit is a limit of the API's update contract, named here instead of worked around with a request shape the DTO does not have.
(c) **The form is built twice.** `createForm()` and the inline rebuild in `loadCompany()` both enumerate the controls, so a control added to one and not the other leaves the field empty after load while looking correct on create. T2 touches both and a spec pins the hydrated value after loading a company.

**L4 (2026-12-01) — where the vocabulary lives.** In `src/shared/fiscal/`, not under `features/companies`, because it is not the issuer's: `customers.regimen_fiscal` reads the same catalog (`V107`), which is exactly why `RegimenFiscal` itself moved out of `company.model` on the API side. A copy under the company feature would have to be duplicated again for the receiver, and two copies of one catalog are two copies free to drift.

**L5 (2026-12-01) — four things the implementation forced, and the evidence.**
(a) **A `computed` cannot follow a form control.** `computed(() => !!this.formGroup().controls.regimenFiscal.value)` is cached through the `formGroup()` input signal and never recomputes on `setValue` — measured: the "stop offering the empty option" case rendered **20** options instead of 19. The gate now reads a `regimenFiscalValue` signal kept in sync by `valueChanges` inside an `effect` that re-subscribes when the group is replaced, the same shape the component already uses for `serverErrors`. Written down because the cached version reads as correct.
(b) **`maxlength` is not a rule.** The five-digit shape needed `Validators.pattern(/^\d{5}$/)` in both builders: `maxlength` limits typing and a paste steps over it, so without the validator `fiscalZipCodeErrorMessages.pattern` could never fire and S1's "five digits" would be a browser hint enforced nowhere. Angular's pattern validator skips an empty value, so S2 (optional) stays true with no `required` on the control.
(c) **Two `toStrictEqual` body pins had to move** (`company-edit.spec.ts`, `+4` lines, nothing else in that file). A whole-body pin is exactly the assertion that must move when the wire body legitimately gains two keys; the alternative — not sending them — would have made the field unclearable from the screen on insert, since the DTO's only spelling for "nothing" is the absent value.
(d) **A label the prose left implicit.** `626` is `Régimen Simplificado de Confianza (RESICO)` — the enum's javadoc carries the suffix, so the const does too, and `L3`(a) omits it. The spec compares verbatim against the enum, so the constant follows the enum and this record's prose is the loose side; it was not edited because the spec is the authority here.
(e) **Evidence, and why two commits.** RED, observed before any implementation: the suite failed to build with `TS2307: Cannot find module './regimen-fiscal'`, `TS2339: Property 'regimenFiscal' does not exist on type 'CompanyFormValue'` and `TS2345: Argument of type '"612"' is not assignable to parameter of type 'never'` (×4). GREEN, after it: `npx ng test --no-watch` → **206 files / 4309 tests / 0 failures** (the full suite, not a filtered run); `npm run test:coverage:check` → statements 86.98%, branches 86.52%, functions 81.35%, lines 89.90%, all above their thresholds; `npx ng lint` → all files pass; `npx prettier --check` → all match. Together the two tasks are **483 authored lines**, past the ~400 review budget the repository asks for, so they land as the **two slices the Tasks table already names** — T1 the vocabulary and its parity spec, T2 the screen — in one pull request. Neither a hash nor a merge identity is written here: both are one command away and neither survives a rebase.
