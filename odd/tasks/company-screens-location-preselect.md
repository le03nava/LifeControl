# ODD feature: company-screens-location-preselect

**Repository**: LifeControl — `life-control-app-angular` only. The three hierarchy list pages
(`src/features/companies/countries/pages/countries-page/`, `.../zones/pages/zones-page/`,
`.../stores/pages/stores-page/`) and their three specs. No API code, no DTO, no schema, no stored data.
**Status**: implemented — the three pages preselect their levels from the shared `LocationPreference`
when the route names none, the route keeps winning when it does, and every rule is pinned by a spec in
the page it belongs to. Nothing is deferred.
**Created**: 2026-10-22 · **Base**: `origin/main` @ `61577d3` · **Branch**:
`feat/company-screens-location-preselect` · **Worktree**:
`~/workspace/LifeControl-worktrees/feat-company-screens-location-preselect` · **Issue**: **#527**
**Risk**: **none**. No wire contract moves: the three pages already sent the same requests with the
same query parameters, and what this unit adds is a selection that a user would otherwise make by hand.
No stored data, no secret, no permission, no concurrency, no pipeline, no dependency. The shared
`LocationPreference` is consumed, never modified, so products, scheduling and regions are untouched.
Each new rule has a spec that fails on the pre-fix tree, and the route path the pages already had gains
its own pin.

## Specs

S1 — The user's request, verbatim:

```
quiero modificar las pantallas de /companies/countries, /companies/zones y /companies/stores para que los selectores aparezcan preseleccionados con las preferencias dle usuario, como lo que se hizo con /companies/regions
```

S2 — The rule this unit **inherits** rather than invents, verbatim from `odd/tasks/location-preference.md`
(`S6`, `S7`, `L13`): the precedence is the route's own query parameters → `profile.location` → `null`,
and the override is **all-or-nothing**. A route that names *any* level answers the location question and
the profile is not consulted for the rest, because a chain stitched from two sources would pair one
company's ancestors with another company's store. `/companies/regions` is the reference implementation
(`regions-page.ts`: `routeCarriesIds`, the two `Preselected` guards, `locationPreference.resolve()` only
when the route names nothing) and the three pages here follow it level by level.

S3 — What each page preselects, and the read that has to hang off it. The level list is the page's own
selectors, not a new contract:

| Page | Selectors it owns | Preselected from the chain | Read the preselection must reach |
| --- | --- | --- | --- |
| `/companies/countries` | Empresa | `companyId` | `getCountries(companyId)` |
| `/companies/zones` | Empresa, País, Región | `companyId`, `companyCountryId`, `companyRegionId` | `getCountries`, `getRegions`, `getZones` |
| `/companies/stores` | Empresa, País, Región, Zona | `companyId`, `companyCountryId`, `companyRegionId`, `companyZoneId` | `getCountries`, `getRegions`, `getZones`, `getStores` |

A level is preselected only from a row the page's own list returned, exactly as the query-parameter
preselection already did: a country, region or zone the company does not have is simply not found, and
nothing is preselected rather than guessed (`S8` of `location-preference`).

S4 — `location: null`, a failed profile read, or a chain whose ancestor the list did not return leaves
the selectors empty and issues **no** store-scoped read. Opening a screen is not a reason to guess a
location, and the fail-closed state the regions page established holds here unchanged.

S5 — What must keep working, and what covers it. The pre-existing cases of the three specs stay green
and unchanged (the create-button gating, the toggles, the write handlers, the cards, the error and
loading states), and two behaviors gain their own pin because this unit touches the code that carries
them:

- The deep link the edit screens already emit (`?companyId=&countryId=&regionId=[&zoneId=]`, written by
  `zones-edit` and `stores-edit` on save and on cancel) keeps preselecting **exactly** the ids the URL
  names, and `GET /api/profile` is **not** requested on that path.
- A URL that names only some levels keeps the named ones and leaves the rest empty — the route answered
  the location question, so the preference is not consulted for the remainder.

S6 — What this unit deliberately does **not** do. It does not touch `LocationPreference`, the profile
API, the products/scheduling contexts, the purchase-order cascade, the sales-order create screen, or
`/companies/store-areas`, `/companies/store-zones` and `/companies/store-locations` (which extend
`StoreCascadePage` and resolve their own store). It does not change what any selector **lists**: the
zones page's region selector keeps requesting without `includeDisabled`, so a disabled ancestor stays
unpreselected (declared as `G1` below) instead of silently changing a dropdown's contents.

