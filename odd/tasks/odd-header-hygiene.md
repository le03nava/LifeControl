# ODD feature: odd-header-hygiene

**Status**: implemented — 15 prescribed edits across 11 files on `docs/odd-header-hygiene`
off `main` @ `c49d296`, worktree `~/workspace/LifeControl-worktrees/docs-odd-header-hygiene` (herdr `w15`).
Documentation only: no source, no test, no CI, no runtime behaviour. This header carries only dated local
facts and makes no claim about push or PR state — see D2.
**Created**: 2026-09-24
**Risk**: **low.** Every edit is prose that asserts a delivery state or a cross-reference. The only
non-prose edit is a numeric coverage table, and its values come from a gate run recorded below, not from
memory.
**Requested by**: the user, choosing "Higiene documental ODD" from the verified pending-work inventory,
then narrowing the scope to headers plus stale body lines and asking for a real coverage re-measurement.

## Why this exists

`odd/tasks/odd-status-reconciliation.md` (PR #162) repaired 14 stale status headers and decided the
governing invariant, but it deliberately left three classes of residue (its `## Follow-ups`, `:366-388`),
and the cycle that followed — PRs #164 through #169 — produced **four new headers asserting "Not pushed;
no PR is open"** for work that is now merged. That is exactly the drift D2 forbids, reproduced by the
next merge, as the reconciliation predicted.

A read-only inventory (2026-09-24) enumerated the residue. This slice closes the part that is a **false
statement of fact** and leaves the frozen narrative alone.

## Decisions

- **D1 — scope: the false state claims, not the whole prose.** Four headers from the just-closed cycle,
  six stale body lines from earlier slices, two mis-cited anchors, and the stale coverage table. The
  preventive convention (`.agents/skills/project-conventions/references/`, option (a) of
  `odd-status-reconciliation.md:379-384`) is **explicitly out of scope**: the user chose not to bundle a
  skill-convention change into this slice.
- **D2 — the header invariant, inherited verbatim from the reconciliation.** A `**Status**:` line "states
  durable state plus its evidence (PR number, merge commit, date) and never asserts a live PR-open state"
  (`odd-status-reconciliation.md:84-90`). The repaired headers therefore name **merged + PR number + merge
  commit + date**, or state only what remains.
- **D3 — frozen narrative is not rewritten.** Dated evidence-log rows, findings that were true when
  written, and the per-slice narrative preserved by D4 stay untouched. Specifically **not** rewritten:
  `optimistic-lock-conflict.md:227` (a dated row recording the status correction itself — the audit trail
  of this very class of repair, named as legitimate at `odd-status-reconciliation.md:241`);
  `mobile-breakpoint-single-source.md:209` ("The branch is unpushed…", inside finding F3, whose whole point
  is *why* that adjective was not reworded then); `purchase-order-goods-receipt.md:369` and its 5 770-char
  narrative line (protected by D4); the historical worktree paths and herdr workspace ids in the six
  headers of the #164–#169 cycle (they say where the work happened, which is evidence, not a live claim —
  the worktrees themselves are gone, which is normal).
- **D4 — the coverage table is re-measured, not cited.** The user asked for a real gate run. The numbers
  in `life-control-app-angular/AGENTS.md` are `Actual`, and a prior measurement — even one taken on this
  same tip — would leave a reader unable to tell this table from the stale one it replaces. The run is
  recorded under Evidence.

## The edit contract (verbatim; apply exactly, do not restyle)

15 edits in 11 files. Every `BEFORE` block is the exact current text; every `AFTER` block is the exact
replacement. No other line in any file may change.

### E1 — `odd/tasks/breakpoint-vars.md` (header; PR #164)

```text
BEFORE
**Status**: implemented — W1 (`36ac3d3`) on `refactor/breakpoint-vars` off `main` @ `218f4b0`, in the
worktree `~/workspace/LifeControl-worktrees/refactor-breakpoint-vars`. Build, lint and the compiled-CSS
gate pass. Not pushed; no PR is open yet.
```

