# ODD feature: odd-status-reconciliation

**Repository**: LifeControl — documentation only (`odd/tasks/**`, plus this record). No source file is
touched and no build gate applies.
**Status**: merged — PR #162 (`docs/odd-status-reconciliation` @ `c19a5d37d`), 2026-09-24. Nothing in this slice is open; the follow-ups below are separate work, not unfinished work here.
Every fact below was verified at `bcb8d10` on 2026-09-24 and upheld by one independent verification
round; its findings, and this record's own, are closed in `## Findings`. The correction surface is **66
changed lines across 18 records** — see `## Review workload`.
**Created**: 2026-09-24
**Risk**: **low** — documentation only, no code, no contract, no auth. The risk is not the change, it is
writing an unverified claim into a record that a human will later trust; every row below is
command-verified for exactly that reason.

## Why this exists

`odd/tasks/` holds 23 feature records. On 2026-09-24 the repository has **zero open pull requests** —
all **155** PRs are `MERGED` (142) or `CLOSED` (13) — and yet 14 of those records carry a `**Status**:`
line claiming work is open, pending, uncommitted, or "not merged".

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

- `gh pr list --state all --limit 100 --json number,state,headRefName,mergedAt,mergeCommit` → **0 open**;
  this is the authoritative source for both PR state and merge-commit identity. **The `--limit 100` cap
  is a trap: it returns at most 100 rows, and the repository has 155 PRs** (142 `MERGED` + 13 `CLOSED`),
  which only a `--limit 1000` pull reveals. The first version of this record said "100 PRs" — that was
  the cap, not the count. Found by the independent verification round and corrected here: see **F1**.
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
| 12 | `purchase-order-goods-receipt.md` | one 5 770-character line (measured with `len()`); contains `**PR #128 open** … **not merged**` and a paragraph on "**Two unmerged branches were deliberately left untouched**" | `STALE_MERGED` | PR **#128** → `1b00cc33e`, merged 2026-09-21. Both "untouched" branches have since landed: `fix/docker-artifact-provenance` (`b30c8d3`) **is** an ancestor of `main` (PR #122 → `280f3556c`), and `chore/project-conventions-skill` was superseded by #131. The W2d3 tips `6be6950`/`0340481`/`3dff094` are all ancestors of `main`. **W3 remains deferred — that claim is true.** |
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

Tally: **14 misleading** (`STALE_MERGED` 11, `CONTRADICTED` 2, `IMPLIES_UNLANDED` 1), **4** with no
status line, **5 accurate**, **23** total. All 18 of the first two groups were corrected; all 5 of the
third were deliberately left alone (D1).

## Decisions

- **D1 — the correction surface is the `**Status**:` line, plus a stale checkbox when a record's only
  open item is work that already landed, plus the two body clauses the W1 contract prescribes by number
  (edits 5b and 6, where an open-work claim sat in a paragraph of its own rather than on the status
  line — see F3).** Records whose status line is already true are left
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
- **D4 — `purchase-order-goods-receipt.md` is corrected by prepending, then swapping only the false
  clauses.** Its status line is one **5 770-character** physical line carrying byte-identity trees
  (`49ee03b…`, `d52e68c0f597…`), gate counts and CI results. Those must survive, so the line is not
  rewritten: a short current-truth paragraph is prepended and exactly two false clauses are replaced in
  place. Measured: the 5 691-character narrative remainder is **byte-identical** to the original minus
  its `**Status**: ` prefix and those two clauses. This record's first estimate ("~4 000 characters")
  was wrong; `len()` on line 4 gives 5 770, and the correction above reflects the measured value.
- **D5 — the deferred and blocked items stay.** W3 (location-aware sales), D13b (the Angular conflict
  UX), T14 (the variant matrix, blocked on the unapproved `B1`) and the `lc-receiving` Keycloak
  protocol mappers are **real pending work**. Marking a feature "merged" must not erase what is
  genuinely left; finding those is the whole point of the exercise.
- **D6 — the two stale checkboxes are corrected at the source, not merely described.** `T5` of
  `project-conventions-skill.md` and `T7` of `store-claim-hardening.md` are the only unchecked boxes in
  all 23 records, and both are work that already landed. Leaving them unchecked while the status line
  says "no work left" would recreate the same self-contradiction in a new place.
