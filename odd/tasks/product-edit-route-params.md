# ODD feature: product-edit-route-params

**Repository**: LifeControl — `life-control-app-angular/` only: the product workspace
(`pages/product-edit/`) and the spec that pins it. No `life-control-api/` change, no migration, no
route table change, no docker file.
**Status**: implemented — the workspace reads the product id and the company from the route
reactively and reloads when either names another product, while an in-place `?tab=`/`?storeId=`
change keeps not reloading it. Nothing is deferred.
**Created**: 2026-10-28 · **Issue**: **#670** (`fix: reload the product workspace when the route names
another product`, `bug` + `status:approved`) · **Branch**: `fix/product-edit-route-params` ·
**Risk**: medium, the tier the native assess returned (`baseRef 38bb9fe0`, 3 paths, 271 changed lines)
and not the highest: no Task Size high-risk item applies — one component's read of its own route, no
contract, no schema, no shared helper, no stored data — so the tier adds no separate verifier and the
writer's own checks stand.

## Specs

**S1** — *the recorded defect, verbatim* (`odd/tasks/products-company-filter.md:66`, L5): «`ProductEdit`
reads `productId` and the company through `snapshot`, so navigating from
`/products/edit/A?companyId=X` to `/products/edit/B?companyId=Y` reuses the component instance and
leaves the old values in place.» The route naming another product or another company must load that
product under that company: one `getProductById` call carrying the new id and the new company, and the
header plus the form showing what that call returned.

**S2** — *the behavior that must keep working, and the reason it is a spec*: an in-place `?tab=` or
`?storeId=` change is not a product change. `getProductById` is called **once** for one product, no
matter how many tabs the operator walks.

**S3** — *the source rule the fix must not break*: the company is read from the URL, never from a
response body (`products-company.ts`'s own contract, record `products-company-filter` S3). Following
the route means following the two values the route names — the management of the company picker on
create stays exactly as it is.

## Tasks

| ID | Specs | What | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1, S2 | RED: the spec changes the route in place and pins the second load; the tab-switch case pins the single load | inline (parent) | `55ab3587` |
| T2 | S1, S3 | GREEN: the product id and the company are followed across navigations, and either one changing reloads the product | inline (parent) | `55ab3587` |
| T3 | S1–S3 | This record and its evidence | inline (parent) | this commit |

## Log

**L1** — the request, verbatim (2026-10-28): «si abre el issue y apruebalo y ejecuta el cambio».

**L2** — the requirement's source is the finding the previous unit's review recorded and deliberately
left out of scope, quoted in S1. Nothing new is decided here: the unit is that finding, and its scope
is the one screen the finding names.

**L3** — the measured asymmetry, from the file itself: `product-edit.ts:301` reads the query params
with `toSignal(this.route.queryParamMap, …)` *because* an in-place `replaceUrl` write must be observed,
while `product-edit.ts:85` seeds `productId` from `this.route.snapshot.paramMap.get('id')` and reads it
once in `ngOnInit` (`product-edit.ts:323`). Two halves of the same route, two different treatments —
the snapshot half is the defect.

**L4** — why the fix is not an `rxResource` rewrite: the load also writes `productForm`, and the form
is what `savedCategoryPath` and the attribute-profile resource read. Turning the load into a resource
would move the form's construction into a `stream`, which is a different unit with its own F1
regression risk (a form built before the tree arrives wipes a saved classification). The reload keeps
the imperative load and only changes what triggers it.

**L5** — scope boundary, stated so it cannot drift: nothing outside `pages/product-edit/` changes.
`products-company.ts` keeps its snapshot seed — it is documented as a *seed* for screens that only seed
— and the sibling editors that read an id from a snapshot are not reachable in place from this
screen's own navigation, so widening the unit to them would be an unrequested refactor.

**L6** — the mechanism, and why it is `NavigationEnd` and not the route's own `paramMap`. Three
candidates were weighed: (a) `combineLatest([route.paramMap, route.queryParamMap])` — two streams that
emit one after the other, so a navigation that changes both spends a request on the new id under the
**old** company before the second emission corrects it; (b) the same with `debounceTime(0)`, which
buys coalescing with a timer every spec then has to advance; (c) `NavigationEnd` plus a read of the
settled route, which is one event per **completed** navigation, both values from the same state, and a
navigation the unsaved-changes guard refuses reloads nothing because it never ends. (c) is the one
implemented. `distinctUntilChanged` compares the pair, so `?tab=` and `?storeId=` writes — the same
product under the same company — reload nothing (S2). `startWith(null)` keeps the first load in the
same tick it occupied when `ngOnInit` read the snapshot directly, which is what keeps every existing
spec's timing. The in-flight read is cancelled on each reload, so an answer for the previous product
cannot land after the new one.

**L7** — RED, measured before the component changed:

```
cd life-control-app-angular
npx ng test --no-watch --include src/features/products/pages/product-edit/product-edit.spec.ts

FAIL  … > the route names another product (S1) > should load the product the new id and company address, and render it
AssertionError: expected "vi.fn()" to be called 1 times, but got 0 times
 Test Files  1 failed (1)
      Tests  1 failed | 73 passed (74)
```

The zero is the defect itself: the second navigation reached no handler at all. The sibling case of the
same describe (*should not reload the product when only the tab the route names changes*) already passed
before the fix and is the pin S2 asks for.

**L8** — GREEN, measured on the fixed tree:

```
npx ng test --no-watch --coverage=false --include src/features/products/pages/product-edit/product-edit.spec.ts
 Test Files  1 passed (1)
      Tests  74 passed (74)

npm run lint
All files pass linting.

npx ng test --no-watch --coverage=false
 Test Files  192 passed (192)
      Tests  3989 passed (3989)
```

The full run is the whole Angular suite, not a filtered slice: the change sits in a shared-in-app
component whose form, category and attribute-profile behaviour all run through the same file.

**L9** — what this record does **not** cover, so nobody reads the green as more than it is: the browser.
No Playwright spec walks `edit/A` → `edit/B` in one session, so the in-place navigation is pinned at the
component level (the router's own reuse behaviour is Angular's, not this unit's), and the manual denial
path — a refused navigation, which by design reloads nothing — is asserted through the mechanism (it
never emits `NavigationEnd`) and not through a spec that drives a real guard.
