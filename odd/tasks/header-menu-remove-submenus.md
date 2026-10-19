# ODD feature: header-menu-remove-submenus

**Repository**: LifeControl — frontend `life-control-app-angular/`
**Status**: implemented — the request's two submenus are gone and their destinations stay reachable from
the pages their labels navigate to. This header makes no claim about push or PR state; the delivery
detail belongs to the dated log below (`references/feature-records.md` invariant 2).
**Created**: 2026-10-18 · **Risk**: none — the diff deletes a template branch and an optional field in
`items()`; of the eight header entries, only the two the request names changed, and the other six were
**measured** identical against the base commit (T5). That is also why item 3 of the Task Size list does
not transfer: the deleted branch was taken by `Companies` and `Calendario y citas` only, and both are
in scope, so no entry the request did not mention changes behavior.
**Base**: `main` @ `a6207fd` · **Branch**: `feat/header-menu-remove-submenus` · **Worktree**:
`~/workspace/LifeControl-worktrees/feat-header-menu-remove-submenus`

## Requested by the user

> quiero quitar los submenos que aparecen de Companies submenu y Calendario y citas submenu

## Specs

- **S1** — the `Companies` header entry renders as a plain link: `<a href="/companies">`, **no**
  `.submenu-trigger` element, **no** `<mat-menu>` inside its `li.nav-item`, and **no** `children` in its
  menu item.
- **S2** — the `Calendario y citas` header entry renders the same way, with `href="/scheduling/list"`:
  no caret, no panel, no `children`.
- **S3** — no destination is stranded by the removal: `Store Zones` (`/companies/store-zones`) and
  `Store Locations` (`/companies/store-locations`) stay reachable from the cards of `/companies`
  (`companies-admin`), and `Calendario` (`/scheduling/calendar`) stays reachable from the `Calendario`
  button of the activities list (`onOpenCalendar`). The header offers no direct entry for them any more.
- **S4** — every other header entry (`Sales`, `Products`, `Stock por tienda`, `Compras`, `Users Admin`,
  `Recursos Humanos`) renders exactly as at the base commit: same set, same order, same label, same
  `href`, no caret, no panel; and every per-entry visibility gate is untouched.
- **S5** — no dead mechanism remains: `items()` has no `children` field, the template has no
  `item.children` branch, `header.scss` has no `.submenu-trigger` rules, and the user menu's
  `mat-menu` (which is why `MatMenuModule` stays imported) still renders.

## Decisions

- **D1 — the `children` mechanism is removed, not left unused.** With S1/S2 no item sets it, so its
  template branch, its `header.scss` rules and its type field were unreachable. This repeats the
  decision `hr-menu-entry-navigates-to-hr-landing` D1 made for `opensSubmenu`: dormant markup for a
  hypothetical future user is exactly what the next reader has to reason about while the model no
  longer offers it.
- **D2 — the destination ownership moves to the pages, and that is not a behavior change nobody asked
  for.** Both pages already owned every destination the carets opened: `/companies` lists Store Zones
  and Store Locations as cards, and the activities list carries the `Calendario` button. The removal
  takes away a second control per entry, not a destination — which is what makes S3 checkable instead
  of a hope.
- **D3 — `hr-menu-entry-navigates-to-hr-landing` S3 is reversed for these two entries, and that record
  is not rewritten.** Its S3 pinned that `Companies` and `Calendario y citas` "render exactly as before,
  down to the element order inside their `li`" — the rule existed to protect them from *that* change.
  This unit is the change that rule was protecting against, so the rule is superseded here and the
  earlier record keeps its frozen narrative as the evidence of the earlier decision
  (`references/feature-records.md` invariant 4).

## Out of scope

- The routes, pages and guards behind the removed submenus (`/companies/store-zones`,
  `/companies/store-locations`, `/scheduling/calendar`, `/scheduling/list`).
- The `Companies` and `Calendario y citas` labels keep navigating to the same destinations.
- Every other header entry's behavior, gate and element order (S4), including the user menu.
- The records that documented the submenus this unit removes (`hr-menu-entry-opens-submenu`,
  `store-zones-frontend`, `store-locations-frontend`, `scheduling-calendar`): their frozen narratives
  are evidence and are left as they are.

## Delivery

| Step | Detail |
| --- | --- |
| Base | `a6207fd` (`main`) |
| Work unit 1 | `refactor(header): remove the Companies and Calendario y citas submenus` — 5 files, 47 insertions / 151 deletions |
| Record | the closing commit of the branch, `docs(odd): record the header submenu removal` (this file) |
| Branch | `feat/header-menu-remove-submenus` — **198 authored changed lines in one work unit**, inside the repository's ~400-line review budget, so no chain is needed |
| PR | not recorded in this commit — the row is added with the delivery evidence, per `references/feature-records.md` invariant 3 |
| Merge | not recorded in this commit — same reason |

### Verification evidence