- **D7 — an amendment this slice made to its own output, found while closing.** Three headers written
  earlier in this same slice asserted "No PR is open". That is precisely the claim D2 forbids, and it
  would rot the same way the 14 repaired headers did. They were amended to assert only **what remains**
  (the blocked or deferred work) — the durable half, and the only half a reader needs. Recorded rather
  than silently fixed: the invariant was applied to this slice's own text, not only to the records it
  was written to repair.

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

> **Amended in W3 by D7 — do not copy this section forward verbatim.** Three of the replacements below
> as originally prescribed (edits 5, 6 and 7) contained the clause "No PR is open". Those three headers
> were amended in `5ff16b1` to drop it, because D2 forbids asserting a live PR-open state and it would
> have rotted exactly as the 14 repaired headers did. **What follows is the W1 contract as it was
> applied at `025eadc`** — the record of what W1 did, not text to reuse.

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

**W2 (T2), the `purchase-order-goods-receipt.md` correction**, prepends a current-truth paragraph and
then replaces exactly two clauses inside the narrative line (see D4 and D7):

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

The second command was run at the W1 tip and again at the close. Its surviving hits fall into exactly
five legitimate classes: **this record's own live `**Status**:` line** (live by design until the slice is
delivered — see F2), the frozen per-slice narrative below the corrected headers (for example
`product-create-ux.md:1019`, `purchase-order-goods-receipt.md:10`), the dated evidence-log rows that
describe what was true when they were written (`product-create-ux.md:1197`/`:1234`,
`optimistic-lock-conflict.md:227`), a historical task-log row (`purchase-order-goods-receipt.md:147`,
about PR #118), and this record's own quotations of the text it replaced. **`gh pr list --state open` is
empty**, so any *new* "no PR is open"/"PR open" phrasing in a header is a defect — which is exactly how
D7 was caught.

## Review workload

**Forecast**: 18 records, one line each (two of them two-line paragraphs), plus this record. Estimated
**~70–110 changed lines of markdown across 19 files**, all one-line substitutions at a uniform position
and all carrying the same shape, so the review is a spot-check of the table above plus a scan for
surviving false phrasing — not a 19-file read. **Well under the ~400-line guardrail; no split.**

#### W1 — measured

Measured with `git diff --numstat bcb8d10 025eadc`.

| Bucket | Files | Changed lines |
|---|---|---|
| Records (T1) | 17 | **58** (34 insertions / 24 deletions) |
| This record (landed in the same commit) | 1 | **254** (254 / 0) |
| **W1 total** (`025eadc`) | **18** | **312** (288 / 24) |

#### W2 — measured

Measured with `git diff --numstat 025eadc f3006ce`.

| Bucket | Files | Changed lines |
|---|---|---|
| `purchase-order-goods-receipt.md` (T2) | 1 | **8** (7 insertions / 1 deletion); the file goes 808 → 814 lines |

#### Whole slice — measured

The **correction surface** is the 18 records, and it is final: it will not move again, because the
records are what a reviewer actually reads.

| Bucket | Files | Changed lines |
|---|---|---|
| The 18 records (the correction surface) | 18 | **66** (41 insertions / 25 deletions) — **final** |
| This record | 1 | **372** (372 / 0) at `f1f5e5a` |
| **Slice total** | **19** | **438** (413 / 25) at `f1f5e5a` |

Per commit, measured with `git diff --numstat <sha>~1 <sha>`: `025eadc` 312, `f3006ce` 8, `5ff16b1` 83,
`f1f5e5a` 111.

**The forecast was right about the records and wrong about the total.** It said ~70–110 changed lines
across 19 files; the records came in at **66** — inside the band, at its lower edge, which is what 18
uniform one-line substitutions at a fixed position should produce. The weight is the record itself, and
the forecast under-counted it because it treated the record as an afterthought rather than as the audit
trail it actually is.

**Why the record's own line count is anchored to a commit instead of stated as a live total.** Measuring
this record edits this record, so any total it prints about itself is stale the moment it is written.
This section's first two versions were both wrong for exactly that reason — the first claimed "~320
changed lines ... **well under** the guardrail", the second printed 411 while the tree already held 438.
A commit hash is stable; a present-tense total is a claim that invalidates itself. **The correction
surface — 66 changed lines across 18 records — is the only number here that is final, and it is the one
that matters.**

**The raw total exceeds the ~400-line review guardrail — 438 against ~400. Declared, not glossed.** No
split is proposed, and here is the reasoning rather than the assumption: the guardrail exists to protect
a human reviewer's attention, and the reviewer's real load is the **66 changed lines across 18
records**, all one-line substitutions at a uniform position plus two prepended paragraphs — a spot-check
against the truth table above, not an 18-file read. The other 372 lines are this record, which is the
evidence the review is checked *against*; splitting it from the correction it documents would separate
the claim from its proof and make both harder to review. For scale, this repository's other ODD records
carry comparable evidence logs (`purchase-order-goods-receipt.md` is 814 lines, and its status narrative
alone is 5 770 characters).

## Findings

Round 1 was an independent read-only `gentle-ai-verify` pass over `bcb8d10..5ff16b1`, deliberately
adversarial on the numbers: it re-derived every PR state, merge commit and date from a 155-PR pull and
re-ran `merge-base --is-ancestor` for every cited hash, rather than reading the table. It found **no
false claim in any corrected header and no wrong PR number, merge hash or date anywhere in the table** —
and it found a real error in this record.

- **F1 (MEDIUM — found by round 1, CLOSED) — the PR count was wrong: `100` was the query's limit, not the
  repository's total.** `gh pr list --state all --limit 100` returns at most 100 rows, and the repository
  has **155** PRs (142 `MERGED` + 13 `CLOSED`). This record's headline and its evidence bullet both said
  "100 PRs". The load-bearing claim — **zero open** — is true, and the corrected number makes it
  stronger, but the sentence as written was false. Corrected in three places, and the `--limit` trap is
  now recorded at the evidence bullet, where the next reader will hit it.
  **The lesson: a truncated query result is indistinguishable from a complete one unless the cap is
  compared against the true total.**
- **F2 (LOW — CLOSED) — the surviving-hits class list omitted this record's own status line.** The
  verification plan claimed four legitimate classes for the sweep's survivors; this record's own
  `**Status**: in progress` line is a fifth, and is live by design until the slice is delivered.
- **F3 (INFO — CLOSED) — the scope claim was imprecise.** D1 described the correction surface as the
  status line plus a stale checkbox, but two hunks land on body paragraphs (edits 5b and 6: the S5
  closing clause, and `product-variant-admin-ui.md`'s "Nothing is pushed" paragraph — cases where the
  open-work claim sat in a paragraph of its own rather than on the status line). D1 now names them by
  contract number.
- **F4 (INFO — CLOSED, and a real trap) — the printed W1 contract would reintroduce the phrasing D7
  removed.** That section quotes the replacements as applied at `025eadc`, and edits 5, 6 and 7 all
  contained "No PR is open", which D7 deleted in `5ff16b1`. The section now carries an explicit
  amendment warning: it is the record of what W1 did, not text to copy forward.
- **F5 (INFO — CLOSED) — one citation was misclassified.** `product-create-ux.md:1234` was listed as
  "frozen per-slice narrative"; it is a dated evidence-log row. Same verdict either way; the class list
  now names the right one.
- **F6 (LOW — found by the parent while applying F1–F5, CLOSED) — the workload claim was an estimate
  presented as a measurement, and it was wrong.** This record asserted the slice was "19 files and ~320
  changed lines ... **well under** the ~400-line guardrail". Measured: **411** changed lines (386/25).
  The estimate was made before the findings round existed and was never re-run, which is exactly how a
  number goes stale inside the document that exists to stop numbers from going stale. Corrected to the
  measured table above, with the guardrail breach **declared and reasoned** rather than rounded away.
  **This repeats, in this very slice, the lesson the `optimistic-lock-conflict` record already paid for:
  report line counts from `git diff --numstat`, never from an estimate. A claim about the size of a
  change is still a claim, and it needs the same command as every other one.** The fix then needed a
  second pass for the same reason: the number F6 introduced (411) was itself stale one edit later,
  because writing the correction adds lines to the file that holds it. `## Review workload` now anchors
to a commit hash instead of a running total.

## Evidence log

| Date | Slice | Commit | Evidence |
|---|---|---|---|
| 2026-09-24 | recon | — | Read-only `gentle-ai-explore` scout over all 23 records — which returned **no shell** (its own report: no `bash`, `git` or `gh`), so its table was built from `.git` internals (`refs/`, `packed-refs`, `logs/HEAD`, `FETCH_HEAD`) and is treated here as **hypothesis, not evidence**. It was then re-verified with real commands by the parent: `gh pr list --state all --limit 100 --json number,state,headRefName,mergedAt,mergeCommit` (**0 open**; the capped pull returned 100 rows — the true total is 155, see **F1**) plus `git merge-base --is-ancestor <sha> main` for every cited hash. The scout was right on substance and wrong on method; the `gh` data is what settled it. |
| 2026-09-24 | recon (settled special cases) | — | `chore/project-conventions-skill` **#129 = CLOSED**, and the skill landed via **#131** `34fbe4cb4` (+#130 `3aabfd0f3`, #132 `3c990062a`); `cec4578` is **not** an ancestor of `main` (`git merge-base --is-ancestor` → no) while the objects still exist. `fix/docker-artifact-provenance` `b30c8d3` **is** an ancestor of `main` (PR #122). `bcb8d10` parents = `964e826` + `3cb507b`; `odd/tasks/optimistic-lock-conflict.md` is absent at `964e826` (`git ls-tree`) and added by the merge. |
| 2026-09-24 | **W1 (T1)** | `025eadc` | 18 files, **288 insertions / 24 deletions** (17 records = 34/24; this record = 254/0). The 19 prescribed edits were delegated to a `gentle-ai-worker` with the exact replacement text and one allowed-edit-surface entry per file; **nothing was left to the writer's judgement**. Its report confirms all 19 matched the prescribed text exactly, none failed, and it named 6 further stale passages it did *not* touch (now in Follow-ups). The first delegation attempt was **rejected before launching** by the writer contract — the task lacked the canonical `## Allowed edit surfaces` heading — and was relaunched with it; no work was lost and nothing ran twice. **Gate**: every replacement re-read in `git diff` by the parent, and the `grep` sweep above plus a re-extraction of all 23 status lines. |
| 2026-09-24 | **W2 (T2)** | `f3006ce` | 1 file, **7 insertions / 1 deletion** (808 → 814 lines). **Gate**: a scripted proof that the 5 691-character narrative remainder equals the original status line minus its prefix and minus the two replaced clauses — printed `True`, lengths 5691 == 5691, i.e. the byte-identity trees and gate counts survive byte for byte (D4). |
| 2026-09-24 | **W3 close** | `5ff16b1` | The D4 estimate corrected from ~4 000 to the measured 5 770 characters; the table tally corrected from `STALE_MERGED` 12 to **11**; the measured workload filled in against the forecast; **D7 recorded** together with the three headers it amends. |
| 2026-09-24 | **W4 findings round** | `25b2bb4` | Independent read-only `gentle-ai-verify` over `bcb8d10..5ff16b1`, instructed to be adversarial on the numbers. **Upheld**: all 23 table rows (every PR state, merge commit and date re-derived from a 155-PR pull; every cited hash re-checked with `merge-base --is-ancestor`); the tally (`STALE_MERGED` 11 + `CONTRADICTED` 2 + `IMPLIES_UNLANDED` 1 + `NO_STATUS` 4 + `ACCURATE` 5 = 23); no new false claim in any corrected header; the five untouched records really are accurate and really are unmodified; **D4's byte-identity claim reproduced exactly** (5 770 → 5 691, equal); and the two-checkbox claim verified in both directions (exactly T5 and T7 before, zero live unchecked boxes after). **Findings F1–F5, all closed above**: F1 was a real error in this record's own headline. **F6 is the parent's own, found while closing F1–F5**: the workload claim was an unmeasured estimate and false (see `## Findings`). |
| 2026-09-24 | **delivery** | `c19a5d37d` | PR **#162** merged into `main` as a real two-parent merge commit (`bcb8d10` + `d8b203d`), and the four workflows on `main` went green (Docker Build Integrity, API CI, Angular CI, Gateway CI). Three of this slice's own headers had already been amended by **D7** so they would not rot; this record's status line was the exception, and it rotted exactly as predicted — it still read "`in progress` ... committed on `docs/odd-status-reconciliation`" after the branch it named had been merged and deleted. Corrected in this follow-up commit, per **D2**: durable state plus evidence, no live PR-open claim. |

## Follow-ups

- **Stale prose the W1 writer flagged and did not touch** (out of scope by D1): `product-variant-identity-split.md`'s
  `## Plan` table still marks S2/S3 `pending` while the header and task log say complete;
  `store-zones-backend.md:126` still reads as current ("the commit is deferred until after this round");
  `store-areas-frontend.md:173-175` claims an uncommitted purchase-orders refactor still needs its own
  commit or discard decision (probably settled by PR #101, **not verified**); `docker-images-rebuild.md:166`'s
  cross-reference to `project-conventions-skill` T2–T5 pending is now stale, since T5 was closed by this
  slice; `optimistic-lock-conflict.md:227`'s dated evidence row still describes the header as recording
  the open PR.
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
