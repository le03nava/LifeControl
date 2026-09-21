# ODD feature: store-p0-defects

**Repository**: LifeControl — frontend `life-control-app-angular/` only (no backend edits)
**Status**: delivered — PR #113 (`feat/store-p0-defects` @ `73113c3`), based on `main` @ `c0cb1f5`
**Created**: 2026-09-17
**Predecessor**: `odd/tasks/store-locations-frontend.md` (merged, PR #112)

## Objective

Fix four confirmed P0 defects in the store Area/Zone/Location frontend: the read-only role is
locked out of routes the API serves, disable/enable write failures are invisible on two pages,
every cascade level renders a backend outage as "no records", and save failures without an
`errors` map (409/404) are silent.

## Problem (confirmed evidence, not re-litigated)

1. `companies.routes.ts:8` `STORE_ROLES` omits `lc-company-store-read`, so a read-only user is
   redirected to `/unauthorized` even though the API grants every GET.
2. `store-areas-page.ts:352-373` and `stores-page.ts:295-315` subscribe to write calls with
   next-only handlers; failures vanish. `store-zones-page.ts` already handles this via
   `actionError` + `setActionError`.
3. Non-leaf cascade levels swallow load failures with `catchError(() => of([]))` on all three
   pages, so an outage looks like an empty selection.
4. `stores-edit.ts:222-227` only reads `apiError.errors`; the backend 409/404 envelope
   (`{status,message,path,timestamp,correlationId}`) has no `errors` map, so the save is silent.

## Why this shape

- **Behavior fixes only.** No new infrastructure, no shared cascade/leaf-form abstraction, no
  renames, no refactors. The shared a11y/cascade abstraction is a separate slice.
- **Mirror the working sibling.** `store-zones-page` already solved the write-error defect; the
  fix copies that exact shape (signal, private setter, `{next,error}` subscribe) instead of
  inventing a new one.
- **Errors are per level.** Removing `catchError` lets `rxResource` own the failure; the template
  renders which level failed while dependent selectors stay empty. The leaf list resource keeps
  its existing service-owned error handling untouched.
- **Read role gets read routes only.** `lc-company-store-read` joins the list-route role set, but
  `create`/`edit` children carry an explicit write-role guard so the read role never reaches a
  write-only surface.
- **One small role helper.** `core/security/roles.ts` mirrors `Roles.java` and the header's token
  path, used in exactly one place (the "Nueva Tienda" button) because
  `CompanyStoreService.createStore` throws `AccessDeniedException` for `lc-company-store`.

## Scope

### In scope

- `companies.routes.ts`: route role wiring.
- `store-areas-page`, `stores-page`: write-error surfacing + create-button gating.
- `store-areas-page`, `store-zones-page`, `store-locations-page`: cascade error surfacing.
- `stores-edit`: non-field save error surfacing.
- `core/security/roles.ts`: new role helper.
- Co-located specs for every fix.

### Out of scope (explicitly not implemented)

- Shared cascade/leaf abstraction, a11y unification, backend changes, renames, refactors.
- Hiding edit/toggle actions from read-only users (only the create button is gated here).
- A new companies routes spec (none exists; not created in this slice).

## Verification

| Check | Command | Result |
| --- | --- | --- |
| Scoped tests | `ng test --no-watch --include src/features/companies` | PASS — 44 files, 954 tests |
| Lint | `npm run lint` | PASS — all files pass linting |
| Build | `npm run build` | PASS — only pre-existing budget warnings |
| Task's `npx vitest run` | `npx vitest run --reporter=dot src/features/companies` | FAIL (environmental) — no vitest config/globals; 44 files fail `ReferenceError: describe is not defined`. Repo runner is `ng test`. |

## Task log

- [x] Route role wiring (`companies.routes.ts`).
- [x] Role helper (`core/security/roles.ts`).
- [x] `stores-page` action error + create-button gating (+ spec).
- [x] `store-areas-page` action error + cascade errors (+ spec).
- [x] `store-zones-page` cascade errors (+ spec).
- [x] `store-locations-page` cascade errors (+ spec).
- [x] `stores-edit` non-field error (+ spec).
- [x] Verification run.

## Status

Delivered as PR #113. The 17 files were split out of the shared dirty worktree and committed on
their own branch, because the working tree carried both this slice and the backend slice
`store-contract-hardening` at once. The split was verified two ways: every committed blob is
identical to the backed-up stash, and the union of both slices reproduces the original tracked
state exactly.

Verification re-run independently in an isolated `git worktree` of `73113c3`, reproducing
`angular-ci.yml`: `npm run lint` exit 0; `npm run build` exit 0 with 0 `NG####` warnings and only
the 4 pre-existing SCSS budget warnings; `npm run test:coverage:check` exit 0 with 100 files /
1721 tests passed and statements 91.43%, branches 74.61%, functions 87.54%, lines 91.43% — every
threshold met.

## Next step

Await CI (`angular-ci.yml`) and human review on PR #113. Open follow-ups, unchanged from
delivery: the route block still has no automated test (no spec in the repository references
`companyRoutes`, so `companies.routes.ts` stays verified structurally only), and the toggle/edit
actions remain visible to read-only users — only the create button is gated here.
