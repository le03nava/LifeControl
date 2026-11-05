# ODD feature: company-currency-form

**Repository**: LifeControl — `life-control-api` (`currency/`, `common/tenant/TenantScopeFilter`), `life-control-app-angular` (`features/companies/companies`), `odd/tasks/`
**Branch**: `feat/company-currency-form`
**Status**: implemented — the unit is the read-only currency catalog endpoint, its tenancy-agnostic registration, the activity process the new controller derives, and the company form's required currency select (create and edit). T1–T5 are the code, T6–T7 the tests that pin both ends, T8 the docs and T10 the one reference row the full suite demanded. Nothing is deferred: no schema is changed, and the single migration is an idempotent seed (`V78`), because `V57__company_currency.sql` already created the catalog and the `NOT NULL` tenant column.
**Created**: 2026-11-05 · **Risk**: none — no stored data is rewritten, no format changes, and no consumed contract breaks: `GET /api/currencies` is added, and `currency` already existed on `CompanyRequest` and `CompanyResponse` before this unit (the Angular client simply never sent it). `V78` inserts one reference row guarded on its natural key, the shape `V27` uses.

## Problem

`POST /api/companies` answers **400** `{"status":400,"message":"Validation failed","errors":{"currency":"currency is required"}}` for a payload the Angular form produces, and the same happens on **PUT**: the edit screen cannot save any existing company. The API is right — `CompanyRequest.currency` is `@NotBlank` and `CompanyService.resolveCurrency` refuses a blank for every non-HTTP caller, because ADR-0002 decision 2 (D-c) deliberately has no default: a silent `MXN` writes one country's currency onto another's sales. The gap is the client, which has no currency at all, and the label-free cause is that nothing exposes the catalog for reading.

| Evidence | Where |
| --- | --- |
| Required, no default | `life-control-api/src/main/java/com/lifecontrol/api/company/dto/CompanyRequest.java:62`, `.../company/service/CompanyService.java:346` |
| Create body built as an explicit literal, no `currency` | `life-control-app-angular/src/features/companies/companies/components/companies-form/companies-form.ts:127-156` |
| Edit body rebuilt key by key, shared by POST and PUT | `.../pages/company-edit/company-edit.ts:209-236` (used at `:176`, `:188`) |
| API already returns it; the interface drops it | `.../company/dto/CompanyResponse.java:29`, `CompanyService.java:313`, `company.models.ts:21-36` |
| No read surface exists | `life-control-api/src/main/java/com/lifecontrol/api/currency/` holds only `model/`, `repository/`, `exception/` |
| Closed tenancy-agnostic list — a missing entry is a silent 403 | `life-control-api/src/main/java/com/lifecontrol/api/common/tenant/TenantScopeFilter.java:107-121` |

## Specs

**S1** — `GET /api/currencies` serves the platform catalog as a flat list of `{code, minorUnits, symbol, enabled}`. By default only `enabled` rows; `?includeDisabled=true` also returns the retired codes, the shape `/api/measure-units` already has.

**S2** — Any authenticated principal reads it; a request with no token is **401**. The catalog is global — it has no tenant column — and it grants no write.

**S3** — `/api/currencies` is registered in `TenantScopeFilter.TENANT_AGNOSTIC_PATHS`. Without that entry a token carrying `company_*` claims is refused with **403** while plainly authenticated, and no security test would say why.

**S4** — The companies form cannot be saved without a currency. The select starts **empty** — no pre-selection of `MXN`, because a pre-filled value is the default D-c refuses, one layer up — is bound with `Validators.required`, and renders the existing `Este campo es obligatorio.` message when empty.

**S5** — The edit screen shows the company's stored currency and lets it change. Rows that existed before `V57` carry `MXN` from that migration's backfill, so no company becomes unsaveable.

**S6** — The request body stays the explicit literal in `buildCompanyRequest()`: one key, `currency`, is added. The mechanism does not change, because the API runs with `fail-on-unknown-properties=true` and a stray key is a 400 of its own.

