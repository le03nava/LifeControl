# ODD feature: hr-menu-entry-opens-submenu

**Repository**: LifeControl — frontend `life-control-app-angular/`
**Status**: merged — PR **#358** (`feat/hr-menu-entry-opens-submenu` @ `6d2f357`), 2026-10-17. No
work left in this unit; **G1** and **G2** stay declared. Verification closed with
`writerSelfVerification: true`, `independentVerifier: false`, `reviewDue: false` (L7).
**Created**: 2026-10-16 · **Risk**: item 3 — small diff, shared surface. It edits the markup and the
item model that **every** header entry renders (`items()` + `li.nav-item`), so the failure mode with
the best chance of going unnoticed is an unrequested change in a sibling entry (Companies,
Calendario y citas), not in the HR entry the request is about.
**Base**: `main` @ `8cde31a` · **Branch**: `feat/hr-menu-entry-opens-submenu` · **Worktree**:
`~/workspace/LifeControl-worktrees/feat-hr-menu-entry-opens-submenu`
**Origin**: **recovered work unit**. The session that wrote it (2026-10-16, 22:59–23:12) left four
modified files and a temporary probe spec **uncommitted** in that worktree, with no record and no
Engram observation, and the user deferred it ("lo dejamos para otra sesion"). The work was
reconstructed on 2026-10-16 from `git status`, the diff and the specs.

## Requested by the user

> en el menu quiero modificar el menu de Recursos Humanos para que en lugar de redireccionar a
> /hr/departments abra otro submenu con las opciones de Departamentos, Puestos y Empleados

and, in the same session, the locked answer to the design fork that request leaves open:

> continua, label abre submenu

## Objective

The `Recursos Humanos` entry rendered its label as a link to `/hr/departments` **and** carried the
three children that already duplicate that destination — `9-1` Departamentos (`/hr/departments`),
`9-2` Puestos and `9-3` Empleados — so clicking its label jumped into one of its own options. The
entry is now **menu-only**: the label is the submenu trigger, it carries no destination of its own,
and the three options are reached from the panel.

## Specs

- **S1** — the `Recursos Humanos` label opens a submenu with exactly three options, in this order:
  Departamentos, Puestos, Empleados.
- **S2** — the label carries no destination: clicking it opens the panel and does not navigate.
  `Departamentos` (`9-1`) keeps `/hr/departments`.
- **S3** — the entries that keep navigating (`Companies`, `Calendario y citas`) render exactly as
  before, down to the element order inside their `li`.
- **S4** — unchanged behavior that must hold: the entry's visibility is the union of the four HR
  write sets (`hr-org-structure` D17) and the three children keep their routes.

## Decisions

- **D1 — the label opens the submenu and does not navigate.** Locked by the user on 2026-10-16
  ("continua, label abre submenu"). The alternative, which is what `main` shipped, was the
  navigating label plus a separate caret trigger. The rejected middle ground was keeping both (a
  label that navigates *and* a panel that lists the same destination): `9-1` already carries
  `/hr/departments`, so the parent's own destination could only ever duplicate one of its children.
- **D2 — the `<mat-menu>` placeholder is declared *after* the label.** Derived from the differential
  check (L4), not from preference: `.nav-item` is a flex container with `gap`, and the `<mat-menu>`
  element is one of its items, so declaring it first moved it ahead of the sibling entries' label
  and changed their layout order. Declaring it last keeps every non-HR entry's elements in the order
  `main` renders them.

## Out of scope

- The children's destinations and their screens: `hr-org-structure` W2a/W2b, `employee-registry` and
  `hr-positions-w3` own those.
- The HR entry's role gating (D17 of `hr-org-structure`) — nothing here changes which roles see it.
- Any behavior of the other header entries, in both the desktop menu and the mobile drawer.

## Delivery

| Step | Detail |
| --- | --- |
| Base | `8cde31a` (`main`) |
| Work unit | `0966dae` — `feat(hr): open the Recursos Humanos submenu from its own label` (5 files, +171/−41) |
| Record | `d892aa1` — `docs(odd): record the HR menu-only entry as a recovered work unit` (`odd/tasks/hr-menu-entry-opens-submenu.md`, 148 lines) |
| Branch | `feat/hr-menu-entry-opens-submenu` |
| PR | **#358** → `main` (6 files, +319 −41); `Lint, Build & Test` pass in 6m41s |
| Merge | merge commit **`6d2f357`**, 2026-10-17T14:57:57Z; parents `ce2c15b` + `d892aa1` (identity in L8) |

### Verification evidence

| Check | Command | Result |
| --- | --- | --- |
| Frontend lint | `npm run lint` | PASS — all files pass linting |
| Frontend build | `npm run build` | PASS — bundle generated in 13.9 s, no budget warnings |
| Header spec | `npx ng test --no-watch --include 'src/core/layout/header/header.spec.ts'` | PASS — **73/73**, re-run on the committed bytes (`0966dae`) |
| Full unit suite + coverage gate | `npm run test:coverage:check` | PASS — **179 files / 3626 tests / 0 failures**; statements 95.12% / branches 77.58% / functions 90.65% / lines 95.12% |
| Navigation e2e | `E2E_PORT=4292 npx playwright test e2e/specs/navigation.spec.ts --workers=1` | PASS — **9/9**, after the `prettier --write` rewrap |
| Differential check | `git archive 8cde31a` into a scratch tree + a temporary probe spec, `ng test --include` on both trees | PASS — every non-HR entry renders the same elements in the same order (D2) |
| RDD plan | `gentle_review assess` over `main..HEAD` (`committedOnly`) | risk `medium`; `reviewDue: false` (`under_budget`); plan `writerSelfVerification: true`, `independentVerifier: false` → no native review started and no separate verifier ran |