```text
AFTER
**Status**: merged — PR #164 (`refactor/breakpoint-vars` @ `4469cf717`), 2026-09-24. W1 (`36ac3d3`) on
`refactor/breakpoint-vars` off `main` @ `218f4b0`, in the worktree
`~/workspace/LifeControl-worktrees/refactor-breakpoint-vars`. Build, lint and the compiled-CSS gate
passed. The OpenSpec change this record superseded was archived at that merge commit (see `## Follow-ups`).
```

### E2 — `odd/tasks/breakpoint-vars.md` (the archive was deferred, and has now happened)

```text
BEFORE
findings round: the first version said it "gets archived", in the present tense, as though that had
happened. It had not and still has not — the archive step is deliberately deferred until after this
branch merges, because the change lives in `openspec/` in the anchor repo, which is gitignored and does
not travel with this branch.)* What is left is the real, smaller debt the change was pointing at:
```

```text
AFTER
findings round: the first version said it "gets archived", in the present tense, as though that had
happened. It had not at the time of writing — the change lived in `openspec/` in the anchor repo, which
is gitignored and does not travel with this branch, so the step had to wait for this branch to land. It
has since been archived at `4469cf7`, the merge commit of PR #164.)* What is left is the real, smaller
debt the change was pointing at:
```

### E3 — `odd/tasks/breakpoint-vars.md` (the follow-up is discharged)

```text
BEFORE
- **The OpenSpec change `modify-form-layout-breakpoint` still needs archiving** (F3) — as superseded, with
  this record as the reason. It lives in the anchor repo's gitignored `openspec/`, so it cannot travel
  with this branch and the step is deferred until after the merge.
