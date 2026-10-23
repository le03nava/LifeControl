# ODD feature: http-412-conflict-copy

**Repository**: LifeControl — `life-control-app-angular` only. `src/shared/data/http-error-message.ts` and
its spec. No API code, no DTO, no wire contract, no stored data.
**Status**: implemented — the shared helper now maps `412`, its row is in the status table, and the
Angular conflict UX the parent record deferred is complete in all five of its measured points. Nothing is
deferred in this slice; the double report is separate work, priced in `## What stays open`.
**Created**: 2026-10-23 · **Base**: `main` @ `5a5bcbe` · **Branch**: `fix/http-412-conflict-copy` ·
**Worktree**: `~/workspace/LifeControl-worktrees/fix-http-412-conflict-copy`
**Risk**: **none**. No public contract, no stored data, no secret, no permission, no concurrency, no
pipeline and no dependency moves: one `case` added to a pure status→copy function and one row in the table
that pins it. The table fails on the pre-fix tree, so a regression is detectable rather than silent.

## Specs

**S1** — The user's request, verbatim:

```
ok continua con el 2
```

"2" is item 2 of the pending inventory delivered to the user in the same session: `optimistic-lock-conflict`,
**D13b** — the Angular half of the conflict UX, deferred by that record and never scheduled. It is an
authorization to continue that unit, not a new design decision.

**S2** — The deferred scope, verbatim from `odd/tasks/optimistic-lock-conflict.md` (`## Handoff to the
Angular slice (D13b)`), five numbered points: `version` in both inventory interfaces; the settings PUT
sending the version read from the GET; a `409` case in `handleSaveError`; a `409` entry in
`shared/data/http-error-message.ts`; and the same treatment for the other four `@Version`-backed edit
screens.

**S3** — Measured before writing anything: **four of the five points were already on the tree**, closed by
later units that the parent record predates, so the handoff's list is not a to-do list any more. Point 1:
`store-location-summary.models.ts:41` and `:52` carry `version` on the response and the optional
precondition. Point 2: `store-inventory-settings-panel.ts:202-208` echoes the version it read and spreads it
only when present. Point 3: `store-inventory-settings-panel.ts:247-256` answers a `409` with the reload
recovery. Point 5: the four tree screens carry `version` in their models (`store.models.ts:22`,
`store-area.models.ts:29`, `store-zone.models.ts:30`, `store-location.models.ts:32`), echo it on update and
branch on `412` (`stores-edit.ts:342`, `store-areas-edit.ts:248`, `store-zones-edit.ts:235`,
`store-locations-edit.ts:251`). Point 4 was half done: the `409` entry has been there since before this
work, and **`412` had no entry**, which is the hole this slice closes.

**S4** — Why `412` and not `409` is the missing half. `412` is this platform's *lost update* status, and it
is unambiguous: `VersionPreconditionException` "maps to **412 Precondition Failed**. It is deliberately not
a `ConflictException`" (`VersionPreconditionException.java:6`), rendered by exactly one handler
(`GlobalExceptionHandler.java:74-80`). Every `@Version`-backed write that takes a precondition raises it:
the five store-tree services and `SchedulingActivityService`. `httpErrorMessage` is the single source of
the copy that the global toast shows (`error-interceptor.ts:20`) for every request that does not opt out,
and **no store-tree service opts out** (`grep 'SKIP_ERROR\|HttpContext'` over
`features/companies/stores/data/*.ts`: no match). So a lost update on a store, area, zone, location or
activity produced two messages at once: the page's own, correct one, and a red toast reading
*"Ocurrió un error inesperado. Intentá de nuevo más tarde."* — a server-fault sentence for an event whose
recovery the page had already performed.

**S5** — The copy rule, and why it does not reuse the `409` sentence. The toast cannot know which recovery
ran: `stores-edit` deliberately does **not** reload (`stores-edit.ts:337-346` — `history.state` would hand
back the same stale copy, so it tells the operator to reopen from the list), while areas, zones, locations
and scheduling activities reload in place and say so. A shared sentence may therefore assert only what is
true in both cases — another session saved first, this write was refused — plus a reload instruction the
operator can always follow. It cannot claim that `recargá la página` restores the current values, because
for `stores-edit` it does not, and it cannot claim the page already reloaded, because for `stores-edit` it
did not.

**S6** — What must keep working, and what covers it: every existing row of the status table — `400`, `401`,
`403`, `404`, `409`, `500` — the generic fallback for an unmapped status, the generic fallback for a
non-HTTP and for an unknown value, and the `cause`-chain unwrapping for the resource API. All of them are
the pre-existing cases of `http-error-message.spec.ts` and they pass unchanged; the same run that holds the
new row holds every one of them, which is the differential check for a pure function whose whole surface is
that table.

**S7** — What this slice deliberately does **not** do: it does not touch the `409` copy (that status is
overloaded in this app — duplicate name and disabled ancestor on the tree, lost update on the settings
panel — and a correct sentence there needs its own measurement); it does not make the toast silent for a
`412` (see `## What stays open`); it does not move the settings endpoint from `409` to `412`; and it does
not touch the API at all.