S7 — Opening a screen never writes the preference. No page in this unit calls `PUT /api/profile`, and
the shared resolution is read-only from here: the only writer stays `/profile` (`S9` of
`location-preference`).

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S2, S3, S4 | `countries-page`: preselect Empresa from the chain, RED specs first | `442f95f` |
| T2 | S2, S3, S4 | `zones-page`: preselect Empresa, País and Región | `1ee9af1` |
| T3 | S2, S3, S4 | `stores-page`: preselect Empresa, País, Región and Zona | `b485504` |
| T4 | S5 | the route path's own pins, in the page that owns each one | `442f95f`, `1ee9af1`, `b485504` |
| T5 | S3, S5 | gates: focused specs, coverage, lint, build, differential | this commit, evidenced in `L5`–`L7` |

## Log
L1 (user, verbatim): «quiero modificar las pantallas de /companies/countries, /companies/zones y
/companies/stores para que los selectores aparezcan preseleccionados con las preferencias dle usuario,
como lo que se hizo con /companies/regions»

L2 (measurement, 2026-10-22, against `origin/main @ 61577d3`). The seam is not one shape, and reading it
rather than assuming it is what fixed the task list:

- `regions-page.ts:63-70,168-190` is the reference: `routeCompanyId`/`routeCountryId` captured once,
  `routeCarriesIds`, `locationPreference.resolve()` only in the negative branch, and one
  `<level>Preselected` boolean guard per level.
- `zones-page.ts:70-79,180-201` and `stores-page.ts:78-90,252-288` already have the same skeleton for
  the query parameters (`initialCountryId`/`initialRegionId`/`initialZoneId` plus a guard per level), so
  each one is extended rather than rebuilt: the `??` fallback and the `routeCarriesIds` gate are the
  whole change.
- `countries-page.ts` is the outlier and the one that needed a decision: it is the pre-`rxResource`
  shape (`implements OnInit`, an imperative `getCountries(...).subscribe()` in `ngOnInit` and in
  `onCompanyChange`, and the template reading `companyCountryService.assignedCountries()` directly), so
  it has no `rxResource` to re-key. Converting it would have moved the template and every rendering
  case in its 318-line spec, which is blast radius this unit refuses to grow. The preselection therefore
  lands as a constructor `effect` that sets the signal **and** triggers the same `getCountries` call the
  other two entry points already make.
- The three specs each build their own `TestBed`, and the default route mock returns `null` for every
  query parameter — so with the change every default test takes the preference path and needs a
  `ProfileService` stub, the way `regions-page.spec.ts` already does. That is a change to the three
  specs' provider lists, not a new fixture: the stub reports `location: null`, so the default state of
  every pre-existing case is exactly what it was.

L3 (design decision, 2026-10-22). The all-or-nothing override of `S2` is applied to the three pages
unchanged, including the case that looks like it should be more helpful: a URL naming **only**
`companyId` leaves País, Región and Zona empty even though the preference knows them. The alternative —
taking the named company from the URL and the rest from the preference — is the exact chain `L13` of
`location-preference` rejected, because the preference's store may belong to a different company. The
test names the reading so the next reader does not "fix" it: *the route answered the company question,
so the preference is not consulted for the rest*.

L4 (design decision, 2026-10-22 — and the one place this unit could have grown). The zones page's
`regionsResource` requests `getRegions(companyId, countryId)` with `includeDisabled` left at its
`false` default, so a region that is disabled is absent from the list and a preference pointing at it
preselects nothing. The stores page solved the same problem for its **filter** levels by always
requesting disabled rows (`stores-page.ts:106-110`, with the reason in its comment), and mirroring that
here was the obvious consistency move. It was **not** taken: the two pages show that list differently —
the stores page's region and zone selectors already render disabled rows while the zones page's region
selector does not — so "make them consistent" is a visible change to a dropdown nobody asked to change,
and it is a different question from *which levels start selected*. The measured consequence is declared
as `G1` and the fail-closed reading of `S4` covers it: nothing is preselected rather than something
wrong.

L5 (gate evidence, 2026-10-22). Every number below was read from a report or a command's own exit code,
never from a summary line, and every command ran with `set -o pipefail` (the failure mode `G16` of
`location-preference` records):

- **RED, three times, one per page, before its fix**: countries **1 failed / 22 passed**, zones
  **1 failed / 45 passed**, stores **1 failed / 54 passed** — in each case the failing case is the
  preselect one, with `AssertionError: expected null to be 'company-1'`, and the other new cases pass
  because they pin behavior the tree already had. GREEN after each fix: **23 / 46 / 55 passed**.
- **Focused specs**, the four hierarchy pages plus the shared service they read:
  `npx ng test --no-watch --coverage=false --include='src/features/companies/{countries,zones,stores,regions}/pages/**/*.spec.ts' --include='src/features/user/profile/data/location-preference.service.spec.ts'`
  → **15 test files / 574 tests passed**, exit 0.
