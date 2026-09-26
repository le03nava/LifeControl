# ODD feature: sales-status-transition

**Status**: **closed** — commit **`1e155da`** (tree **`ca4ceb38`**) on `fix/sales-status-transition`, based
on `main` @ `450b741` after a fast-forward from `982fdd9`. Gates on the committed bytes: `npm run lint`
clean, `npm run build` success, `npm run test:coverage:check` **130 files / 2539 tests / 0 failures /
0 errors / 0 skipped**, coverage **94.08 / 76.01 / 89.32 / 94.08** against the enforced **80 / 60 / 75 /
80**. The commit hook (`lint-staged` → `eslint --fix`, `prettier --write`) re-ran at commit time and
changed nothing, so the committed bytes are the gate-verified bytes: `status-transition.ts`
`sha256 0617bdf5…bd84`, `status-transition.spec.ts` `sha256 5b29d556…a7b`. **Merged as PR #179** — merge
commit **`dfb35adbc00b74bfb0d29ddbd7e66b88f37495c9`**, parents `450b741` + `0a196eb`, 2026-09-26; and
`main^{tree}` after that merge is `8896dcb02c93cec874006fb9694ff2b89cd398b5`, the tree of `0a196eb`, so the
merged content is byte-identical to the gate-verified bytes above. **No work left in this record**; the
four follow-ups it opens are listed under `### Follow-ups opened by this record`.
**Repository**: LifeControl — Angular frontend (`life-control-app-angular/**`) only. One component, one
spec, one wire type. No backend edit, no template edit, no route, no contract change, no build config,
no CI change. Verified diff: **2 files / +68 −5**.
**Created**: 2026-09-25 · **Closed**: 2026-09-26
**Branch**: `fix/sales-status-transition` · **Base**: `main` @ `450b741` (fast-forwarded from `982fdd9`
on 2026-09-26; the only incoming change was `.agents/skills/project-conventions/references/worktrees.md`,
no overlap with this diff) · **Worktree**:
`~/workspace/LifeControl-worktrees/fix-sales-status-transition` (herdr `w1B`), created with the procedure
in `.agents/skills/project-conventions/references/worktrees.md`.
**Requested by**: the user, choosing the live defect from the verified pending-work inventory
(2026-09-25) after being shown that it is the only item in that inventory that breaks a function the
operator uses today; closed on the user's instruction ("cierra sales-status-transition", 2026-09-26).
**Risk**: **low in blast radius, high in user impact if unfixed.** One component reads one endpoint and
builds a name→UUID map from it. No auth, no role, no schema, no contract, no data migration. The whole
change is a boundary mapping plus the tests that should have caught it.

> **How to read the line numbers.** Citations without a commit name are against the base commit
> `982fdd9`. Post-commit line numbers move by the size of each hunk (the `StatusResponse` interface adds
> 6 lines above the component body; the spec grows by 64).

## Problem

The operator cannot change the status of a sales order. The component renders the fallback branch of its
own template and stays there:

- `features/sales/sales-orders/components/status-transition/status-transition.ts:31-34` declares the
  wire type as `interface StatusEntry { id: string; name: string }`.
- `:96` builds the lookup map with `map.set(s.name, s.id)` from the response of
  `GET ${apiUrl}/statuses?statusTypeId=…` (`:145-149`).
- The backend answers `List<StatusResponse>` (`StatusController.java:43-51`), and `StatusResponse` carries
  **`statusName`**, not `name` (`life-control-api/src/main/java/com/lifecontrol/api/status/dto/StatusResponse.java:6-13`).
- The response objects therefore have `s.name === undefined`, the map has a single `undefined` key,
  `validTransitions()` (`:99-109`) filters every transition name out and returns `[]`.
- `status-transition.html:39-41` renders `Loading transitions...` — the final `@else` — because
  `statusFetchFailed()` is false, `isTerminal()` is false for a `Draft` order, and
  `validTransitions().length > 0` is false.

The result is not an error state the operator can act on: it is a permanent "Loading transitions..."
placeholder under an order that is not terminal, with no dropdown and no error message. The status
endpoint itself answers 200 and its payload is correct.

