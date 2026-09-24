# ODD feature: odd-status-reconciliation

**Repository**: LifeControl — documentation only (`odd/tasks/**`, plus this record). No source file is
touched and no build gate applies.
**Status**: in progress — plan, verified reconciliation table and decisions recorded at `bcb8d10`. The
work units below are not yet committed.
**Created**: 2026-09-24
**Risk**: **low** — documentation only, no code, no contract, no auth. The risk is not the change, it is
writing an unverified claim into a record that a human will later trust; every row below is
command-verified for exactly that reason.

## Why this exists

`odd/tasks/` holds 23 feature records. On 2026-09-24 the repository has **zero open pull requests** —
all 100 PRs are `MERGED` or `CLOSED` — and yet 14 of those records carry a `**Status**:` line claiming
work is open, pending, uncommitted, or "not merged".

The `**Status**:` line is the only thing a reader uses to decide whether a record holds live work. It
is therefore the highest-leverage line in the file, and it was wrong in 14 of 23 records plus 4 records
with no line at all.

**Root cause (structural, not carelessness).** `.gitignore` carries `odd/*` with `!odd/tasks/`, so a
feature record is versioned *and travels with its branch*. Its status line is written while the branch
is open, describing work that has not landed. Merging the PR is the last thing that ever touches the
file, and nothing revisits the header afterwards. The header is therefore *guaranteed* to be stale the
moment the feature lands — the drift is designed in, not accidental. This was measured directly: this
repository's own `optimistic-lock-conflict.md` was corrected to record "PR #161 is open … not merged"
hours before #161 merged, making the line false again.

## Verified truth (read-only, at `main` @ `bcb8d10`)

Every row was established with a command, never inferred from the record's prose. Primary evidence:

- `gh pr list --state all --limit 100 --json number,state,headRefName,mergedAt,mergeCommit` → **100 PRs,
  0 open**; this is the authoritative source for both PR state and merge-commit identity.
- `git merge-base --is-ancestor <sha> main` → confirms each cited commit is actually on `main`, and was
  run for every hash named below.
- `git cat-file -e <sha>^{commit}` → separates "on `main`" from "object gone".