| Check | Command | Result |
| --- | --- | --- |
| RED (spec first, source untouched) | `npx ng test --no-watch --include=src/core/layout/header/header.spec.ts` | FAIL as expected — **10 failed / 63 passed (73)**, every failure an assertion of the removed submenu (S1, S2) |
| GREEN, focused spec | same command, after the source edit | PASS — **1 file / 73 tests / 0 failures** |
| Frontend lint | `npm run lint` | PASS — no output, exit 0 |
| Frontend build | `npm run build` | PASS — application bundle generated, no budget warnings |
| Full unit suite + coverage gate | `npm run test:coverage:check` | PASS — **182 files / 3641 tests / 0 failures**; statements 86.33% / branches 85.34% / functions 80.18% / lines 88.96%, all inside the thresholds and printed `OK` by `scripts/check-coverage.mjs` |
| Scheduling e2e | `npx playwright test e2e/specs/scheduling-calendar.spec.ts` | PASS — **2/2**, reaching the calendar the way an operator now does: header link → activities list → `Calendario` button |
| Parent spot check | focused spec re-run after the suite | PASS — **73/73** |
| Differential check (the Risk line) | base commit in a `mktemp -d` copy (`git archive HEAD`) + the same throwaway DOM probe spec in both trees, `lc-admin` | PASS — **8 entries per tree, 6 byte-identical**; only `Companies` and `Calendario y citas` differ, each by `caretTriggers 1 → 0` and `menuPanels 1 → 0`, with `href` and the label `<a>` unchanged (S4) |
| RDD plan | `gentle_review` `assess` over the working tree | `nativeRisk: medium`, **`reviewDue: false`** (`under_budget`; 5 paths / 202 changed lines), `writerProfile: large` (runtime) → no native review started. The plan's `independentVerifier: true` comes from the parent's `escalate` item 3, not from the native tier |

## Tasks

- **T1** `header.spec.ts`: S1/S2/S4 assertions — no `children` on either entry, no caret for any entry,
  the labels keep their `href` — written **before** the source edit so the RED run was observed.
- **T2** `header.ts` / `header.html` / `header.scss`: the `children` mechanism goes away (D1, S5).
- **T3** `e2e/specs/scheduling-calendar.spec.ts`: the calendar is reached through the activities list, so
  the spec's own path is the header link plus the list's button (S3).
- **T4** Gates: lint, build, focused spec, full suite with the coverage gate, and the e2e.
- **T5** Differential check of the six sibling entries against the base commit (S4, the Risk line).
- **T6** RDD reading (`assess`) and the verification tier that follows from the Risk line.
- **T7** Issue #441 from the feature template, with the acceptance criteria that make S1–S5 checkable
  by someone else.
- **T8** Work unit commit plus the record commit.
- **T9** Pull request against `main` with `Closes #441`, exactly one `type:*` label and the template's
  sections — waiting on the maintainer's `status:approved` on the issue.

## Log

- **L1** — 2026-10-18, the user's request (quoted above), verbatim.
- **L2** — 2026-10-18, reading the tree it was written against: of the eight entries `lc-admin` renders,
  only `Companies` (ids 2-1/2-2) and `Calendario y citas` (8-1/8-2) carried `children`; the two
  destinations the first caret opened are cards of `/companies`, and the second one's calendar is
  reached by the `onOpenCalendar` button of `/scheduling/list`. So the request takes away a control,
  not a destination — and it reverses S3 of `hr-menu-entry-navigates-to-hr-landing`, which had pinned
  these two entries against exactly this change (D3).
- **L3** — 2026-10-18, environment note: the local runs used Node **v26.12.0**, while
  `life-control-app-angular/.nvmrc` pins **24.15.0** and `.github/workflows/angular-ci.yml` reads that
  file (`node-version-file`). So the CI gate evaluates a different Node than the local evidence above.
- **L4** — 2026-10-18, **the first e2e run failed on the assertion, not on the change**: the new path
  arrives as `/scheduling/calendar?storeId=store-1`, because the activities list carries the store it
  resolved into the calendar's query params (`onOpenCalendar`), while the deleted submenu navigated
  bare. The URL already proved the click worked; the assertion was narrowed to the path and the query
  string was allowed. The run that failed said nothing about navigation and everything about the regex.
- **L5** — 2026-10-18, a chained `lint && build && test:coverage:check` job died with
  `ENOENT: coverage/.tmp/coverage-0.json`: two of my own `ng test` runs overlapped and both wrote the
  same `coverage/.tmp`. The re-run in series passed every gate. Environmental, and worth recording
  because the failure looks like a broken suite and is really a scheduling error.
- **L6** — 2026-10-18, **the first differential probe produced no evidence at all**: the Angular
  unit-test builder does not forward `console.log` from specs, so both trees wrote empty dumps and the
  `diff` of two empty files reads exactly like a clean check. The probe was switched to `node:fs`
  `writeFileSync` and re-run, and only then did the measurement say anything: 8 entries, 6 identical.
  This is the same failure mode `hr-menu-entry-navigates-to-hr-landing` recorded in its own L4, one
  repository and one year of convention later.
- **L7** — 2026-10-18, the independent verifier could not be admitted: the launcher refuses a change
  that declares no `Risk: item N`, and its own instruction forbids adding one to force admission. So
  this unit carries **writer self-verification plus the inline gates**, and no separate verification
  run. The refusal is recorded instead of being papered over with an invented Risk line.
- **L8** — 2026-10-18, the RDD reading on the final bytes: `nativeRisk: medium`, `reviewDue: false`
  (`under_budget`), `writerProfile: large` from the runtime-recorded writer. The plan's independent
  verifier comes from the parent's `escalate` item 3 — a judgement about the shared template branch,
  which T5 then measured away. No native review was started, and the coverage gate ran before it.
- **L9** — 2026-10-18, delivery: **issue #441** opened from the feature template (problem, expected
  outcome, acceptance criteria, non-goals), the change moved into the worktree with a verified
  identical patch checksum (`8c61036a…`, so the committed bytes are the measured bytes), the anchor
  left clean on `main` @ `a6207fd`, and the branch pushed. The pull request waits on the maintainer's
  `status:approved` on #441, which no author may apply to its own issue
  (`references/labels.md` invariant 4).