## What stays open

**The double report.** A `412` now says the right thing in both places, but the page banner and the toast
still report the same event. The convention that already exists for this is `SKIP_ERROR_NOTIFICATION`, used
where "the page owns the visible error" (`receipt-create.ts:211`, and the settings GET's expected `404`).
Applying it here is not free: the token is per **request**, not per status, so opting the five mutation
calls out would also silence their `403`, `409` and `5xx` toasts. Each of those pages does have a banner for
them, but which of the two voices should own a failed write is a product decision, not a defect this slice
measured. It stays open, priced, with the evidence above.

## Tasks

| ID | Spec | Task | Commit |
| --- | --- | --- | --- |
| T1 | S2, S3 | Re-measure the five handoff points on `main` @ `5a5bcbe` and keep only the one that is actually missing | — (read-only) |
| T2 | S1 | Worktree and branch `fix/http-412-conflict-copy` off `main` @ `5a5bcbe` | — |
| T3 | S4, S6 | RED: the `412` row in `http-error-message.spec.ts` | (lands with T4) |
| T4 | S4, S5 | GREEN: the `412` case in `httpErrorMessage`, with the comment that records why the sentence names no specific recovery | `fix(shared)` |
| T5 | S6 | `npx ng test --no-watch`, `npm run lint`, `npm run build` | — (checks, not a commit) |
| T6 | — | This record, with the evidence it cites | `docs(odd)` |
| T7 | — | The double report (S7, `## What stays open`), on its own decision | — (not scheduled) |

## Log

**L1** *(the user's request, verbatim — `S1`)*: «ok continua con el 2».

**L2** *(the measurement, and the reason this slice is smaller than the record it closes — `S3`)*: the
parent record's handoff reads like five pending items; on `main` @ `5a5bcbe` four of them are shipped and
the fifth is shipped for `409` and missing for `412`. `version-precondition-store-tree` and the store
inventory settings unit are what closed them, and neither is named in the parent's handoff because both
landed after it. Nothing in the parent record is rewritten: it is frozen narrative, and this record is the
evidence of the supersession, the same rule `access-request-revoked-rule` applied to its parent.

**L3** *(the copy decision — `S5`)*: three candidate sentences were discarded against measured behaviour of
the five pages that own a `412`. *"Recargá la página y volvé a intentar"* (the `409` sentence) is false for
`stores-edit`, which cannot recover that way. *"Se recargaron los valores actuales"* is false for the same
page. *"No se pudo guardar"* names no cause and reads like the generic failure this slice exists to remove.
What shipped asserts the conflict and the refusal, and asks for a reload without claiming what the reload
yields.

**L4** *(not done, on purpose — `S7`)*: the `409` sentence and the toast suppression. Both are adjacent and
both are tempting; both change behaviour nobody asked this slice to change, and the second is a product
decision about which voice owns a failed write.

## Evidence log

**2026-10-23 — the worktree, and a measured environment constraint.** `./.github/scripts/new-unit.sh
fix/http-412-conflict-copy` created the worktree off `origin/main` @ `5a5bcbe`. A fresh worktree has no
`node_modules`, and symlinking the anchor's is **not** a shortcut for the Angular unit tests: `npx vitest
run` then fails with `The service 'BrowserXhr' needs to be compiled using the JIT compiler`, because the
Angular linker does not resolve partially-compiled metadata through the symlink. `npm ci --prefer-offline`
against the local cache installed the real tree in **9s**, which is the route to use.

**2026-10-23 — RED, observed before any source edit.** Full suite with only the spec row added:
`Test Files 1 failed | 184 passed (185)`, `Tests 1 failed | 3746 passed (3747)`. The single failure is the
new row — `should map status 412 to a Spanish message` — with
`expected 'Ocurrió un error inesperado. Intentá …' to be 'Otra sesión guardó este dato primero …'`. Note
that `--filter=http-error-message` reported `185 skipped / 3747 skipped`: it selects nothing in this
builder, so the focused loop is the full suite.

**2026-10-23 — GREEN.** Same command after the fix: `Test Files 185 passed (185)`,
`Tests 3747 passed (3747)` — the `3746` that passed on the pre-fix tree plus the new row, with every
pre-existing mapping, fallback and unwrapping case unchanged. That is the differential check `S6` asks for.
`npm run lint` → *All files pass linting*, exit 0. `npm run build` → *Application bundle generation
complete*, exit 0.

**2026-10-23 — the diff.** Two files, both under `life-control-app-angular/src/shared/data/`: the helper
(+7, one `case` and its comment) and its spec (+4: the table row, which the pre-commit
`prettier --write` of `lint-staged` wrapped into a multi-line array entry because the sentence exceeds
the print width — the assertion is unchanged). Nothing under `life-control-api/` moved, which `S7`
requires and `git diff --stat` shows. The suite was re-run on the committed tree, after that formatting,
with the same result.