| # | File | `**Status**:` line as it stands | Verdict | Truth + evidence |
|---|---|---|---|---|
| 1 | `angular-build-hygiene.md` | *(none)* | `NO_STATUS` | Landed. PR **#133** → `28c077175`, merged 2026-09-21. |
| 2 | `angular-docs-prettier-scope.md` | *(none)* | `NO_STATUS` | Landed. PR **#127** → `f576c3460`, merged 2026-09-20. |
| 3 | `docker-images-rebuild.md` | `in progress` | `CONTRADICTED` | The file contradicts itself: its own `## Status` section (line 96) says "**Done. The three dev images are rebuilt from `main` (`a72de5f`) and the dev stack runs on them**", and its task log is 6/6 checked. Dev-only operation: no branch, no PR, nothing to merge. The top-level header was never updated. |
| 4 | `gateway-route-coverage.md` | `in progress` | `STALE_MERGED` | PR **#152** → `d7317f659`, merged 2026-09-22. Task log 4/4. |
| 5 | `optimistic-lock-conflict.md` | `delivered on branch … **PR #161 is open** … **not merged**` | `STALE_MERGED` | PR **#161** → `bcb8d10db`, merged 2026-09-24T03:34:03Z. `bcb8d10`'s parents are `964e826` + `3cb507b`; the record file did **not** exist at `964e826` (`git ls-tree`) and was added by this merge. |
| 6 | `otlp-tracing-migration.md` | `delivered — T1-T8 verified; code committed on \`fix/otlp-tracing-migration\`` | `IMPLIES_UNLANDED` | PR **#153** → `80f44c39a`, merged 2026-09-23. Not factually false, but "code committed on branch X" reads as unlanded work while X is merged and deleted. Task log 8/8. |
| 7 | `product-create-ux.md` | multi-section block; the S5 paragraph ends `**Nothing is pushed and no PR is open**` | `STALE_MERGED` | **All five slices merged**: S1 #154 → `158a6b3f9`, S2a #155 → `d85e9e2d7`, S2b #156 → `d7c6e16df`, S3 #157 → `a98426e32`, S4 #159 → `84a17ef64`, S5 #160 → `964e826f5`. The S5 guard is on `main` (`products.routes.ts`, `pages/product-variant-list-host/`). |
| 8 | `product-variant-admin-ui.md` | `S1 delivered … S2 complete (T9–T15) on \`feat/variant-store-stock-ui\` … **Nothing is pushed and no PR is open.**` | `STALE_MERGED` | The four split units all merged 2026-09-22: #146 → `14e271657`, #147 → `5c95d0d56`, #148 → `0450b929e`, #149 → `3bc5555df`. Task log 16/16. |
| 9 | `product-variant-identity-split.md` | `done — S1, S2 and S3 complete; PR pending (user-owned)` | `STALE_MERGED` | PR **#134** → `45d237bf2`, merged 2026-09-21; the `V13`/`V14` migrations are on `main`. Task log 33/33. |
| 10 | `product-write-authz.md` | *(none)* | `NO_STATUS` | Landed. PR **#137** → `ac73118f0`, merged 2026-09-22. Task log 4/4. |
| 11 | `project-conventions-skill.md` | `delivered, uncommitted (T5 awaits explicit user authorization)` | `CONTRADICTED` | **The work is committed and on `main`.** The record's own PR **#129** is `CLOSED` without merging and its commit `cec4578` is **not** an ancestor of `main` (`git merge-base --is-ancestor` → no). The skill reached `main` through PR **#131** → `34fbe4cb4` (`chore/project-conventions-rescue`), followed by #130 → `3aabfd0f3` and #132 → `3c990062a`. Task log 4/5, with **T5 stale**. |
| 12 | `purchase-order-goods-receipt.md` | one ~4 000-character line; contains `**PR #128 open** … **not merged**` and a paragraph on "**Two unmerged branches were deliberately left untouched**" | `STALE_MERGED` | PR **#128** → `1b00cc33e`, merged 2026-09-21. Both "untouched" branches have since landed: `fix/docker-artifact-provenance` (`b30c8d3`) **is** an ancestor of `main` (PR #122 → `280f3556c`), and `chore/project-conventions-skill` was superseded by #131. The W2d3 tips `6be6950`/`0340481`/`3dff094` are all ancestors of `main`. **W3 remains deferred — that claim is true.** |
| 13 | `purchase-order-sections-polish.md` | `in progress` | `STALE_MERGED` | PR **#101** → `f8a29f8f0`, merged 2026-09-17. The record's own `## Status` section already says "Shipped", contradicting its header. Task log 5/5. |
| 14 | `store-areas-backend.md` | `merged` | `ACCURATE` | PR #98 → `9135fa944`. |
| 15 | `store-areas-frontend.md` | `in progress` | `STALE_MERGED` | PRs **#99** → `fd71cd917` and **#100** → `8ebb11bae`, merged 2026-09-17. Task log 13/13. |
| 16 | `store-claim-hardening.md` | *(none)* | `NO_STATUS` | Landed. PR **#136** → `7958dd052`, merged 2026-09-21. Task log 12/13, with **T7 stale**. |
| 17 | `store-contract-hardening.md` | `delivered — PR #114 (\`feat/store-contract-hardening\` @ \`dff6626\`), based on \`main\` @ \`c0cb1f5\`` | `ACCURATE` | PR #114 → `6240844f0`; `dff6626` is that branch's tip and is an ancestor of `main`. |
| 18 | `store-locations-backend.md` | `in progress` | `STALE_MERGED` | PR **#108** → `dd894a314`, merged 2026-09-18. Task log 17/17. |
| 19 | `store-locations-frontend.md` | `delivered — the chain is merged into \`main\` @ \`c0cb1f5\` … Nothing open.` | `ACCURATE` | PRs #109–#112; `c0cb1f5` is on `main`. |
| 20 | `store-p0-defects.md` | `delivered — PR #113 (\`feat/store-p0-defects\` @ \`73113c3\`), based on \`main\` @ \`c0cb1f5\`` | `ACCURATE` | PR #113 → `d2a661628`; `73113c3` is that branch's tip, on `main`. |
| 21 | `store-ux-a11y.md` | `delivered — PR #116 merged into \`main\` (\`516348a\`, 2026-09-19)` | `ACCURATE` | PR #116 → `516348a83`. |
| 22 | `store-zones-backend.md` | `in progress` | `STALE_MERGED` | PR **#103** → `2c21fd57e`, merged 2026-09-18. Task log 20/20. |
| 23 | `store-zones-frontend.md` | `in progress` | `STALE_MERGED` | PRs **#104** → `0058c3ce4`, **#105** → `8281e04f0`, **#106** → `56bbf8d95`, **#107** → `19faa3e56`, merged 2026-09-18. |

