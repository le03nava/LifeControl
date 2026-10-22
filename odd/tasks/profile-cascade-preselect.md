# ODD feature: profile-cascade-preselect

**Repository**: `life-control-app-angular` — the profile screen's free-mode load
(`features/user/profile/user-profile.component.ts`), its spec, and this record. No API, no DTO, no
template, no route, no request payload: the chain is read-only on the wire and this unit never sends
it.
**Status**: implemented — the free-mode load patches the five location levels from the chain the
profile response already carries and issues no store read of its own; the specs that pin it fail on
the pre-fix tree. Nothing is deferred; `G15` of `location-preference` stays separate work.
**Created**: 2026-10-21 · **Base**: `main` @ `f73935d` ·
**Branch**: `fix/profile-cascade-preselect` ·
**Worktree**: `~/workspace/LifeControl-worktrees/fix-profile-cascade-preselect` · **Issue**: `#521`
**Risk**: **none**. Item 3 is considered and rejected: the request payload does not move — the store
travels only when the caller touched the control (`T16`/`S10`), and patching the form never marks it
touched — so no contract a consumer reads changes. Item 1 does not apply (nothing is stored, and the
only write path is untouched), item 2 does not apply (no auth, no permission, no secret), item 4 does
not apply (the load is a one-shot `subscribe`, as it was), item 5 does not apply (no CI, dependency or
environment file), and item 6 is answered by the four specs that fail on the pre-fix tree.

## Specs

**S1** — With a stored preference and `location` present, `/profile?edit=true` holds all five ids from
the chain: `companyId`, `companyCountryId`, `companyRegionId`, `companyZoneId`, `companyStoreId`.
Before this unit the four ancestors were `null`, because only `companyStoreId` was patched.

**S2** — The load issues **no** `getStoreById` call: the chain comes from the response, which the
server derived from that same authorized read (`ProfileLocationResolver`), so the screen pays one
request fewer per mount and there is one chain rather than two that could disagree.

**S3** — The legitimate `S2` pair of `location-preference` — a stored `companyStoreId` with
`location: null` (deleted store, or one the caller can no longer read) — leaves the cascade empty,
asks for no store and raises no error, exactly as the pre-fix tree did.

**S4** — A load that preselects writes nothing: an untouched store posts neither `companyStoreId` nor
`clearStorePreference`, so the stored preference survives an edit that never touched it.

**S5** — Constrained mode (`assignedStores` an array) still renders one select, never walks the four
levels, and its `— None —` still posts `clearStorePreference` alone.

**S6** — The view-mode location row still names the store from `location.companyStoreName` and hides
the row when `companyStoreId` is `null`.

**S7** — `npm run test:coverage:check`, `npm run lint`, `npm run build` and the `.github/scripts/tests`
suites pass.

## Tasks

| ID | Task | Spec | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | Measure the defect on `main` @ `f73935d`: which control the load patches, which read it pays, and how the template disables the levels below an empty ancestor | S1–S3 | inline | — |
| T2 | Open the issue with the problem, the expected outcome, the acceptance criteria and the code evidence | S1–S7 | inline | — |
| T3 | RED: four specs for the chain, the enabled cascade, the absent read and the `S2` pair, plus the two existing assertions that pinned the read turned into the opposite claim | S1–S3 | inline | `eb5bc9c` |
| T4 | GREEN: patch the five levels from `location` and delete the flat read | S1–S3 | inline | `eb5bc9c` |
| T5 | Re-gate: focused spec, full coverage gate, lint, build, Prettier, the `.github/scripts/tests` suites | S4–S7 | inline | `eb5bc9c` + this record's commit |
| T6 | Repair `G17` in `odd/tasks/location-preference.md` where it was declared, pointing at this unit | S1–S2 | inline | this record's commit |

## Log

**L1** (user, verbatim): «dale al g17» — taking `G17` of `odd/tasks/location-preference.md`, the gap
the independent verification of that unit declared: *"The profile screen's free mode still calls
`getStoreById(storeId)` (`user-profile.component.ts:278-291`) to preselect its cascade although
`location` now carries that chain. It is a second read per screen that S1's own rationale argues
against, it is outside S1–S11, and it is the natural next unit rather than a repair."*

**L2** (measurement, T1, 2026-10-21). Read on `main` @ `f73935d` before any edit. The gap the record
declared is real, and it is **one half of the defect**: the load also preselects **nothing**.

- `user-profile.component.ts:283-297` — `preselectFromStoredStore` reads
  `profile.companyStoreId`, calls `companyStoreService.getStoreById(storeId)`, patches
  `{ companyStoreId: store.id }` and seeds the walk with `store.companyId`,
  `store.companyCountryId`, `store.regionId`, `store.zoneId`.