- **Coverage gate**: `npm run test:coverage:check` → **186 test files / 3752 tests passed**; statements
  86.78 (17214/19835), branches 86.07 (5650/6564), functions 79.41 (2982/3755), lines 89.43
  (12930/14458) — every metric above its threshold, `[check-coverage] Cobertura dentro de los umbrales. OK`,
  exit 0.
- `npm run lint` → `All files pass linting.`, exit 0. `npm run build` →
  `Application bundle generation complete.`, exit 0.

L6 (differential evidence, 2026-10-22 — the check `S5` asks for, and the one that decides whether the
deep link the edit screens emit still behaves as it did). A temporary harness (never committed, and
deleted afterwards) mounted the three pages with the deep link's query parameters, pushed one line per
observed service call and per final selector value into a log, and was run in two trees with the **same
harness file**, proven byte-identical between them (`diff` exit 0):

- **Base tree** — `git -C <anchor> archive origin/main | tar -x -C "$(mktemp -d)"` at `61577d3`, then
  `npm ci` (exit 0).
- **Worktree** — the change.

The whole point of the harness is the shape of its output, so it is quoted rather than summarized. Both
trees produced this **identical** sequence, in this order:

```
countries.getCountries(company-1)
countries.selected=company-1
countries.getCountries(company-1)
regions.getRegions(company-1,cc-1,false)
zones.getZones(company-1,cc-1,reg-1,false)
zones.selected=company-1|cc-1|reg-1
countries.getCountries(company-1)
regions.getRegions(company-1,cc-1,true)
zones.getZones(company-1,cc-1,reg-1,true)
stores.getStores(company-1,cc-1,reg-1,zone-1,false)
stores.selected=company-1|cc-1|reg-1|zone-1
```

and then, on the bare route (no ids in the URL), `countries.bare.selected=null`,
`zones.bare.selected=null|undefined|undefined` and
`stores.bare.selected=null|undefined|undefined|undefined` — the fail-closed state `S4` requires, unchanged.

The **only** difference between the two trees is three added lines, one per screen, `profile.getProfile()`,
and `diff` reports exactly that and nothing else (`12d11`, `14d12`, `16d13`). That is the intended
consequence of the unit: the three screens now read the preference on the path where the route names
nothing, and they still issue the same requests with the same arguments on the path where it does. No
unrequested difference was found, so there is no regression to fix and no test to add for one.

The harness also produced a second, smaller piece of evidence worth recording because it is what makes
the differential trustworthy: the profile stub in it reports `location: null`, so the bare-route branch
in the **changed** tree still preselects nothing at all — a chain-less preference and an absent
preference are indistinguishable to the screens, which is `S4` stated as a measurement rather than as an
intention.

L7 (delivery strategy, recorded before the third commit rather than after it, 2026-10-22). The unit is
committed as **one commit per screen plus this record as the closing commit**, and the measured size is
declared rather than rounded:

- `442f95f` — T1, the countries page and its spec, **188 authored changed lines**.
- `1ee9af1` — T2, the zones page and its spec, **222**, which puts the branch at **410** and past the
  repository's ~400-line advisory budget.
- the closing slice — T3 (the stores page and its spec, **268**) plus this record.

The overage is 625 authored lines of code and specs in total, of which **206 are production code across
three files** and 419 are the specs' fixtures; the rest of the branch is this record. The declared
reading is that the three screens are **one rule** — the all-or-nothing override of `S2` — so a chain of
three pull requests would make a reviewer read the same rule three times, each slice carrying its own
approved issue, and the two Angular slices would share a single decision that neither can be reviewed
without. The budget is advisory and this is the trade taken, on the record.

The escape hatch is already cut, and it costs nothing because it is only a topology change: the three
commits **are** the three slices, in the order the rule has to be read (countries is the one-level case,
zones the three-level one, stores the four-level one), so the branch can be cut into a chain at exactly
those boundaries if the reviewer prefers the budget — with this record travelling in the parent slice,
the way `location-preference`'s did (`L12` and `L20` of that record). Nothing in the code changes.

## Gaps

G1 (declared, found by `L4`). A preference whose region (on `/companies/zones`) or zone is **disabled**
preselects nothing, because the page's own list does not return it. Measured location:
`zones-page.ts` `regionsResource` (`getRegions` without `includeDisabled`) and the same page's
`zonesResource`, which already passes `includeDisabled` and therefore *does* resolve a disabled zone.
Closing it means deciding whether the zones page's region selector should list disabled regions at all —
the question `L4` refused to answer inside this unit. The stores page has already answered it for itself
and is unaffected.

G2 (declared, out of scope by `S6`). `/companies/store-areas`, `/companies/store-zones` and
`/companies/store-locations` extend `StoreCascadePage`, which carries its own query-parameter
preselection and its own store resolution. They are not covered here; whether they should read
`LocationPreference` is the same question `G17` of `location-preference` asked for the profile screen
and is its own unit.