**S7** — No new migration. `companies.currency CHAR(3) NOT NULL REFERENCES currency (code)` and its seed row already exist in `V57__company_currency.sql`, and this unit writes none of them.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S1, S2, S3 | API: `currency/dto/CurrencyResponse`, `currency/service/CurrencyService` (`@Cacheable("currencies")`), `currency/controller/CurrencyController` (`GET`, `@PreAuthorize("isAuthenticated()")`, `includeDisabled`), `findByEnabledTrue()` on `CurrencyRepository`, the `TENANT_AGNOSTIC_PATHS` entry, and the `currencies` region in `CacheConfig.simpleCacheManager()` | — | writer (backend unit) | `1ae71bce` |
| T2 | S1, S2, S3 | API tests: `CurrencyControllerTest`, `CurrencyControllerSecurityTest` (401 without token, 200 with any authenticated role, `includeDisabled` filtering), the `TenantScopeFilterIntegrationTest` case that fails without the S3 entry, and the two cache parity tests the new region moves (`CacheManagerSelectionTest`, `CacheValueCodecTest`) | T1 | writer (backend unit) | `1ae71bce` |
| T2b | S1 | Pin the one-entry-per-shape invariant with `CurrencyServiceCacheTest`, and correct the stale "six cache regions" prose in `RedisOutagePolicyTest` | T2 | inline | `1ae71bce` |
| T3 | S4, S5 | Angular model: `currency` on `Company`, `CompanyRequest` and `CompanyControl` | — | writer (web unit) | `40e0f8a3` |
| T4 | S1 | Angular data: `Currency` model and `CurrencyService` cloned from `country.service.ts` (signals + `getCurrencies(force)`, no `includeDisabled` per D2) | T1, T3 | writer (web unit) | `40e0f8a3` |
| T5 | S4, S5, S6 | Angular UI: required `currency` control, catalog-loaded `<mat-select>` following `address-form.html`'s country select, catalog passed as an `input()` from the page, and `currency` in `buildCompanyRequest()` | T3, T4 | writer (web unit) | `40e0f8a3` |
| T6 | S4, S5, S6 | Angular tests: `fillRequiredFields()` fills the new control, exact POST/PUT body assertions, and every `Company` mock that gains a required field — including the nineteen literals in the fifteen sibling specs the spec-wide typecheck surfaced | T5 | writer (web unit) | `40e0f8a3`, `6d2650b1` |
| T7 | S4, S5 | E2E: `e2e/specs/companies.spec.ts` fills the select and adjusts the required-error count, and `e2e/mocks/api.ts` serves `/api/currencies` | T5 | writer (web unit) | `40e0f8a3` |
| T8 | S1, S3, S6 | Docs: `AGENTS.md` (the catalog endpoint, the tenancy-agnostic list, the cached-region list, and the company form's required field) and `life-control-api/AGENTS.md` | T1–T7 | inline | this commit |
| T9 | — | Verification: the API slice, `npm run test:coverage:check`, `npm run lint`, `npm run test:e2e` | T2b, T6, T7 | inline | — |
| T10 | S3 | `V78__seed_currency_process.sql` — seed the `CURRENCY` activity process the new controller derives, which the full API suite refused without (`ActivityLogIntegrationTest`) | T1 | inline | this commit |

**Total task count: 11.**

## Decisions

**D1 (2026-11-05) — the select starts empty.** Pre-selecting `MXN` would restore, one layer up, exactly the default ADR-0002 D-c refuses on the server. The operator states the currency; one click is the price of the decision holding where it is visible.

**D2 (2026-11-05) — only enabled codes are offered.** `?includeDisabled=true` exists on the endpoint for an administrative future, not for the form: a retired code would be refused by `CompanyService.resolveCurrency` with a 400 anyway, so offering it turns a data question into a validation error.

**D3 (2026-11-05) — documentation goes to both places.** `AGENTS.md` is the contract the next agent reads; `docs/` is what a person reads. One of them alone leaves half the readers guessing.

## Log

**L1 — the user's original request, verbatim** (2026-11-05):

> puedes revisar el error Request URL
> http://localhost:9000/api/companies
> Request method
> POST
> Status code
>  {
>     "status": 400,
>     "message": "Validation failed",
>     "errors": {
>         "currency": "currency is required"
>     },
>     "path": "/api/companies",
>     "timestamp": "2026-11-05T04:13:50.042685553",
>     "correlationId": "971d2b6e99d416f75642e4f7c45d288b"
> }, este es el json {"companyKey":"EMP001","companyName":"Empresa1","tipoPersonaId":1,"razonSocial":"NAAL870131RH2","rfc":"NAAL870131RH2","phone":"+5280123456789","email":"leo3nava@gmail.com","emailDomain":"","enabled":true,"address":{"street":"Janitzio","streetNumber":"66414","internalNumber":null,"neighborhood":null,"zipCode":"66414","city":"San Nicolas de los Garza","state":"Nuevo León","countryId":"1669e5cc-2293-48e1-9411-257c5d76cdda"}}

**L2 — the user asked for the plan** (2026-11-05): «arma el plan para la correccion».

**L3 — the user answered the three open decisions** (2026-11-05): «d1 vacio, d2 solo habiliadas, d3 los dos» — recorded as D1, D2 and D3 above.

**L4 — S3's trap was real, and observed** (2026-11-05): with the controller in place and `TENANT_AGNOSTIC_PATHS` untouched, the new tenancy case failed `Status expected:<200> but was:<403>` at `TenantScopeFilterIntegrationTest.java:210` — refused before routing while plainly authenticated. Adding the entry removed it.

**L5 — the cache region is a second, invisible trap: one layer down and outside the first delegation's surfaces** (2026-11-05): after the S3 entry the same request answered **400** `Cannot find cache named 'currencies'`, because `CacheConfig.simpleCacheManager()` pre-registers its regions and `SimpleCacheManager` cannot create a seventh. Production is unaffected (Redis creates regions on demand), so no controller test could have caught it — only the real-context tenancy case did. Three coupled sites moved together: `CacheConfig`, `CacheManagerSelectionTest` (the pinned set and its prose say seven now) and `CacheValueCodecTest` (the `CurrencyResponse` pair).

**L6 — a RED check that lied, and what it taught** (2026-11-05): the first mutation run reported success because the `perl` substitution had not matched the annotation — a RED that never mutated is a second green, and it was discarded rather than reported. The corrected mutation then showed something better: dropping `key = "'all-' + #includeDisabled"` entirely still passes, because Spring's default key generator derives the key from every method parameter. The explicit expression is **redundant, not load-bearing**. With a **constant** key, two of the three cases in `CurrencyServiceCacheTest` fail — so the test pins the one-entry-per-shape invariant, which is what S1 requires, and not the expression that happens to satisfy it.

**L7 — the client's required field reaches further than one screen** (2026-11-05): making `Company.currency` required turned up nineteen `Company` literals in fifteen sibling specs across `countries`, `regions`, `stores`, `zones`, `hr`, `products` and `profile`. `angular.json` compiles every `src/**/*.spec.ts`, so they are a red suite, not a style nit; each gained one `currency: 'MXN'` line and nothing else. `tsc -p tsconfig.spec.json --noEmit` is clean.

**L8 — a follow-up this unit does not take** (2026-11-05): an existing company whose currency is later disabled cannot be saved at all today, because `CompanyService.updateCompany` calls `resolveCurrency` on every update and `resolveCurrency` refuses a disabled code **even when the value is unchanged**. Pre-existing API behaviour; today the catalog holds one enabled row (`MXN`), so the state is unreachable. Left out of scope on purpose — and the UI does not invent a disabled option for it either (D1/D2): the catalog renders as the catalog.

**L9 — D3's "docs/" resolves to the two `AGENTS.md` surfaces** (2026-11-05): `docs/` holds only `adr/`, and every file there is a frozen decision record. Writing a new ADR for a catalogue endpoint would be a decision nobody took, and rewriting `0002-money-first-class.md` would destroy frozen evidence — so the human-readable half of D3 lands in the root `AGENTS.md` (`## Moneda de la empresa`) and the module one (`/api/currencies` in the endpoint map, `currencies` in the cached regions, and a new subsection on `TenantScopeFilter`'s closed exemption list, which is the trap this unit walked into).

