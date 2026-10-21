# ODD feature: empty-error-banner-store-leaf-edit

**Repository**: LifeControl — `life-control-app-angular/`, the three store leaf edit pages (`store-areas-edit`, `store-zones-edit`, `store-locations-edit`), the scheduling activity page (`scheduling-activity-edit`) and their specs.
**Status**: implemented — four templates guard the banner with `@if (generalError(); as <name>)` and each page's spec pins the absence of an empty one; the store leaf trio arrived first and the scheduling activity page joined the unit on the user's instruction (`L7`), and nothing is deferred.
**Created**: 2026-10-21 · **Risk**: none — the change is three template conditions plus three tests, no shared component, contract or stored data is touched (item 3 considered and rejected below, in `L3`).

## Specs

**S1** — The report, verbatim: _"puedes revisar en /companies/store-areas/create al entrar me sale un app-error-banner si texto"_ — opening `/companies/store-areas/create` shows an error banner with no text.

**S2** — The observed cause, measured rather than assumed: `src/shared/ui/error-banner/error-banner.html` renders its own chrome (the `.error-banner` box with `role="alert"`, a `mat-icon` and a `<span>`) unconditionally, and only the span text is bound to the `message` input. `error-banner.spec.ts` pins that on purpose (_"should render an empty message span when message is null"_), so the component is behaving as designed and the guard belongs at the call site.

**S3** — A page with no general error mounts no `app-error-banner`: on entry to a store leaf create page, `generalError()` is `null` and the page renders no banner element at all.

**S4** — The case that must keep working, unchanged: when a save fails, the banner still renders with its message, through the same `ErrorBanner` component and the same template line — the existing `should render the general error through the ErrorBanner` test of each page stays green without being touched.

**S5** — `ErrorBanner`, its spec and every other call site are out of scope: the shared component's contract does not change, so no page that already guards its banner is affected.

**S6** — The three affected pages are the reported `store-areas-edit` and the two siblings whose template carries the same unconditional line, `store-zones-edit` and `store-locations-edit`; leaving those two behind would ship the reported defect one click away.

**S7** — The scheduling activity page joins the unit, on the user's instruction verbatim (`L7`): _"corrige el error en scheduling-activity-edit.html"_. Its template carries the same unconditional line between two other guarded banners, so the same rule applies — no `app-error-banner` while `generalError()` is `null` — while the file's deliberate decoupling of the load and write banners stays exactly as it is, and the existing test that proves a save failure still shows its message keeps passing.

## Tasks

| ID     | Task                                                                                                                                                                             | Specs      | Route              | Commit                                                                                                                                        |
| ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- | ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------- |
| **T1** | RED first: one test per page in its create-mode block, `should not render the ErrorBanner while there is no general error`, observed failing against the unconditional template. | S3         | inline, test-first | `14ae00d`                                                                                                                                     |
| **T2** | The fix: `@if (generalError(); as errorMsg)` around the banner in the three templates, matching the shape `stores-edit.html` and `company-edit.html` already use.                | S2, S3, S6 | inline             | `14ae00d`                                                                                                                                     |
| **T3** | The gate: the three specs, then the whole `life-control-app-angular` suite, plus `prettier --check` and `eslint` on the touched files.                                           | S4, S5     | inline             | `14ae00d` — focused run **153/153 passed**, full suite **exit 0**, prettier and eslint clean                                                  |
| **T4** | The issue and its approval, then the record and the pull request.                                                                                                                | S1         | inline             | the commit that carries this record                                                                                                           |
| **T5** | The scheduling activity page, on the user's instruction: RED test for the absence on a fresh create page, then the same `@if` guard.                                             | S7         | inline, test-first | `83698b9` — RED observed `1 failed, 19 passed` of 20 on the empty element, GREEN `20 passed (20)`, full suite `184 files / 3682 tests passed` |

## Log

**L1** — _2026-10-21, the user's report, verbatim_: _"puedes revisar en /companies/store-areas/create al entrar me sale un app-error-banner si texto"_.

**L2** — _2026-10-21, the user's instruction for this delivery, verbatim_: _"ok genera issue, apruebalo y has el PR"_ — issue [#511](https://github.com/le03nava/LifeControl/issues/511), approved at the user's explicit instruction.

**L3** — _2026-10-21, why this is not Risk item 3._ The change looks like it touches a shared contract, and it does not: `ErrorBanner` keeps its input, its markup and its spec, so no consumer sees a different component. What changes is three call sites' decision to mount it, and the convention for that decision already exists in the same feature (`stores-edit.html`, `company-edit.html`, and every list page wrap the banner in `@if`). The three pages are leaf edit pages of one component family, and the tests are per-page so a regression names the page.

**L4** — _2026-10-21, one sibling left behind at that moment, and why (superseded by `L7`: the user brought it into this unit)._ `src/features/scheduling/pages/scheduling-activity-edit/scheduling-activity-edit.html` also contains a bare `<app-error-banner [message]="generalError()" />` between two guarded banners, so it shows the same empty alert on entry. It was not touched in the first commit: it belongs to the scheduling feature, its banner conditions are deliberately decoupled (the template's own comment explains that coupling them would let a stale load failure swallow a later write failure), and changing that page needs its own read of those conditions.

**L5** — _2026-10-21, the RED that proves the cause and not the symptom._ Before the fix, the three new tests failed with the empty banner in the assertion diff:

```
 FAIL  .../store-zones-edit.spec.ts > StoreZonesEdit > create mode > should not render the ErrorBanner while there is no general error
AssertionError: expected <app-error-banner> to be null
+ Received:
<app-error-banner>
  <div class="error-banner" role="alert">
    <mat-icon fonticon="error" role="img" />
    <span>
    </span>
  </div>
</app-error-banner>
 Test Files  3 failed (3)
      Tests  3 failed | 150 passed (153)
```

After T2 the same command reports `Test Files 3 passed (3)` / `Tests 153 passed (153)`, and the full `npx ng test --no-watch` run exits 0.

**L6** — _2026-10-21, the placement of this unit._ The work started in the anchor, which is never an edit target; it was moved to the worktree `~/workspace/LifeControl-worktrees/fix-store-leaf-edit-empty-error-banner` on branch `fix/store-leaf-edit-empty-error-banner` from `origin/main` @ `6cc02dc` before any commit, and the anchor was returned to a clean tree with the two diffs verified identical first.
**L7** — _2026-10-21, the user's instruction, verbatim_: _"corrige el error en scheduling-activity-edit.html"_ — so the fourth page is not separate work after all, and `L4`'s deferral is closed here rather than in another unit. What the read of that page established: only the bare general-error line is unconditional; `loadError()`, `storePending()`, `storeError()` and `storeUnconfigured()` already own their own `@if`, and the decoupling the template's comment defends is preserved untouched. The new guard is `@if (generalError(); as saveFailure)`, named for what it carries there — a failed write — and the spec's new test asserts the absence of any banner on a fresh create page.
**L8** — _2026-10-21, the delivery shape the user chose, verbatim_: _"Sumarlo al PR #512 que ya está abierto"_ — so the fourth page ships as its own commit on `fix/store-leaf-edit-empty-error-banner` and the one pull request covers the whole class, instead of a second unit with its own issue. Its worktree `fix-scheduling-activity-edit-empty-error-banner` was removed after the two diffs were verified identical, so the unit lives in one branch.
