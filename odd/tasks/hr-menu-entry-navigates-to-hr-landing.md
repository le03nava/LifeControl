# ODD feature: hr-menu-entry-navigates-to-hr-landing

**Repository**: LifeControl — frontend `life-control-app-angular/`
**Status**: implemented — the entry navigates again and `/hr` renders a landing page with the three
HR destinations, which reverses **D1** of `hr-menu-entry-opens-submenu` (PR #358). Nothing remains
open in this unit. This header makes no claim about push, PR or merge state; see the delivery table.
**Created**: 2026-10-18 · **Risk**: none — the diff adds a page and a route, and removes a template
branch that had become unreachable. The one surface that could have gone unnoticed is the *sibling*
header entries, and it was measured rather than argued: a temporary DOM probe rendered the header for
`lc-admin` on this branch and on the base commit, and the seven entries that are not `Recursos Humanos`
are byte-identical in child order, `href`, caret, label trigger and `<mat-menu>` presence (T8). That
is also why item 3 does **not** transfer from `hr-menu-entry-opens-submenu`, which declared it for the
same surface: that unit *added* a branch and an element order, this one deletes an unreachable one.
**Base**: `main` @ `e671799` · **Branch**: `feat/hr-menu-landing-page` · **Worktree**:
`~/workspace/LifeControl-worktrees/feat-hr-menu-landing-page`

## Requested by the user

> quiero cambiar el menu de Recusos Humanos para que en lugar de tener la flecha sea como el de
> Compras que cuando le das click abre /purchases con los submenus en este caso seria con los d RH

## Specs

- **S1** — the `Recursos Humanos` header entry is a **link to `/hr`**: it renders as an `<a>` with
  `href="/hr"`, carries **no** caret (`.submenu-trigger`), **no** label trigger (`.nav-item-trigger`)
  and **no** submenu panel, and it is the only HR entry in the menu (no `9-1`/`9-2`/`9-3` children).
- **S2** — `/hr` renders a landing page with exactly three destinations, in this order:
  Departamentos (`/hr/departments`), Puestos (`/hr/positions`), Empleados (`/hr/employees`) — the
  same three the submenu listed, so the request's "los de RH" is preserved.
- **S3** — the entries that keep navigating (`Companies`, `Calendario y citas`) render exactly as
  before, down to the element order inside their `li`.
- **S4** — unchanged behavior that must hold: the entry's visibility stays the union of the four HR
  write sets (`hr-org-structure` D17 → `HR_WRITE_ROLES`), and every existing HR destination
  (`departments`, `positions`, `employees`, `seniority-levels` and their write children) keeps
  resolving to the same component with the same guards.

## Decisions

- **D1 — the `opensSubmenu` mechanism is removed, not left unused.** It existed solely for the HR
  entry (`hr-menu-entry-opens-submenu` T1/T2): with S1, no item sets it, so its template branch and
  its `header.scss` rules were unreachable. The alternative — keeping a dead branch for a future
  user — was rejected because the branch is exactly the kind of dormant markup the next reader has
  to reason about while the model no longer offers it. Consequence: `routeLink` is a required field
  of the item model again, since every entry navigates.
- **D2 — the landing page mirrors `/purchases`' card grid**, and it is role-gated on `HR_WRITE_ROLES`
  like the purchases dashboard is gated on its own roles: the page exists to be the destination of
  the entry, so it is visible exactly where the entry is. It is **stricter** than its children (the
  HR reads are `isAuthenticated()`), never looser.
- **D3 — no per-card role filtering.** The three cards are the three catalogs the submenu listed
  unconditionally; each target is a read that any authenticated caller may open (its write controls
  gate themselves on the narrower write set). Filtering per card would hide destinations the submenu
  offered, which is a behavior change nobody asked for.
- **D4 — the landing page is written in Spanish.** The HR surface it belongs to is Spanish
  (`Departamentos`, `Puestos`, `Empleados` page titles and nav labels); only the purchases/products
  dashboards, which the layout is borrowed from, are English. The layout follows `/purchases`, the
  language follows `/hr`.

## Out of scope

- The destinations themselves and their screens (`hr-org-structure`, `employee-registry`,
  `hr-positions-w3` own those): no route below `/hr/departments`, `/hr/positions` or `/hr/employees`
  changes.
- The HR entry's role gating (`hr-org-structure` D17).
- Any behavior of the other header entries beyond S3.
- The `hr-menu-entry-opens-submenu` record: it is **not rewritten**. Its D1 is superseded by this
  record's D1, and its frozen narrative stays as the evidence of the earlier decision.

## Delivery

| Step | Detail |
| --- | --- |
| Base | `e671799` (`main`) |
| Work unit 1 | `72331cc` — `feat(hr): add the /hr landing page with the three catalogs` (6 files, +319) |
| Work unit 2 | `4b40203` — `refactor(header): make the Recursos Humanos entry a link to /hr` (5 files, +87/−183) |
| Record | the closing commit of the branch, `docs(odd): record the HR entry's return to a navigating label` (this file) |
| Branch | `feat/hr-menu-landing-page` — **589 authored changed lines in two commits**; the slice
boundary is commit 1 | commit 2, which is where a chained delivery would cut (the repository's
~400-line review budget is passed by the branch as a whole, not by either commit) |

### Verification evidence

| Check | Command | Result |
| --- | --- | --- |
| Frontend lint | `npm run lint` | PASS — all files pass linting |
| Frontend build | `npm run build` | PASS — bundle generated in 9.4 s, no budget warnings |
| Focused specs (header + hr routes + landing) | `npx ng test --no-watch --include 'src/core/layout/header/header.spec.ts' --include 'src/features/hr/hr.routes.spec.ts' --include 'src/features/hr/pages/hr-admin/hr-admin.spec.ts'` | PASS — **3 files / 87 tests / 0 failures** |
| Full unit suite + coverage gate | `npm run test:coverage:check` | PASS — **182 files / 3642 tests / 0 failures**; statements 86.35% / branches 85.34% / functions 80.18% / lines 88.97% against 80/60/75/80 |
| Navigation e2e | `E2E_PORT=4296 npx playwright test e2e/specs/navigation.spec.ts --workers=1` | PASS — **9/9** in 22.5 s |
| Differential check | base tree (`e671799`) in `mktemp -d` + a temporary DOM probe spec, `ng test --include` on both trees | PASS — the full diff is **one line**, the HR entry itself; the seven other entries (Companies and Calendario y citas included) are byte-identical |
| RDD plan | `gentle_review` `assess` over `e671799..HEAD` (`committedOnly`) | `nativeRisk: medium`, **`reviewDue: false`** (`under_budget`; 12 paths / 736 lines as measured, a count this record's own size moves); plan `writerSelfVerification: true`, `independentVerifier: false` → no native review started and no separate verifier ran |

## Tasks

- **T1** `header.spec.ts`: S1/S3 assertions (link + `href="/hr"`, no caret, no label trigger, no
  panel, no `.nav-item-trigger` anywhere) and the S4 re-pointing of the gating tests.
- **T2** `hr-admin.spec.ts` (new): S2 — three cards, their order and their `routerLink` targets.
- **T3** `hr.routes.spec.ts` (**extended, not created** — the file already existed and pins the whole
  guard split): the `path: ''` landing child, its gate and its lazy component, plus the three catalog
  routes it links to.
- **T4** `header.ts` / `header.html` / `header.scss`: the entry becomes a link to `/hr`; the
  `opensSubmenu` mechanism goes away (D1).
- **T5** `hr-admin.ts` / `.html` / `.scss` (new) + `hr.routes.ts`: the `/hr` landing page (D2–D4).
- **T6** `e2e/specs/navigation.spec.ts`: the HR flows go through the link and the landing page.
- **T7** Gates: lint, build, focused specs, coverage check and the navigation e2e.
- **T8** Differential check of the sibling entries against the base commit (the Risk line).
- **T9** Commit of the work unit.

## Log

- **L1** — 2026-10-18, the user's request (quoted above), verbatim.
- **L2** — 2026-10-18, reading the tree it was written against: `Compras` is a plain `routeLink` to
  `/purchases`, whose page lists its two areas as cards; `Recursos Humanos` was `opensSubmenu` with
  the three children (`hr-menu-entry-opens-submenu`, merged as PR #358). The request therefore asks
  for the **inverse** of that record's D1, plus a new destination (`/hr`) that does not exist today —
  `/hr` had no route at all, so nothing that worked before is taken away.
- **L3** — 2026-10-18, environment note: the pinned toolchain is Node `24.15.0` (`.nvmrc`) and none
  of the shell managers in this machine had it; `~/.gentle-shell/runtime/node-24.21.0/bin` satisfies
  the Angular CLI floor and is what every command below ran with.
- **L4** — 2026-10-18, **the differential check was rerun after the first attempt produced no
  evidence at all**: `console.log` from a spec is swallowed by this project's Vitest configuration, so
  the probe's two runs each wrote an empty file and their `diff` was vacuously clean. Switching the
  probe to `process.stdout.write` produced the real measurement, and only then did the check say
  anything: eight entries per run, one differing line, the seven siblings identical. A clean diff of
  two empty files reads exactly like a passing check, which is the failure mode this note exists for.
- **L5** — 2026-10-18, **a lost file was caught by `git diff --stat`, not by a test**: T3 wrote
  `hr.routes.spec.ts` as a new file, and the path already held a 247-line spec that pins the guard
  split T22 establishes by walking the real `hrRoutes` tree (the departments, positions,
  seniority-levels and employees branches, their write roles and their lazy components). The write
  replaced it, and the first full-suite run reported **182 files / 3626 tests / 0 failures** against
  that reduced spec — green, because nothing asserted that those pins still existed. The file was
  restored from `HEAD` and the landing-page block was **appended** to it; the whole suite and the
  end-to-end run were then re-measured on the restored bytes. The lesson recorded here is the reason
  the check is in this log at all: a missing spec is not a failing test, so the diff stat is the only
  thing that reported it.
- **L6** — 2026-10-18, **the slice boundary was fixed before the third commit, not after**: the two
  work units land 589 authored changed lines together, past the repository's ~400-line review budget,
  so the boundary is recorded here as commit 1 | commit 2 — the landing page (a pure addition, and
  inert until the entry links to it) and the header change with its specs and the e2e rewrite — which
  is where a chained delivery would cut. Splitting the *commits* differently was rejected: the header
  change without `/hr` leaves the entry pointing at a route that does not resolve, and `/hr` with the
  old submenu would be a page no rendered control reaches, so each commit is coherent on its own and
  only the pair delivers the request.
- **L7** — 2026-10-18, the first `assess` could not run the native assessment at all
  (`nativeRisk: unassessable`): the record file was still untracked, and untracked files require an
  explicit declaration. Staging it and re-running produced the real reading — `medium`,
  `reviewDue: false` (`under_budget`) — which is what this record carries, instead of the earlier
  unassessable one, which would have been an unknown masquerading as a result. The first real reading
  covered the two code commits (11 paths / 589 lines) and the re-read on the final bytes repeats it
  with this record included (12 paths / 736 lines); the row above carries the final one.