**L10 — the full suite caught a coupling the plan did not see** (2026-11-05): `./gradlew test` failed 2 of 4524 with `activity process 'CURRENCY' should be seeded — ActivityLogService discards its entries with only a WARN`. The guard derives a process name from every production controller's **package**, so `currency.controller` derives `CURRENCY`, and a process absent from `activity_processes` is dropped with a WARN — a catalog read would have been logged and thrown away, invisibly. That is the failure `V27` documents for `HR`, `PROVISIONING` and `GOODSRECEIPT`, and the guard exists precisely so the next domain added without a seed turns the suite red instead of going dark. `V78` seeds it, idempotent on the natural key. This is why the full suite runs even when the focused slices are green: `spotlessCheck` also failed on a line of `CurrencyServiceCacheTest`, which no focused run checks.

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-11-05 | `1ae71bce` — API unit: `GET /api/currencies`, the `TENANT_AGNOSTIC_PATHS` entry (RED `expected:<200> but was:<403>` observed first), the `currencies` cache region, and the one-entry-per-shape test (RED observed against a constant key: 2 of 3 failed) |
| 2026-11-05 | `40e0f8a3` — web unit: required `Moneda` select starting empty, `CurrencyService`, one key in `buildCompanyRequest()`, e2e mocks. Focused vitest 24 files / 514 tests green; `ng test --no-coverage` on the companies feature 19/19 after the REDs |
| 2026-11-05 | `6d2650b1` — the nineteen `Company` literals in twenty-five sibling spec files, which `tsc -p tsconfig.spec.json --noEmit` reported as `TS2741` before and accepts after |
| 2026-11-05 | `.github/scripts/tests/odd-status-headers.test.sh` — 78 passed, 0 failed, 41 fixtures asserted |
| 2026-11-05 | Angular gates — `npx prettier --check "src/**/*.{ts,html,scss}"` clean, `npm run lint` clean, `npm run build` complete, `npm run test:coverage:check` OK on all four thresholds (86.57 / 86.22 / 80.91 / 89.51). The full unit suite ran inside the gate **without** the ten hook timeouts an earlier standalone `ng test` produced under load — contention, not regressions |
| 2026-11-05 | API suite, full — `./gradlew test --no-daemon`: **4524 tests, 0 failed** (8m 36s, 1073 result XMLs). `spotlessCheck` and `spotbugsMain` green. `ActivityLogIntegrationTest$ActivityProcessSeedTests` 2/2 with `V78` in place |
| 2026-11-05 | Playwright e2e — `E2E_PORT=4300 npm run test:e2e`: **19 passed** (20.8s). The first attempt failed 2 of 19 for an environment reason worth recording: port 4200 is held by the `lifecontrol-life-control-app-angular` dev container at revision `c95c8510` (an ancestor, built before the form change), and `reuseExistingServer: !CI` made Playwright attach to it, so the run measured a build that has no `Moneda` field — `git show c95c8510:…/companies-form.html | grep -c Moneda` → 0. Unverified, not a defect; `E2E_PORT` freed it without touching the stack |