Tally: **14 misleading** (`STALE_MERGED` 12, `CONTRADICTED` 2, plus `IMPLIES_UNLANDED` 1 — 15 rows
touched), **4** with no status line, **5 accurate**, **23** total.

## Decisions

- **D1 — the correction surface is the `**Status**:` line, plus a stale checkbox when a record's only
  open item is work that already landed.** Records whose status line is already true are left
  untouched, deliberately: "untouched" is a decision recorded here, not an oversight. Other false
  claims inside the same header blocks (for example `angular-docs-prettier-scope.md` still calling its
  own doc "gitignored", which `#130` fixed) are **out of scope** and listed under Follow-ups.
- **D2 — the header invariant: the `**Status**:` line states durable state plus its evidence (PR
  number, merge commit, date) and never asserts a live PR-open state.** Three reasons. (a) A line that
  asserts "no PR is open" is falsified by the next merge, which is exactly the drift being repaired.
  (b) Frozen per-slice narrative belongs in the sections below, not in the header; the repository's own
  records already use that convention — `product-create-ux.md` writes "this line is the correction" and
  keeps its older paragraphs as the record of what was true when they were written. (c) A dated claim
  with a commit hash is checkable by the reader with one command.
- **D3 — the four records with no status line get one, inserted as the first metadata line after the H1.**
  An absent status line is the same defect as a wrong one: the reader cannot tell whether the record
  holds live work. A uniform insertion point keeps the diff mechanical and reviewable.
- **D4 — `purchase-order-goods-receipt.md` is corrected surgically, not restructured.** Its status line
  is one ~4 000-character physical line carrying byte-identity trees (`49ee03b…`, `d52e68c0f597…`), gate
  counts and CI results. That evidence must survive; the false clauses are replaced in place and nothing
  else in the line is reflowed. A rewrite would destroy the audit trail this record exists to hold.
- **D5 — the deferred and blocked items stay.** W3 (location-aware sales), D13b (the Angular conflict
  UX), T14 (the variant matrix, blocked on the unapproved `B1`) and the `lc-receiving` Keycloak
  protocol mappers are **real pending work**. Marking a feature "merged" must not erase what is
  genuinely left; finding those is the whole point of the exercise.
- **D6 — the two stale checkboxes are corrected at the source, not merely described.** `T5` of
  `project-conventions-skill.md` and `T7` of `store-claim-hardening.md` are the only unchecked boxes in
  all 23 records, and both are work that already landed. Leaving them unchecked while the status line
  says "no work left" would recreate the same self-contradiction in a new place.

## Scope

**In**: the `**Status**:` line of 18 records (14 corrected, 4 added), and the 2 stale checkboxes named in
D6. This record.

**Out, explicitly**: the frozen per-slice sections below each header; every other false header-block
claim (Follow-ups); the 5 accurate records; any `.agents/**` or convention change; any source file; any
checkbox in the 21 records that have none unchecked.

## Task list

