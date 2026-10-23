# ODD feature: error-banner-scroll-into-view

**Repository**: LifeControl — `life-control-app-angular` only: the shared `ErrorBanner`, five edit page
templates and their specs, and the jsdom stub in `src/test-setup.ts`. No API code, no DTO, no wire
contract, no stored data.
**Status**: implemented — `ErrorBanner` can scroll itself into view behind an input that is off by
default, the five edit surfaces whose save action sits at the bottom of a long form opt in, and the stub
that lets a spec observe the scroll is in the test setup. Nothing is deferred.
**Created**: 2026-10-23 · **Base**: `main` @ `99b0912` · **Branch**: `fix/error-banner-scroll-into-view` ·
**Worktree**: `~/workspace/LifeControl-worktrees/fix-error-banner-scroll-into-view`
**Risk**: **none**. Adding an optional input whose default is `false` is the carve-out the risk list names
explicitly, and the default-off case is pinned by a test, so the other thirty-odd consumers of the banner
keep today's behaviour observably. No stored data, secret, permission, concurrency, pipeline or dependency
moves, and no wire contract changes.

## Specs

**S1** — The user's request, verbatim:

```
si abrelo como unidad aparte
```

It answers the closing proposal of the previous unit, also verbatim: *"hacer alcanzable el banner de
`stores-edit` — `scrollIntoView` sobre el `ErrorBanner` cuando aparece, o moverlo junto a las acciones. Es
una unidad chica y aislada (~una página, un test)"*.

**S2** — The measured geometry, which is what makes the banner missable. `stores-edit.html:2-4` renders the
banner above a `mat-tab-group` and the save control lives at `stores-form.html:108-116`, the bottom of a
119-line template with no sticky action bar. The three leaf pages render it at `:9`, right after the page
header, above a 66-line form whose `.form-actions` sits at `:53-59`. `scheduling-activity-edit.html:22`
renders it above `scheduling-activity-form.html:71`, the actions row of an 89-line form. Two other measured
facts bound the remedy: the banner is `role="alert"`, so assistive tech already announces it wherever it is
on screen, and the toast that reports the same failure lasts **8000ms** and carries no action
(`notification.ts:35`). On `stores-edit` a `412` changes nothing visible at all — that page deliberately
does not reload (`stores-edit.ts:337-346`) — so the toast was the only voice that reached the eye there.

**S3** — The three shapes considered, and why two were dropped. **(a) Move the banner next to the actions**:
for the three leaf pages the actions live inside the shared form component, so the page-level banner would
have to move into three form components, duplicating placement and moving ownership of a message away from
the page that sets it. **(b) Silence the toast instead**: already rejected in `http-412-conflict-copy` with
its own evidence — `SKIP_ERROR_NOTIFICATION` is per **request**, not per status, so it would silence `403`,
`409` and `5xx` too, and would leave `stores-edit` with no visible feedback at all. **(c) Scroll the banner
into view, opt-in**: shipped.

**S4** — The scope is five surfaces, not the 31 templates that render the banner. The rule is *"a page whose
save action sits at the bottom of a long form opts its error banner into scrolling itself into view"*, and
it is applied to every surface that measures it. The banner's other consumers are dialogs and lists where it
is already on screen; scrolling there would be noise, which is why the input defaults to off. Measured, not
assumed: the unit was proposed as "~una página, un test" for `stores-edit`; the three leaf pages then
measured the same geometry and `scheduling-activity-edit` measured the same again (89-line form, actions at
`:71`).

**S5** — The mechanism. `autoScroll` is an `input(false)`; an `effect()` created in the constructor scrolls
the host with `scrollIntoView({ block: 'nearest' })` when the input is true and `message()` is non-null.
`nearest` is the minimal movement that makes the element visible: it does nothing when the banner is already
on screen and never pushes the form out of view to centre a one-line message. The method is called
optionally (`scrollIntoView?.`) so a DOM without it cannot turn an error banner into a second error.

**S6** — The test-setup stub. jsdom does not implement `Element.scrollIntoView`; `src/test-setup.ts` now
defines a no-op when it is missing, for the same reason it already stubs `window.matchMedia`. Without it the
component's optional call would be the only guard and a spec would have nothing to spy on, so the assertion
would silently pass as "never called".

**S7** — What must keep working, and what covers it: the banner's pre-existing spec cases (creation,
`role="alert"`, the icon, message rendering, the reactive update, the empty span), the untouched templates of
its thirty-odd other consumers, the five pages' existing error assertions, and every existing `412`/`409`
path. The explicit guard is the new case `should not scroll by default when a message appears`: it fails if
anyone ever makes the scroll unconditional.