## Why it survived

The spec asserts the bug's own shape. `status-transition.spec.ts:43-46` mocks the statuses as
`{ id, name }` — a payload the backend never sends — so every existing transition assertion
(`:139-176`) passes against a map keyed by a field that does not exist in the contract. The suite is
green on a broken component. This is the same failure mode `odd/tasks/sales-location-aware-stock.md`
recorded as F19: **a check narrower than the claim it is taken as evidence for.**

The sibling half of the same feature was fixed correctly and is the pattern to copy:
`features/purchases/purchase-orders/data/status.service.ts:6-10` declares the wire type as
`{ id: string; statusName: string }` and maps it at the HTTP boundary
(`:53-58` → `list.map((status) => ({ id: status.id, name: status.statusName }))`).

## Fix decision

**Map the wire shape at the HTTP boundary, exactly as the purchases half does** — a distinct
`StatusResponse` interface for the response, and the existing `StatusEntry` kept as the internal
projection. Rejected alternatives:

1. **Keep one interface and read `s.statusName` inside `statusMap`.** Smaller diff, but the component
   would then carry the backend's field naming into its own model and the two halves of the same feature
   would disagree on where the mapping lives. Rejected for consistency with the sibling implementation,
   which already defines the convention for this exact endpoint.
2. **Tolerate both shapes (`s.name ?? s.statusName`).** Hides the next contract drift instead of failing
   on it, and would leave the spec free to mock either shape — which is precisely how this defect
   survived.

The delivered change is exactly that decision: the `StatusResponse` interface, one `map(...)` line in
`loadStatusesForType`, and the generic-type change on the `.get`, plus the spec. Nothing else in the
component moved: the PATCH payload, the `statusFetchFailed()`/`catchError` path and the `statusTypeId`
query param are unchanged in effect (independently verified, item 6 below).

## Tasks

- [x] **T1 — RED first: regression tests that fail on the base commit `982fdd9`.** Delivered as two cases:
      `Draft: should carry the real ids and names from the wire payload` (exact-equality assertion on
      `validTransitions()`) and `Draft: should render the change-status select with one option per valid
      transition` (rendered DOM: the select exists, the fallback branch does not, and the options carry
      the bound ids and the labels). The `mockStatuses` fixture also moved to the real wire shape (T3),
      which turns two pre-existing cases RED as well. **Honest label: the RED run was *reconstructed* at
      closing time, not observed in order.** The previous session wrote the tests and the fix in the same
      sitting, so no RED output was recorded. On 2026-09-26 the base version of `status-transition.ts`
      (`git show 982fdd9:<path>`) was written into the worktree, the focused spec run, and the fixed
      version restored from a `git stash create` object with `sha256` verified identical before and after
      (`0617bdf5…bd84`). **Result: `Test Files 1 failed (1)` / `Tests 4 failed | 9 passed (13)`, exit 1** —
      the four failures are recorded verbatim in the evidence log. GREEN on the final tree: the same
      focused run gives `13 passed (13)`, exit 0.
- [x] **T2 — the boundary mapping.** `StatusResponse` declared at `status-transition.ts:35-38` post-commit
      and mapped in `loadStatusesForType` (`:156` post-commit:
      `map((statuses) => statuses.map((status) => ({ id: status.id, name: status.statusName })))`). The
      internal projection is unchanged: `statusMap` still reads `s.name`/`s.id` (`:102`), and the only
      reads of `statusName` in the file are the declaration and the mapping.
- [x] **T3 — the spec's fixtures.** `mockStatuses` now carries `statusName`, with the reason recorded at
      the fixture itself so the masking cannot be reintroduced silently. The fixture is the only status
      mock in the file: no remaining mock expresses `{ id, name }` on the wire.
- [x] **T4 — gates.** `npm run lint` (exit 0, "All files pass linting."), `npm run build` (exit 0), and
      `npm run test:coverage:check` (exit 0, 130 files / 2539 tests / 0 failures, coverage within the
      enforced thresholds) — run on the final content, re-run after the spec tightening of T5, so the
      numbers in this header belong to the committed bytes.