| Id | Task | Evidence |
|----|------|----------|
| **T1** | Correct the 17 mechanical `**Status**:` lines prescribed below (13 corrected, 4 inserted) and the 2 stale checkboxes | `git diff` on 17 files + a re-run of the status-line extraction |
| **T2** | Surgical correction of the `purchase-order-goods-receipt.md` status line (D4) | `git diff --numstat` proving the line count of the file is unchanged |
| **T3** | Close: this record's status, evidence log and measured workload | the commits + gate output |

**Work units**: **W1** = T1; **W2** = T2; **W3** = T3. W1 and W2 are split because the
`purchase-order-goods-receipt.md` line needs a different technique (in-place clause replacement inside a
single 4 000-character line, with the surrounding evidence preserved) and a different review muscle than
17 uniform one-line substitutions.

## The exact replacements (W1 contract)

Apply verbatim. Do not reflow, rewrap or re-word any other line; do not touch anything not listed here.

**Corrections (13).**

1. `docker-images-rebuild.md:4` — replace `**Status**: in progress` with
   `**Status**: done — the three dev images were rebuilt and the dev stack runs on them; dev-only, so there is no branch and no PR. The record's own \`## Status\` section says the same. No work left.`
2. `gateway-route-coverage.md:4` — replace `**Status**: in progress` with
   `**Status**: merged — PR #152 (\`fix/gateway-route-coverage\` @ \`d7317f659\`), 2026-09-22. No work left.`
3. `optimistic-lock-conflict.md:5-9` — replace the five-line status block with
   `**Status**: merged — PR #161 (\`fix/optimistic-lock-conflict\` @ \`bcb8d10db\`), 2026-09-24. W1 (T1–T4) is on`<br>`\`main\`; the Angular half (D13b) is still deferred; see \`## Handoff\`. No work left in this record.`
4. `otlp-tracing-migration.md:5` — replace `delivered — T1-T8 verified; code committed on \`fix/otlp-tracing-migration\`` with
   `merged — PR #153 (\`fix/otlp-tracing-migration\` @ \`80f44c39a\`), 2026-09-23; T1–T8 verified. No work left.`
5. `product-create-ux.md` — two edits. (a) Replace the two-line prefix
   `**Status**: **S1, S2a and S2b are merged into \`main\`** — S1 in PR #154, S2a in PR #155, S2b in` / `PR #156, the last two as merge commits …` — with the new leading paragraph prescribed below,
   immediately followed by the original text unchanged. (b) Replace the S5 closing clause
   `**Nothing is pushed and no PR is open**: that stays the` / `user's decision.` with
   `**S5 is merged** as PR #160 (\`964e826f5\`), 2026-09-24.`
6. `product-variant-admin-ui.md:12-14` — replace the three-line block beginning `**Nothing is pushed and no PR is open.**` with the paragraph prescribed below.
7. `product-variant-identity-split.md:6` — replace `done — S1, S2 and S3 complete; PR pending (user-owned)` with
   `merged — PR #134 (\`refactor/product-variant-identity-backend\` @ \`45d237bf2\`), 2026-09-21; S1, S2 and S3 complete. No PR is open and no work is left.`
8. `project-conventions-skill.md:5` — replace `delivered, uncommitted (T5 awaits explicit user authorization)` with the paragraph prescribed below.
9. `project-conventions-skill.md:84` — replace `- [ ] T5 — Work-unit commit on a branch cut from \`main\` (needs explicit user authorization)` with
   `- [x] T5 — Work-unit commit and PR: landed via PRs #130/#131/#132; this record's own PR #129 was closed without merging.`
10. `purchase-order-sections-polish.md:4` — replace `**Status**: in progress` with
    `**Status**: merged — PR #101 (\`refactor/purchase-order-section-details\` @ \`f8a29f8f0\`), 2026-09-17. No work left.`
11. `store-areas-frontend.md:5` — replace `**Status**: in progress` with
    `**Status**: merged — PR #99 (\`feat/store-areas-flat-lookup\` @ \`fd71cd917\`) and PR #100 (\`feat/store-areas-ui\` @ \`8ebb11bae\`), 2026-09-17. No work left.`
