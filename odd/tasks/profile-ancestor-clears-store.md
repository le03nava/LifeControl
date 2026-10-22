# ODD feature: profile-ancestor-clears-store

**Repository**: `life-control-app-angular` — the profile screen's free-mode ancestor handlers
(`features/user/profile/user-profile.component.ts`), its spec, and this record. No API, no DTO, no
template, no route: the wire contract does not move, only *which* of its two existing keys travels.
**Status**: implemented — an ancestor change that empties the store control now clears the stored
preference instead of leaving it behind, pinned by one spec per level plus the two cases that must not
change; the specs fail on the pre-fix tree. `G15` of `location-preference` is closed by this unit.
**Created**: 2026-10-22 · **Base**: `main` @ `1e8217d` ·
**Branch**: `fix/profile-ancestor-clears-store` ·
**Worktree**: `~/workspace/LifeControl-worktrees/fix-profile-ancestor-clears-store` · **Issue**: `#523`
**Risk**: **none**. Item 1 was declared first and re-judged against its own criteria before the diff was
committed, because declaring it and then relaxing it is exactly what the criteria exist to stop: the
clear mechanism **already existed** (`clearStorePreference`, `S5` of `location-preference`) with its
server-side validation untouched, the only value it affects is one user-recoverable preference, and the
failure mode is caught by four specs and visible on the next load — none of *hard to detect*, *hard to
undo* or *reaches beyond the change* holds, so item 1 does not apply. Item 3 is rejected the same way:
both keys exist and the three-state rule is unchanged, so no consumer's contract moves. Items 2, 4 and 5
do not apply (no auth, no concurrency, no CI or dependency). Item 6 is answered by the six specs.

## Specs

**S1** — When an ancestor change empties the store control, the payload carries
`clearStorePreference: true` **alone** and never `companyStoreId: null`. `null` on a `UUID` is
indistinguishable from an omitted key on the wire (`L6` of `location-preference`), which is the defect
the second field exists to close; the two keys stay mutually exclusive by construction (one branch of
`storePreferencePayload`), because the server answers the pair with `400`.

**S2** — The rule holds at **each** of the four levels — company, country, region and zone — because
each handler resets `companyStoreId` in the form. Before this unit none of the four did anything on the
wire, so the store control showed empty while the row still held a store, and the next `/profile` load
brought that store back under an ancestry the caller had just replaced.

**S3** — An ancestor change over a store control that already holds **no** store marks nothing: the
payload omits both keys. Nothing is cleared because there is nothing to clear, and an edit that never
touched a stored value keeps not writing one (`T16` of `company-scope-local-fallback`).

**S4** — The clear is not sticky: selecting a store after an ancestor change posts that
`companyStoreId` and no `clearStorePreference`, because `onStoreChange` re-marks the control with a
value and `storePreferencePayload` chooses the other branch.

**S5** — The three states the store control already had keep their behavior: its own `— None —` posts
`clearStorePreference` alone, an untouched control posts neither key, and the constrained mode's
assigned-store select is untouched by this unit (its handlers are not the four ancestors).

**S6** — The load path still writes nothing: preselecting the cascade never sets `storeTouched`, so an
edit that opens and saves without touching the preference still leaves the row as it was (`S9` of
`location-preference`, `S4` of `profile-cascade-preselect`).

**S7** — `npx vitest run src/features/user/profile/user-profile.component.spec.ts`, `npm run lint`,
`npm run build` and `npm run test:coverage:check` pass.

## Tasks