```

```text
AFTER
- **The OpenSpec change `modify-form-layout-breakpoint` — archived, no longer outstanding.** (F3) It was
  archived as superseded, with this record as the reason, at `4469cf7` (the merge commit of PR #164):
  `openspec/changes/archive/2026-09-24-modify-form-layout-breakpoint/archive-report.md`.
```

### E4 — `odd/tasks/mobile-breakpoint-single-source.md` (header; PR #165)

```text
BEFORE
falsification control was reproduced twice. Independently verified read-only — see `## Findings`. **Not
pushed; no PR is open.**
```

```text
AFTER
falsification control was reproduced twice. Independently verified read-only — see `## Findings`.
**Merged as PR #165 (`7f8ba11b9`), 2026-09-24.**
```

### E5 — `odd/tasks/mobile-breakpoint-guard.md` (header; PR #166)

```text
BEFORE
**Not pushed; no PR is open.**
```

```text
AFTER
**Merged as PR #166 (`89b4d9b76`), 2026-09-24.**
```

### E6 — `odd/tasks/mobile-viewport-observer.md` (header; PR #167)

```text
BEFORE
independently. **Not pushed; no PR is open.**
```

```text
AFTER
independently. **Merged as PR #167 (`07252376f`), 2026-09-24.**
```

### E7 — `odd/tasks/product-variant-identity-split.md` (the `## Plan` table lagged its own header)

```text
BEFORE
| S2 | Backend cutover (atomic) | `V14`; `ProductVariant` reduced; definition CRUD + D6(b) store endpoint; read paths joined; membership validators; stock writers and locking; all backend tests. | High | pending |
| S3 | Frontend adapters | Variant models, services and the six variant-consuming screens. | Medium | pending |
```

```text
AFTER
| S2 | Backend cutover (atomic) | `V14`; `ProductVariant` reduced; definition CRUD + D6(b) store endpoint; read paths joined; membership validators; stock writers and locking; all backend tests. | High | **done** |
| S3 | Frontend adapters | Variant models, services and the six variant-consuming screens. | Medium | **done** |
```

### E8 — `odd/tasks/store-zones-backend.md` (the deferral is over)

```text
BEFORE
- [x] T12 — Commit `feat(store): add store zones CRUD backend` (atomic). *(note: the original plan said "awaiting user request"; the user requested the follow-up fixes instead, so the commit is deferred until after this round)*
```

```text
AFTER
- [x] T12 — Commit `feat(store): add store zones CRUD backend` (atomic). *(note: the original plan said "awaiting user request"; the user requested the follow-up fixes instead, so the commit waited until after that round — it landed as PR #103.)*
```

### E9 — `odd/tasks/store-areas-frontend.md` (the open item was already delivered)

```text
BEFORE
None for this feature. Separate open item: the uncommitted purchase-orders refactor (nested detail
cards removal) still needs its own commit or discard decision.
```

```text
AFTER
None for this feature. The separate item this section used to carry — the purchase-orders refactor that
removed the nested detail cards — is **already delivered**: it landed as work unit 1 of
`odd/tasks/purchase-order-sections-polish.md` (commit `2262452`, PR #101 → merge commit `f8a29f8`,
2026-09-17), and no `detail-card` marker remains anywhere under
`life-control-app-angular/src/features/purchases/purchase-orders/`. The "not verified" note at
`odd/tasks/odd-status-reconciliation.md:371` is closed by that check.
```

### E10 — `odd/tasks/docker-images-rebuild.md` (the cross-reference is stale)

```text
BEFORE
session's `project-conventions-skill` feature (its doc lists T1 done, T2–T5 pending). It is **not**
```

```text
AFTER
session's `project-conventions-skill` feature (its doc then listed T1 done, T2–T5 pending; that work has
since landed through PRs #130/#131/#132 and T5 is closed by that record's own correction). It is **not**
```

### E11 — `odd/tasks/angular-docs-prettier-scope.md` (the record is versioned, not gitignored)

```text
BEFORE
**Local doc**: `odd/tasks/angular-docs-prettier-scope.md` (gitignored)
```

```text
AFTER
**Local doc**: `odd/tasks/angular-docs-prettier-scope.md` (versioned — `.gitignore` carries `odd/*` plus `!odd/tasks/`)
```

### E12 — `odd/tasks/angular-docs-prettier-scope.md` (its own body contradicted its corrected header)

```text
BEFORE
`life-control-app-angular/.prettierignore` (new, 6 lines) and 8 lines in `life-control-app-angular/AGENTS.md`. Not pushed, no PR (the user's decision).
```

```text
AFTER
`life-control-app-angular/.prettierignore` (new, 6 lines) and 8 lines in `life-control-app-angular/AGENTS.md`. Merged as PR #127 (`f576c3460`), 2026-09-20.
```

### E13 — `odd/tasks/unify-mobile-viewport-consumers.md` (mis-citation: the header-drift finding is F11)

```text
BEFORE
local facts and makes no claim about push or PR state — see F6.
```

```text
AFTER
local facts and makes no claim about push or PR state — see F11.
```

### E14 — `odd/tasks/unify-mobile-viewport-consumers.md` (the anchor it names is off by two lines)

```text
BEFORE
- **F11 — the ODD header drift** (`mobile-breakpoint-guard.md:9` and `mobile-viewport-observer.md` both
  asserting "Not pushed; no PR is open" for merged work). This record deliberately makes no live-state
  claim. The rest is a header-correction slice with a decided rule, not spot edits.
```

```text
AFTER
- **F11 — the ODD header drift** (`mobile-breakpoint-guard.md:11` and `mobile-viewport-observer.md:11`
  both asserting "Not pushed; no PR is open" for merged work). This record deliberately makes no
  live-state claim. The correction was applied by `odd/tasks/odd-header-hygiene.md`.
```

### E15 — `life-control-app-angular/AGENTS.md` (the coverage table, re-measured)

```text
BEFORE
| Métrica | Actual | Umbral (floor) |
|---------|--------|----------------|
| Statements | 92.53% | 80% |
| Branches | 72.56% | 60% |
| Functions | 86.13% | 75% |
| Lines | 92.53% | 80% |

**Meta objetivo:** mantener la cobertura ≥ la línea actual (92.5/72.6/86.1/92.5). Los umbrales son guardrails con ~12 pts de margen sobre el baseline para absorber variación legítima.
```

```text
AFTER
| Métrica | Actual | Umbral (floor) |
|---------|--------|----------------|
| Statements | 94.07% | 80% |
| Branches | 75.98% | 60% |
| Functions | 89.32% | 75% |
| Lines | 94.07% | 80% |

**Meta objetivo:** mantener la cobertura ≥ la línea actual (94.1/76.0/89.3/94.1). Los umbrales son guardrails con ~14–16 pts de margen sobre el baseline para absorber variación legítima. La medición es de la corrida de `npm run test:coverage:check` en `main` @ `c49d296` (2026-09-24); `branches` varía en el segundo decimal entre corridas (75.93 / 75.98 / 75.99 observadas), así que el contrato es el floor, no el `Actual`.
```

## Out of scope (decided, must not be swept in)

- The preventive convention for `.agents/skills/project-conventions/references/` (D1).
- Any live-state claim inside `odd/tasks/odd-status-reconciliation.md` and
  `odd/tasks/purchase-order-goods-receipt.md`: that record's quotations and its D4-protected narrative
  line are history, and the audit record of this class of repair is the last place to rewrite.
- `purchase-order-goods-receipt.md:369` (a 2026-09-20 section heading saying "W2d3 … unpushed"), the
  `:209`/`:244`/`:354` narrative rows, and `product-create-ux.md`'s frozen split narrative — all noted as
  legitimate historical text by `odd-status-reconciliation.md:239-241`.
- The `openspec/changes/stock-deduction-on-item-add/` empty directory: untracked (`openspec/` is
  gitignored), no content, nothing to correct in a versioned file.
- `docker/scripts/_common.sh::verify_artifact_freshness` and `deploy.sh`'s missing force flag: code, and
  a real defect, not documentation hygiene.

## Evidence

- **Branch and base**: `docs/odd-header-hygiene` off `main` @ `c49d296`, worktree named above.
- **Verified PR ↔ record mapping** (`gh pr list --state merged`): #164 `refactor/breakpoint-vars` →
  `4469cf717`; #165 `fix/mobile-breakpoint-single-source` → `7f8ba11b9`; #166 `fix/mobile-breakpoint-guard`
  → `89b4d9b76`; #167 `refactor/mobile-viewport-observer` → `07252376f`; #168
  `refactor/unify-mobile-viewport-consumers` → `12cef9063`; #169 `fix/test-timeout-headroom` → `c49d29678`.
  All ancestor-checked against `main`.
- **`git merge-base --is-ancestor` verified for**: `2262452`, `f8a29f8f0`, `1d5efb5`, `f576c3460`,
  `4469cf7` (the merge commit whose archive step E2/E3 records), and the six merge commits above.
- **Archive evidence for E2/E3**:
  `openspec/changes/archive/2026-09-24-modify-form-layout-breakpoint/archive-report.md:3-5` — `Archived`
  2026-09-24, `Archived at` `main` @ `4469cf7` (merge commit of PR #164), verdict "superseded — do NOT
  implement this change".
- **E9 evidence**: `purchase-order-sections-polish.md:75` (work unit 1 = `2262452`), `:78` (PR #101), and
  `grep -rn detail-card life-control-app-angular/src/features/purchases/purchase-orders/` → 0 matches.
- **E11 evidence**: `.gitignore:153-154` (`odd/*` then `!odd/tasks/`) and
  `git ls-files odd/tasks/ | wc -l` → 31 versioned files (30 at the base `c49d296`; this record is the
  31st).
- **E10 evidence**: `odd/tasks/project-conventions-skill.md:8` — "T5 is stale and is closed by this
  correction", and `:87` — T5 landed via PRs #130/#131/#132.
- **Coverage re-measurement (E15)**: `npm run test:coverage:check` in the anchor at `c49d296`, run
  2026-09-24. The gate reported and enforced: `[check-coverage] statements 94.07% OK`, `branches 75.98%
  OK`, `functions 89.32% OK`, `lines 94.07% OK` → "Cobertura dentro de los umbrales. OK". The table's
  `Actual` column is that aggregate; the floor column is unchanged. Note the second-decimal variation in
  `branches` across the cycle (75.93 / 75.98 / 75.99), which is why the prose now points at the floor as
  the contract — a value that moves between runs cannot be a threshold.

## Findings

- **F1 (low, mine, corrected before delivery) — the contract's own edit count was stale.** It was written
  as "14 edits in 11 files" because E15 (the coverage table) could not be prescribed until the gate run
  finished; by the time E15 was appended, the count had not been revisited. The delegated writer caught
  it and reported it instead of silently proceeding, and the count is now 15. Recorded rather than
  quietly fixed: a count written before the last change went stale in exactly the way this slice exists to
  repair, in the very document that prescribes the repair. It is also the reason the writer was told to
  stop on any mismatch instead of improvising — a writer that pads its own report is the failure mode this
  slice is about.
- **F2 (low, mine, found by the independent verification) — three imprecisions inside this record, plus
  one inconsistency this slice created in a file it did not edit.** The verification reproduced every one
  of C1–C6 (the four merge claims, the archive, the PR #101 delivery, the coverage arithmetic, the absent
  live-PR claims, and the untouched frozen narrative), and then held up C7: (a) the E11 evidence count
  read "30 versioned files" while the commit that carried it made the true count 31 — the same
  measured-then-staged staleness as F1, one line below it; (b) the E9 evidence cited
  `purchase-order-sections-polish.md:77` for the PR row, which is `:78` (the `:77` row is `Branch`); and
  (c) T5's evidence was a dangling self-reference with no commit hash. Separately, correcting the
  `AGENTS.md` table left `test-timeout-headroom.md:210` claiming "~12 points of margin" against a margin
  that is now 14.07–15.98 — an inconsistency **caused by this slice**, so fixing it is not scope creep.
  All four are corrected by the commit that carries this entry, which also records `1a75908` as the slice
  commit. Two lessons worth carrying: a documentation slice that asserts numbers must re-check its own
  numbers after staging, and a repair can create the very drift it repairs — one line below its own
  warning about that failure mode.

## Task log

| Date | Task | Evidence |
|---|---|---|
| 2026-09-24 | T1 — read-only inventory of pending work | `gentle-ai-explore` scout + parent re-verification of every citation; result recorded as the edit contract above |
| 2026-09-24 | T2 — gate run for E15 | `npm run test:coverage:check` in the anchor at `c49d296`: 130 test files / 2534 tests passed, aggregate 94.07 / 75.98 / 89.32 / 94.07, "Cobertura dentro de los umbrales. OK" |
| 2026-09-24 | T3 — apply the 15 prescribed edits | delegated to one `gentle-ai-worker` with the exact before/after text and 11 allowed edit surfaces; reported 15/15 applied, 0 mismatches, `git diff --stat` = 11 files, +37 −30 |
| 2026-09-24 | T4 — parent diff gate | the full `git diff` read hunk by hunk and compared against every `AFTER` block: all 15 match character for character, no collateral line changed in any of the 11 files |
| 2026-09-24 | T5 — work-unit commit | `1a75908` — 12 files, +377 −30 (the 11 edited records plus this one) |
| 2026-09-24 | T6 — independent read-only verification | `gentle-ai-verify` over the committed range: C1–C6 upheld (right PRs, right merge commits, real archive, real PR #101 delivery, correct arithmetic, no new live-PR claim, nothing frozen rewritten, 15 hunks in 11 files); C7 partial, see F2 |
| 2026-09-24 | T7 — correct the verification findings | the `docs(odd): correct this record's own evidence count and the margin figure` commit on this branch — F2 (a), (b), (c) and the `test-timeout-headroom.md:210` margin figure |