**S8** — What this unit deliberately does **not** do: it does not suppress the toast (that decision is
recorded as taken in `http-412-conflict-copy`, which measured it); it does not move any banner; it does not
change the copy of any message; it does not touch the API.

## Tasks

| ID | Spec | Task | Commit |
| --- | --- | --- | --- |
| T1 | S2 | Re-measure the geometry of the five edit surfaces and of the banner's other consumers | — (read-only) |
| T2 | S1 | Worktree and branch `fix/error-banner-scroll-into-view` off `main` @ `99b0912` | — |
| T3 | S5, S7 | RED: three component cases and five page cases | (lands with T4) |
| T4 | S4, S5, S6 | GREEN: the `autoScroll` input, the scroll effect, the jsdom stub and the five opt-ins | `fix(shared)` |
| T5 | S7 | `npx ng test --no-watch --coverage=false`, `npm run lint`, `npm run build` | — (checks, not a commit) |
| T6 | — | This record, with the evidence it cites | `docs(odd)` |

## Log

**L1** *(the user's request, verbatim — `S1`)*: «si abrelo como unidad aparte».

**L2** *(the measurement — `S2`)*: the geometry was measured on all five surfaces before any edit, and the
two other remedies were dropped for the reasons in `S3`, not for effort.

**L3** *(the widening to the fifth surface, measured — `S4`)*: the unit was proposed for one page. Measuring
the three leaf pages showed the same geometry, and `scheduling-activity-edit` measured the same as well. The
rule is applied to all five; the exclusion is stated by rule (dialogs and lists where the banner is already
visible), never by file count. Applying it to four of five identical surfaces is the inconsistency this
record would otherwise have had to defend.

**L4** *(the delegation, recorded because it shaped the evidence)*: the parent hit its 150k-token context
backstop mid-unit and handed the bounded implementation to `gentle-ai-worker` — the two missing RED cases,
the GREEN, and the gates, with no commits. The parent then applied the fifth surface itself, observed its own
RED for that surface, re-ran the final suite, reviewed the whole diff and wrote this record. One deviation
from the briefing is recorded rather than smoothed over: the worker's RED was **6** failures where the
briefing predicted 5, and the extra one is not a defect — the second component case also touches the
then-undeclared input and dies with `NG0303: Can't set value of the 'autoScroll' input`, the same root cause
as the first, before the input existed.

## Evidence log

**2026-10-23 — RED, the mechanism and four pages (worker), observed before the implementation.**
`Test Files 5 failed | 180 passed (185)`, `Tests 6 failed | 3747 passed (3753)`. Failures: the component's
`should scroll the banner into view when opted in and a message appears` and `should not scroll when opted in
but there is no message` (both `NG0303` on the undeclared input), and the four pages'
`should scroll the error banner into view when a save fails`, each with
`expected "Mock" to be called with arguments: [ { block: 'nearest' } ]`, `Number of calls: 0`. The
default-off case stayed green in that run, which is the guard `S7` asks for.

**2026-10-23 — RED for the fifth surface (parent), observed the same way.** With only
`[autoScroll]="true"` removed from `scheduling-activity-edit.html:22`:
`Test Files 1 failed | 184 passed (185)`, `Tests 1 failed | 3753 passed (3754)` — exactly the new
scheduling case and nothing else. The line was then restored from a `/tmp` copy and verified byte-identical
to the backup before the next run, never with `git checkout --`, which is what wiped uncommitted work in
`optimistic-lock-conflict`'s W1.

**2026-10-23 — GREEN, final tree.** `Test Files 185 passed (185)`, `Tests 3754 passed (3754)` — the 3746
that passed before this unit plus 8 new cases, with every pre-existing banner and page assertion unchanged.
`npm run lint` → *All files pass linting*, exit 0. `npm run build` → exit 0,
*Output location: …/dist/life-control-app-angular* (the worker's earlier run printed
`Application bundle generation complete. [11.104 seconds]`). The suite was then re-run **on the committed
tree**, after the pre-commit `lint-staged` pass: same `185 / 3754`, and the committed diff is the same
+175/−6 the pre-commit tree measured, so the formatting pass changed nothing.

**2026-10-23 — the diff.** 13 files, +175/−6, all under `life-control-app-angular/src/`: the component
(+30), its spec (+44), the test-setup stub (+8), five templates (+1/−1 each) and five specs (+12 to +38).
Nothing under `life-control-api/` moved, which `S8` requires and `git diff --stat` shows.