- [x] **T5 — one independent read-only verification round** over the final diff, delegated to a read-only
      verifier with the claim stated and the F19 lesson as a search target. **0 blocking findings**; two
      real weaknesses in the new test (global `mat-option` query, labels asserted without the bound ids)
      were fixed inside this record's work; R1, N3, N4 and the partial-payload path became the follow-ups
      below. The round's own limits are recorded under `## Verification`, including the one that matters:
      it executed nothing.

## Gates and what they do NOT prove

| Gate | Command | Result on the committed bytes | What it does **not** prove |
| --- | --- | --- | --- |
| Lint | `npm run lint` | exit 0, all files pass | Nothing about the wire contract; it cannot see a field-name mismatch |
| Build | `npm run build` | exit 0, `Application bundle generation complete [7.313 s]` | The component compiles against a type it declares itself, so `name` vs `statusName` is invisible to the compiler |
| Unit + coverage | `npm run test:coverage:check` | exit 0, 130 files / 2539 tests / 0 failures; 94.08 / 76.01 / 89.32 / 94.08 vs 80 / 60 / 75 / 80 | Only what the fixtures say. Before T1, the fixtures asserted the wrong shape and the suite was green |

That third row is the point of this record: **the gate that would have caught this defect is the fixture
shape, not the coverage number.** The coverage row for the changed component is
`95.83 / 62.85 / 92.85 / 95.83`; the branch number is the honest one, and it would have been satisfied by
the broken component too.

## Verification

One independent read-only round, delegated to a separate agent, over `git diff` at the final content plus
the record, with the claim and seven numbered questions handed to it. Verdicts:

| # | Question | Verdict |
| --- | --- | --- |
| 1 | Does the mapping repair the map and `validTransitions()` for every non-terminal status, with no residual raw-wire read? | **upheld** |
| 2 | Does it mirror `purchases/.../status.service.ts`? | **upheld**, with 4 pre-existing divergences (inline `HttpClient` vs injected service, `params` object vs `HttpParams`, local `StatusEntry` vs shared option type, duplicated `StatusResponse`) — none introduced by this diff, none a disagreement about where the mapping lives |
| 3 | Can the spec still pass against a payload the backend never sends? | **upheld** — the bug-shaped mock is gone; the `{ id, name }` literals that remain are the *expected internal* output, not a wire mock |
| 4 | Quality of the two new cases (leakage, order dependence, cleanup, strength) | **upheld with fragility** → became R2/N1, fixed in this record |
| 5 | Completeness: other consumers, missing template change, other mismatched endpoints | **upheld for this defect** — the only two callers of `GET /api/statuses` are the purchases service (correct) and this component; no template change is needed |
| 6 | PATCH payload, error path and `statusTypeId` unchanged in effect | **upheld** |
| 7 | Minimality, with the base source restored | **upheld**, with one pre-existing path that remains open → SST-2 |

**Findings, and what happened to each**

| Id | Finding | Disposition |
| --- | --- | --- |
| R2 | The new DOM case read `document.querySelectorAll('mat-option')` — global overlay state, not the fixture's — and was therefore order-fragile and blind to the bound ids | **Fixed here.** The case now asserts the fallback branch structurally (`.status-loading` absent), reads the options off the select itself (`select.options.toArray()`, `MatSelect.options: QueryList<MatOption>`) and asserts both the values (`['st-pending','st-cancelled']`) and the labels. `grep -n document.querySelectorAll` over the spec: 0 matches. The change was re-gated: RED re-measured against the final spec (still 4 failures), focused GREEN 13/13, and the full suite re-run |
| N1 | The DOM case asserted labels only, never the ids | **Fixed here**, by the same change |
| N2 | The negative assertion is a substring proxy for a template copy string | **Not fixed**: the case now also asserts the structural absence of `.status-loading`, so the copy string is no longer the only witness. Left as the brittleness it is |
| R1 | The sibling's boundary mapping is untested against the wire shape | **Follow-up SST-1** |
| N3 | `StatusResponse` and the status-type types are duplicated between component and service | **Follow-up SST-3** |
| N4 | The status fetch rethrows through `catchError` with no error callback on `subscribe` | **Follow-up SST-4** |
| — | `validTransitions` silently drops configured transition names absent from the payload | **Follow-up SST-2** |

