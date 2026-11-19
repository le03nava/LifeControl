# Company list: the search reset effect undoes every page change

Issue **#944**. Branch `fix/companies-paginator-page-reset`.

**Status**: implemented — the reset effect declares the debounced search as its only dependency and reads
`pageIndex` through `untracked()`, a spec moves the page and re-runs change detection to prove the chosen
page survives, and the search reset keeps its own spec so the fix cannot be "delete the reset". Nothing is
deferred.
**Created**: 2026-11-19 · **Risk**: none — one screen's effect, no shared code (no option, parser or helper
that an unrequested behavior also uses), and both behaviors the effect has are pinned by a spec, so a
regression cannot pass quietly.

## Specs

Quoted verbatim from issue #944.

**S1** — "A spec that moves the page, runs change detection, and asserts `pageIndex()` is still the page
that was chosen — the assertion that fails today and is the point of the fix."

**S2** — "A spec that a **search** term still resets the page to 0 (the behaviour the effect exists for), so
the fix cannot be 'delete the reset'."

**S3** — "`company-list.spec.ts`'s existing cases stay green, including `should load companies with correct
params` unchanged."

**S4** — "`npx ng test --no-watch`, `npx ng lint` and `npm run test:coverage:check` pass."

## Decisions

**D1 — `untracked()` around the read and the write, not a second effect and not a "skip the first run"
flag.** The defect is not the reset itself, it is the dependency: reading `pageIndex()` reactively makes the
effect a consumer of the signal it writes, so `onPageChange` raising it to 1 schedules the effect, which
sees `1 !== 0` and writes 0 in the same change detection. `untracked()` keeps the intent that the comment
above the effect already declared — *when the debounced search changes, go back to page 0* — and leaves the
page change as the paginator's business. A "first run" guard would silence the defect instead of removing
it: the effect would still re-run on every page change, only nothing would happen on that path.

**D2 — copy `customer-list`, do not invent a third shape.** `customer-list.ts:100-110` was built one day
after this screen, already reads `pageIndex()` inside `untracked()`, and its spec asserts that a page change
survives change detection. The issue names it as the pattern to copy, and the two screens now express the
same rule the same way.

## Tasks

| ID | Specs | Task | Evidence |
| --- | --- | --- | --- |
| T1 | S1, S2 | Two specs in `company-list.spec.ts`: the chosen page survives change detection, and a debounced search still resets to 0 after a page change | RED before the fix, `npx ng test --no-watch --include=…company-list.spec.ts` → `1 failed \| 16 passed`, `AssertionError: expected 0 to be 1` at the new `expect(component.pageIndex()).toBe(1)` |
| T2 | S1, S2, S3 | Read `pageIndex` through `untracked()` in the reset effect and comment why the read is untracked | GREEN: same focused command → `1 passed` (17 cases), exit 0 |
| T3 | S4 | Full gates in `life-control-app-angular/` | `npx ng lint` → `All files pass linting`, exit 0 · `npx ng test --no-watch` → `215 passed (215)`, exit 0 · `npm run test:coverage:check` → statements 87.27%, branches 86.61%, functions 81.42%, lines 90.13%, `Cobertura dentro de los umbrales. OK`, exit 0 |

## Log

**L1** — the human's request, verbatim: *"puedes revisar con que issue continuar"*, answered with a ranking
of the six open issues; then, verbatim: *"ok dale a 944"*, choosing the fix of this record.

**L2** — 2026-11-19, the defect reproduced as a measurement before any source edit. With the new spec in
place and `company-list.ts` untouched, `onPageChange({ pageIndex: 1, pageSize: 12 })` followed by one
`fixture.detectChanges()` left `pageIndex()` at **0**, which is the operator-visible fact in the issue: the
request for page 1 is issued and the paginator reads page 0. The 16 other cases of the file passed, which is
the same blind spot the issue describes — the existing case asserts the request, never the state after it.

**L3** — 2026-11-19, exit codes read without a pipe. The first runs of this unit reported `0` from
`… | tail -12`, including the run that had a failing spec, because the pipeline's status was `tail`'s. Every
gate quoted above comes from a run whose own `$?` was captured, and `tail` was never in the pipeline that
decided the verdict.

**L4** — 2026-11-19, delivered as pull request **#981**, labelled `type:bug`, with issue #944 carrying
`status:approved`. The commit that holds this record is derived, never stored: `git log -1 --format='%h'
-- odd/tasks/company-list-page-reset.md`, the rule this repository states for delivery evidence, because a
commit id written here would name a branch object that the merge leaves behind. The pull request's checks
all passed before the merge: `A linked, approved issue`, `Exactly one type:* label`, `Lint & Build`,
`Unit tests (1)`, `Unit tests (2)` and `Coverage`. The screen was not exercised in a browser; the proof of
this unit is the spec that fails without the fix (L2).