## Task log

- [x] **T1** `header.ts`: `routeLink` becomes optional and `opensSubmenu` is added to the menu model;
      entry `9` drops its `routeLink` and sets `opensSubmenu`.
- [x] **T2** `header.html`: the menu-only branch renders the label as `.nav-item-trigger` (no
      `routerLink`, no separate caret); `<mat-menu>` is declared after it (D2).
- [x] **T3** `header.scss`: `.nav-item-trigger` mirrors the `large` hyperlink row (padding, height,
      typography, hover) and puts the caret at the inline end.
- [x] **T4** `header.spec.ts`: the panel's three options and their order (S1), no `a` inside the entry
      (S2), the sibling entries keep their caret and `Companies` keeps `href="/companies"` (S3), and
      the gating test asserts the entry by id instead of by the parent's `routeLink` (S4).
- [x] **T5** `e2e/specs/navigation.spec.ts`: the three call sites on the entry use the button; the E53
      test also asserts that opening the panel does not navigate.
- [x] **T6** Differential check against `8cde31a`, which found **D2**, plus its fix and re-measurement.
- [x] **T7** Gates: lint, build, coverage check and the header spec.
- [x] **T8** Navigation e2e.
- [x] **T9** Commit `0966dae`.

## Log

- **L1** — 2026-10-16, the user's request (quoted above) and the plain reading of the tree it was
  written against: the entry already carried the three children, so what the request really asks for
  is to take the **navigation** away from the label, not to add children.
- **L2** — 2026-10-16, same session: the agent created the branch and worktree (23:06), left four
  modified files and a temporary probe spec (`__diff-check.spec.ts`), and wrote no record, no
  observation and no commit. The user asked for the summary of what remained and deferred the work.
- **L3** — 2026-10-16, next session: the state was reconstructed from the worktree rather than from
  memory (there was nothing to recall); the design fork was put to the user, who locked **D1**
  ("continua, label abre submenu").
- **L4** — the differential check **found a real defect in the recovered implementation**: it
  declared `<mat-menu>` before the label, moving that placeholder ahead of the sibling entries'
  element order in a flex container with `gap`. Fixed by declaring it after the label (D2) and
  re-measured: the six non-HR entries are element-for-element identical to the `8cde31a` base tree.
- **L5** — evidence table above. The navigation e2e was run twice: once on the recovered bytes and
  again after `prettier --write` rewrapped the D16 block (the longer `getByRole('button', …)` line
  exceeded the print width), so the recorded evidence belongs to the committed bytes.
- **L6** — `0966dae` committed; the pre-commit hook (husky + lint-staged: `prettier --write`,
  `eslint --fix`) ran over the staged files and staged no further change, and the header spec was
  re-run on the committed tree with the working tree clean.
- **L7** — the RDD switch reads `on (decided by default)`, so the candidate was assessed before being
  reported: `main..HEAD` (6 paths, 347 authored lines) → `medium`, `reviewDue: false`
  (`under_budget`), writer profile `large` from runtime evidence. The returned plan is
  `writerSelfVerification: true` / `independentVerifier: false`, whose reason is explicit: no Task
  Size high-risk item was escalated, so the writer's own verification stands and no separate
  verifier runs whatever the native tier. The risk line this record declares (item 3) was therefore
  not escalated, and **no native review was started** — `reviewDue` false is the entry rule's own
  condition for starting one.
- **L8** — merged on 2026-10-17 as PR **#358**: merge commit **`6d2f357`**, `mergedAt
  2026-10-17T14:57:57Z`, parents `ce2c15b` (the `main` tip captured with `git ls-remote` **before**
  merging) + `d892aa1` (the head CI ran on, pinned with `gh pr merge --match-head-commit`). **The
  proof that the merge added exactly this branch's diff** is `git diff --stat ce2c15b 6d2f357` →
  6 files, **+319 −41**. The merge tree is **not** tree-identical to the head, and that is expected
  and not a defect: `main` had moved (`PR #357`, `ce2c15b`) while this branch was open, so the
  tree-identity check that holds on an unmoved base does not apply here. Post-merge CI on `main`
  runs all four workflows, because the `push` trigger has no path filter. **Two repairs this row
  carries**: (1) the PR body quoted **+306 −41**, a measurement taken before this record's own
  amendment added 13 lines, corrected to **+319 −41** with `gh pr edit`; and (2) this record's
  header carried *“what remains is the delivery decision (push, PR, merge)”*, a pending-delivery
  claim the merge falsified — the shape invariant #2 forbids and the reason the repair is recorded
  rather than silently applied — so the header now states the merge with its evidence.

## Gaps

- **G1** — the mobile drawer renders the same `<ul>` and the e2e runs at the desktop viewport only.
  The trigger mechanism is the one the caret already used, so the exposure is low, but no test pins
  the drawer.
- **G2** — the panel's contents are asserted at the DOM level in the spec and by role in the e2e;
  the end-to-end assertion that the label does **not** navigate lives only in the E53 e2e test.

## Next step

None for this unit — merged (L8). **G1** and **G2** are declared follow-ups, not open work here.