- `user-profile.component.ts:290` — and that patch is the **only** one: the four ancestor controls are
  never written on load. `git show e5430b8^:…user-profile.component.ts` (`populateForm`) patched them
  while the response carried them; `T14` of `company-scope-local-fallback` removed them from the wire
  and `e5430b8` replaced them with the flat read, which seeds the lists but not the controls.
- `user-profile.component.html:181`, `:203`, `:229`, `:241` — each level below the company binds
  `[disabled]` to the level above, so with `companyId` and then `companyZoneId` empty the country,
  region, zone and store selects render **disabled**. The lists the load fetched are unreachable, and
  the caller re-walks the hierarchy to save the store that was already stored.
- `life-control-api/.../ProfileLocationResolver.java:66-79` — `location` is derived from
  `CompanyStoreService.getStoreById(UUID)` itself, the same read the screen was repeating, and every
  failure to derive degrades to `null` (`S2`). The four ids and the store id are therefore identical
  to what the screen was fetching, and the access check it was relying on already ran on the server.
- `user-profile.component.spec.ts:469` — the existing spec pinned the second read
  (`expect(getStoreById).toHaveBeenCalledWith('store-1')`) and asserted nothing about the four
  ancestor controls, which is why the suite stayed green with the cascade unpreselected.

**L3** (RED, T3). Observed before the fix, `npx ng test --no-watch
--include='src/features/user/profile/user-profile.component.spec.ts'`:
**6 failed | 51 passed (57)**. The six, in order: *preselects all five levels from the location chain
(G17)*, *leaves every level of the free-mode cascade enabled after the preselect (G17)*, *issues no
store read of its own for the preselect (G17)*, *preselects nothing for the S2 pair and asks for no
store (S2)*, *never writes from a load, even one that preselects a stored store (S9)* and *keeps the
four-level cascade untouched in free mode (assignedStores: null)*. The last two were pre-existing
specs: their claim about the read inverted rather than disappeared, and both were re-pointed at the
chain.

**L4** (GREEN, T4). `preselectFromStoredStore` patches the five levels from `profile.location` and
seeds the walk from the chain; the `getStoreById` call, its `subscribe` and its error branch are
deleted, and `CompanyStoreService` stays injected for `getStores`. Same command: **57 passed (57)**.

**L5** (why pushing the ancestors does not reopen `T14`). The four ids now live in the form because
the cascade needs a value per select, and the payload is still built from `storeTouched` alone
(`T16`/`S10`): `patchValue` does not mark a control touched, and no handler runs during the load, so
an untouched store posts neither key. The wire contract of `company-scope-local-fallback`'s `T14` —
the ancestors are not writable — is untouched, and `S3` of `location-preference`'s API half still
answers a body carrying them with **400**.

**L6** (differential, T5, 2026-10-21). The base commit's spec, unmodified, run against the fixed
component (`git show origin/main:…user-profile.component.spec.ts` into the tree, run, deleted — it is
not committed): **3 failed | 51 passed (54)**. The three failures are exactly the three assertions
that pinned the behaviour this issue authorizes removing — `should pre-populate the store and walk the
cascade from the flat read`, `never writes from a load…`'s `getStoreById` line, and `keeps the
four-level cascade untouched in free mode`'s `getStoreById` line — and nothing else moved. Every other
behaviour the old spec covers (view-mode row, constrained select, payload shapes, cascade order) still
holds against the new component.

**L7** (gate evidence, T5, 2026-10-21). Every command run with `set -o pipefail`, so an exit code is
the command's and not `tail`'s (`G16` of `location-preference` is the failure mode):

- **Angular coverage gate** (`npm run test:coverage:check`): **185 test files / 3727 tests passed**,
  statements 86.71, branches 85.87, functions 79.33, lines 89.32 — all above threshold,
  `Cobertura dentro de los umbrales. OK`. The sibling unit recorded 185 / 3724 on `dac903a`, and the
  delta is the three net new specs (four added, one replaced).
- **Focused spec**: 57 passed (57) — `L3` is its pre-fix reading, 6 failed / 51 passed.
- `npm run lint` → all files pass; `npm run build` → bundle generation complete; `npx prettier --check`
  on the two changed files → clean. The first Prettier run failed on the spec and was corrected with
  `--write` **before** the commit, not after.
- The eleven `.github/scripts/tests/*.test.sh` suites: **364 assertions, 0 failed** — including
  `odd-status-headers.test.sh` (93 records, 78 assertions) with this record in the tree.