12. `store-locations-backend.md:4` — replace `**Status**: in progress` with
    `**Status**: merged — PR #108 (\`feat/store-locations-backend\` @ \`dd894a314\`), 2026-09-18. No work left.`
13. `store-zones-backend.md:4` — replace `**Status**: in progress` with
    `**Status**: merged — PR #103 (\`feat/store-zones-backend\` @ \`2c21fd57e\`), 2026-09-18. No work left.`
14. `store-zones-frontend.md:4` — replace `**Status**: in progress` with
    `**Status**: merged — PRs #104 (\`0058c3ce4\`), #105 (\`8281e04f0\`), #106 (\`56bbf8d95\`) and #107 (\`19faa3e56\`), 2026-09-18. No work left.`
15. `store-claim-hardening.md:164-165` — replace the two-line `- [ ] **T7 …` item with
    `- [x] **T7 — Work-unit commit and PR**: landed as PR #136 (\`7958dd052\`), 2026-09-21.`

**Insertions (4)** — add as the first metadata line after the H1 title and its blank line.

16. `angular-build-hygiene.md` →
    `**Status**: merged — PR #133 (\`chore/angular-build-hygiene\` @ \`28c077175\`), 2026-09-21. No work left.`
17. `angular-docs-prettier-scope.md` →
    `**Status**: merged — PR #127 (\`chore/angular-docs-prettier-scope\` @ \`f576c3460\`), 2026-09-20. No work left.`
18. `product-write-authz.md` →
    `**Status**: merged — PR #137 (\`fix/product-write-authz\` @ \`ac73118f0\`), 2026-09-22. No work left.`
19. `store-claim-hardening.md` →
    `**Status**: merged — PR #136 (\`fix/store-claim-hardening\` @ \`7958dd052\`), 2026-09-21. No work left.`

**The two prescribed multi-line paragraphs.**

For `product-create-ux.md` (edit 5a), inserted before the existing `**S1, S2a and S2b are merged into
\`main\`** …` text:

> **Status**: **All five slices are merged into `main`** — S1 #154 (`158a6b3f9`), S2a #155 (`d85e9e2d7`),
> S2b #156 (`d7c6e16df`), S3 #157 (`a98426e32`), S4 #159 (`84a17ef64`) and S5 #160 (`964e826f5`) —
> verified at `bcb8d10` on 2026-09-24. **No PR is open, and the only work left in this feature is T14**,
> blocked on the unapproved `B1` (D30). The per-slice paragraphs below are kept as the record of what was
> true when they were written; this paragraph is the correction.

For `project-conventions-skill.md` (edit 8):

> **Status**: landed — PR #131 (`chore/project-conventions-rescue` @ `34fbe4cb4`) brought the skill to
> `main` on 2026-09-21, with the ODD-document versioning and the worktree convention following in #130
> (`3aabfd0f3`) and #132 (`3c990062a`). This record's own PR #129 was **closed without merging** and its
> commit `cec4578` never reached `main`. **T5 is stale and is closed by this correction.** No work left.

For `product-variant-admin-ui.md` (edit 6):

> **S2 is merged** as the four units the split proposed — `feat/variant-store-scope` #146 (`14e271657`),
> `test/variant-store-write-it` #147 (`5c95d0d56`), `feat/variant-store-stock-editor` #148 (`0450b929e`)
> and `feat/variant-store-sales-entry` #149 (`3bc5555df`), 2026-09-22 — verified at `bcb8d10` on
> 2026-09-24. The slice measured **3490 changed lines against a declared forecast of 500–800**; see
> `## Review workload`. No PR is open and no work is left in this feature.

**W2 (T2), the surgical `purchase-order-goods-receipt.md` correction**, replaces exactly two clauses
inside the single status line:

- `**PR #128 open**` … `— **not merged**, the user's decision.` → `**PR #128 MERGED** as \`1b00cc33e\` on 2026-09-21.`
- the `**Two unmerged branches were deliberately left untouched**` clause → both have since landed:
  `fix/docker-artifact-provenance` (`b30c8d3`) is an ancestor of `main` via PR #122 (`280f3556c`), and
  `chore/project-conventions-skill` was superseded by PR #131. **W3 stays deferred.**

## Verification plan

No build gate applies (documentation only). The check that matters is that **no false claim survives**:

```bash
for f in odd/tasks/*.md; do awk '/^\*\*Status\*\*/{p=1} p{print FILENAME": "$0; exit}' "$f"; done
grep -rn 'PR #[0-9]* open\|not merged\|Nothing is pushed\|no PR is open\|in progress' odd/tasks/
git diff --numstat
```

The second command must return only records that are genuinely still open, or nothing. `gh pr list
--state open` must be empty, so **any** surviving "no PR is open"/"PR open" phrasing is either a dated
historical sentence in a frozen section or a defect.

## Review workload

**Forecast**: 18 records, one line each (two of them two-line paragraphs), plus this record. Estimated
**~70–110 changed lines of markdown across 19 files**, all one-line substitutions at a uniform position
and all carrying the same shape, so the review is a spot-check of the table above plus a scan for
surviving false phrasing — not a 19-file read. **Well under the ~400-line guardrail; no split.**

#### W1 — measured

| Bucket | Files | Changed lines |
|---|---|---|
| Records (T1) | 17 | *to fill at the W1 commit* |
| This record | 1 | *to fill* |

## Evidence log

| Date | Slice | Commit | Evidence |
|---|---|---|---|
| 2026-09-24 | recon | — | Read-only `gentle-ai-explore` scout over all 23 records — which returned **no shell** (its own report: no `bash`, `git` or `gh`), so its table was built from `.git` internals (`refs/`, `packed-refs`, `logs/HEAD`, `FETCH_HEAD`) and is treated here as **hypothesis, not evidence**. It was then re-verified with real commands by the parent: `gh pr list --state all --limit 100 --json number,state,headRefName,mergedAt,mergeCommit` (100 PRs, **0 open**) plus `git merge-base --is-ancestor <sha> main` for every cited hash. The scout was right on substance and wrong on method; the `gh` data is what settled it. |
| 2026-09-24 | recon (settled special cases) | — | `chore/project-conventions-skill` **#129 = CLOSED**, and the skill landed via **#131** `34fbe4cb4` (+#130 `3aabfd0f3`, #132 `3c990062a`); `cec4578` is **not** an ancestor of `main` (`git merge-base --is-ancestor` → no) while the objects still exist. `fix/docker-artifact-provenance` `b30c8d3` **is** an ancestor of `main` (PR #122). `bcb8d10` parents = `964e826` + `3cb507b`; `odd/tasks/optimistic-lock-conflict.md` is absent at `964e826` (`git ls-tree`) and added by the merge. |

## Follow-ups

- **Other false prose inside the header blocks** — out of scope by D1. Known: `angular-docs-prettier-scope.md`
  calls its own record "gitignored" (`#130` versioned `odd/tasks/`), and several records pin `Base:
  main @ <old sha>` values that no longer describe anything. A second pass could reconcile them; it was
  deliberately not bundled into a header-correction PR.
- **The real fix is preventive.** This feature repairs 14 headers; the next merge will create more,
  because the root cause is structural (the header is written pre-merge and never revisited). The
  durable options are (a) state the invariant in
  `.agents/skills/project-conventions/references/` as a documented practice, (b) have the review/delivery
  step own the header update, or (c) drop live-state claims from the header entirely and let the
  evidence log carry dated facts. Not decided here — recorded so it is not lost. This is a convention
  change and belongs in its own slice with the user's sign-off.
- **`docker/scripts/_common.sh` `verify_artifact_freshness`** still compares mtimes and still
  false-positives after a branch checkout; `deploy.sh` has no force flag. Unrelated to this feature,
  already recorded, not touched.