**Verification limits, recorded because they bound the claim above.** The verifying agent executed no
test, lint or build command — its instructions authorized only reads and `git` inspection — so its
"fails on base / passes on fix" conclusions are static derivations from the two file versions. The
dynamic RED and GREEN evidence in this record was produced by the parent session separately, and the
committed gates by a third, command-running round. The verifier also did not observe the backend's JSON
field name live (it is inferred from the Java record `StatusResponse`), did not re-verify the F19
record's own examples, and did not reproduce the 2537-test baseline it was given for comparison.

### Follow-ups opened by this record

- **SST-1 — the sibling's mapping is still untested against the wire shape (the F19 pattern, in the
  opposite direction).** `purchases/purchase-orders/data/status.service.ts:54-58` maps
  `statusName`→`name`, but `status-selector.spec.ts:66` mocks the whole `StatusService` with internal
  `{ id, name }` fixtures, and there is no spec for the service itself. The code is correct today and
  nothing guards it. Not blocking for this fix; the same class of defect that this record exists to close.
- **SST-2 — a partially populated status type still renders the fallback.** `validTransitions` filters
  with `map.has(name)` (`:110-121` on base), so a configured transition whose status is absent from the
  payload is dropped silently and `@else` can still show "Loading transitions..." for a non-terminal
  order. Pre-existing UX gap, not the all-empty symptom this record fixes.
- **SST-3 — the duplicated status wire types.** `StatusResponse` lives in the component and in the
  purchases service; a shared model would stop the next drift before it starts.
- **SST-4 — the status fetch surfaces as an unhandled RxJS error.** `loadStatusesForType` rethrows
  through `catchError` with no error callback on the subscription, so a failed status fetch both sets
  `statusFetchFailed()` and reaches the global handler. Unchanged by this diff.

## Out of scope

- The backend contract. `StatusResponse.statusName` is correct and shared with the purchases half; the
  Angular component is the side that is wrong.
- Any other consumer of `GET /api/statuses`. Verified by grep over `life-control-app-angular/src`: the
  only two callers are the purchases `StatusService` (correct) and this component (defective).
- The `statusFetchFailed()` / error-message behaviour of the component, its PATCH payload, and its
  notifications: all outside the defect and untouched.
- The other inventory items (`T14`/`B1`, `D13b` step 5, the Keycloak protocol mappers, `store-ux-a11y`
  `T7`/`T10`, the W3 follow-ups `F-1`…`F-6`). Separate records, separate decisions.

## Evidence log