| ID | Task | Spec | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | Measure `G15` on `main` @ `1e8217d`: which handlers reset the store, where `storeTouched` is set, and how the payload chooses between the two keys | S1–S5 | inline | — |
| T2 | Open issue `#523` with the problem, the expected outcome, the acceptance criteria and the code evidence | S1–S7 | inline | — |
| T3 | RED: six specs — four ancestor levels, the empty-control case, and the re-selected store — against the pre-fix tree | S1–S4 | inline | `d0315a9` |
| T4 | GREEN: one private helper that marks the preference as cleared when the form actually held a store, called by the four ancestor handlers | S1–S4 | inline | `d0315a9` |
| T5 | Gates: focused spec, coverage gate, lint, build, Prettier | S5–S7 | inline | `d0315a9` |
| T6 | Adversarial read of the diff: every `storeTouched` write site, the guard's predicate against the payload's, the absent-control case, and whether any spec is vacuous | S1–S6 | inline | `d0315a9` + this record's commit |

## Log

**L1** (user, verbatim): «ok, dale a g15 primero» — taking `G15` of `odd/tasks/location-preference.md`
(its `L16`), the gap that unit declared and that `odd/tasks/profile-cascade-preselect.md` re-measured
as still open: *"the free mode's ancestor handlers reset the form's `companyStoreId` without setting
`storeTouched`, so saving after changing only an ancestor omits both keys and the stored preference
survives"*. Choosing this unit first is the user's decision between it and the stale dev front end.

**L2** (measurement, 2026-10-22, against `origin/main @ 1e8217d`). What the pre-fix tree actually did,
read from the two paths rather than from the record: `onCompanyChange`/`onCountryChange`/
`onRegionChange`/`onZoneChange` each patch `companyStoreId: null` and **none** of them sets
`storeTouched`, whose only two writers were `onStoreChange` and `onAssignedStoreChange`; and `save()`
spreads the preference keys only `when(storeTouched())`. The four handlers are rendered **only** in the
template's free-mode `@else` branch (`user-profile.component.html:160-250`), so the constrained mode and
its assigned-store select are not on this path at all.

**L3** (RED, 2026-10-22). Six cases, and the split is stated rather than glossed: **four failed on the
pre-fix tree** — one per ancestor level, the table cases that assert the payload — and **the two other
cases passed before and after by design**, because `S3` (nothing to clear) and `S4` (re-selected store)
are the guards that keep the new rule from over-firing. Focused spec on the pre-fix tree: **4 failed /
59 passed / 63 total**. GREEN after the fix: **63 passed**, same command.

**L4** (gates, 2026-10-22). Each number read from the command, not from a summary: the focused file
`npx ng test --no-watch --coverage=false --include=src/features/user/profile/user-profile.component.spec.ts`
→ **63 passed**; `npm run test:coverage:check` (the full suite plus the thresholds) → **exit 0**,
statements **86.71%**, branches **85.88%**, functions **79.33%**, lines **89.33%**, `Cobertura dentro de
los umbrales. OK`; `npm run lint` → *All files pass linting*; `npm run build` → *Application bundle
generation complete*; `npx prettier --check` on both files → *All matched files use Prettier code style*.

**L5** (risk re-judgment and the adversarial read, 2026-10-22). The record declared **item 1** at the
start and the independent verifier **refused admission** for exactly that reason: the task did not name
a high-risk item, and the admission rules do not accept a verifier created to satisfy a declaration.
Rather than re-declare to get one, the declaration was re-judged against the criteria — and the honest
answer is that item 1 does not hold (the clear mechanism and its validation are pre-existing, the value
is one user-recoverable preference, the mistake is pinned by four specs and visible on the next load),
so the Risk line is now `none` with that reasoning written down. What replaced the outside view was a
read-only adversarial pass over the diff, and the checks are stated so they can be repeated: every
`storeTouched` write site is now one of three — the new helper plus the two pre-existing handlers — and
nothing on the load path sets it (`preselectFromStoredStore` patches the form directly); the helper's
predicate is the **same truthiness** `storePreferencePayload` uses to choose between the two keys, so
the guard and the payload cannot disagree about whether a store is there; the `companyStoreId` control
is built in the form constructor (`:201`), so no call can read an absent control; and each of the four
table cases calls a distinct handler, which is what makes them four cases and not one repeated four
times. No blocker was found, and none of the four specs rests on a claim the component cannot satisfy —
the payload assertion is the observable, not an internal call.