| Date | Event | Detail |
| --- | --- | --- |
| 2026-09-25 | Defect confirmed by reading, at `982fdd9` | `status-transition.ts:96` reads `s.name` against a response that only carries `statusName`; the template's final `@else` is the observed user-visible symptom |
| 2026-09-25 | Worktree created | `fix/sales-status-transition` off `main` @ `982fdd9`, herdr workspace `w1B` |
| 2026-09-25 | T1/T2/T3 written in the worktree, uncommitted | The tests, the boundary mapping and the fixture change were left in the working tree with no RED output recorded and no gate run — the reason T1 and T4 were re-measured at closing time |
| 2026-09-26 | Base moved by fast-forward | `git merge --ff-only origin/main`: `982fdd9` → `450b741` (only `references/worktrees.md` changed, no overlap). Working tree untouched |
| 2026-09-26 | **RED re-measured (reconstructed) — 4 failed / 9 passed (13), exit 1** | Procedure: `git stash create` for a recoverable copy → `git show 982fdd9:<path> > <path>` → focused spec run → restore from the stash object → `sha256` back to `0617bdf5…bd84`. Failures: (1) `valid transitions per status > Draft: should allow Pending and Cancelled` → `expected [] to include 'Pending'`; (2) `valid transitions per status > Draft: should carry the real ids and names from the wire payload` → `expected [] to deeply equal [ { id: 'st-pending', …(2) }, …(1) ]`; (3) `valid transitions per status > Pending: should allow Completed and Cancelled` → `expected [] to include 'Completed'`; (4) `transition dropdown rendering > Draft: should render the change-status select with one option per valid transition` → `expected null to be truthy` at `spec.ts:217`, i.e. the `By.directive(MatSelect)` lookup, before the option assertions. (1) and (3) are pre-existing cases that the old fixture had been hiding; (2) and (4) are the new ones |
| 2026-09-26 | Focused GREEN on the final tree | `Test Files 1 passed (1)` / `Tests 13 passed (13)`, exit 0 |
| 2026-09-26 | Independent read-only verification round | 0 blocking findings; 7 verdicts as tabulated above. Fixed R2/N1 inside this record; R1/N3/N4/SST-2 recorded as follow-ups. The round executed no command |
| 2026-09-26 | Spec tightened (R2 + N1) | The DOM case now asserts `.status-loading` absent structurally, reads `select.options` off the select instead of the global DOM, and asserts the bound values plus the labels. Re-gated afterwards: RED still 4 failures on the base source, focused GREEN 13/13, full suite re-run |
| 2026-09-26 | T4 gates on the final content | `npm run lint` exit 0 (`All files pass linting.`); `npm run build` exit 0 (`Application bundle generation complete. [7.313 seconds]`, one non-fatal `baseline-browser-mapping` staleness warning); `npm run test:coverage:check` exit 0 → `Test Files 130 passed (130)` / `Tests 2539 passed (2539)` / 0 failures / 0 errors / 0 skipped in 77.40 s; coverage `94.08 / 76.01 / 89.32 / 94.08` vs the enforced `80 / 60 / 75 / 80` (`scripts/check-coverage.mjs`, which prints `Cobertura dentro de los umbrales. OK`) |
| 2026-09-26 | Work unit committed | **`1e155da`** — `fix(salesorder): map the status wire shape at the HTTP boundary`, 2 files / +68 −5, tree `ca4ceb38`. The pre-commit hook ran (`lint-staged` → `eslint --fix`, `prettier --write`) and changed nothing: post-commit `sha256` of both files equals the gate-run hashes (`0617bdf5…bd84`, `5b29d556…a7b`), so the committed bytes are the gate-verified bytes |
| 2026-09-26 | Record closed | Tasks T1–T5 all closed with the evidence above; follow-ups SST-1…SST-4 opened |
| 2026-09-26 | **Cleanup, in the documented order, before the merge** | `herdr workspace close w1B` → `git worktree remove <path>` → `git worktree prune`. The workspace had to be closed first: it was open on that worktree, and removing a worktree from under an open workspace is the dangling-workspace failure the cleanup order exists to prevent. The worktree was clean, so `git worktree remove` needed no `--force` and destroyed nothing |
| 2026-09-26 | **Merged**, on the user's instruction ("arma el merge") | `gh pr merge 179 --merge --delete-branch`, run from the anchor once the worktree was gone. Merge commit **`dfb35adbc00b74bfb0d29ddbd7e66b88f37495c9`**, parents `450b741` + `0a196eb` — two parents, the repository's convention. `main^{tree}` = `8896dcb02c93cec874006fb9694ff2b89cd398b5` = tree of `0a196eb`, so the merged bytes are the gate-verified bytes, and `1e155da`, `d592269` and `0a196eb` were each verified ancestors of `main` with `git merge-base --is-ancestor`. Post-merge CI on `main` @ `dfb35ad`: Angular CI, API CI, Gateway CI and Docker Build Integrity all **success** (`36214430283`, `36214430301`, `36214430314`, `36214430282`). `gh` printed nothing, which is not evidence either way, so the state was read off `gh pr view --json state,mergeCommit`, `git ls-remote --heads origin` and `git worktree list` instead |
| 2026-09-26 | Post-merge cleanup verified | `gh pr merge --delete-branch` deleted the remote ref and the local branch. Verified rather than trusted: `git fetch --prune` reported `[deleted] origin/fix/sales-status-transition`, `git ls-remote --heads origin` shows `main` alone, `git worktree list` shows the anchor alone, and `gh pr list --state open` is empty. This record's terminal state is written in the header above |
