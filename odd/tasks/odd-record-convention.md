# ODD feature: odd-record-convention

**Status**: implemented — the header invariant is now documented in the root `AGENTS.md` and in
`.agents/skills/project-conventions/references/feature-records.md`, and enforced by nothing. This
header makes no claim about push or PR state; see the evidence log.
**Created**: 2026-09-24
**Branch**: `docs/odd-record-convention` · **Base**: `main` @ `c49d296` · **Worktree**:
`~/workspace/LifeControl-worktrees/docs-odd-record-convention` (herdr `w16`)
**Risk**: **low.** Two documentation files change: the root `AGENTS.md` (a new section) and the
`project-conventions` skill (one new reference, one hard rule, one index row). No code, no test, no
CI, no build config. The risk is not breakage, it is writing a convention that is wrong or that
contradicts the one it inherits.
**Requested by**: the user, after D2 was shown and the question "why does the drift keep coming back"
was answered with "the rule lives in a record nobody reads, and nothing enforces it".

## Why this exists

`odd/tasks/odd-status-reconciliation.md` D2 fixed the rule for ODD headers and PR #162 applied it to
14 records. The next cycle produced four new headers with the exact forbidden claim, and the repair
that owns those four is in flight as this is written. This slice makes no claim about that repair's
state (D2 applied to itself).

Two things were measured while answering "how do we fix the cause":

| Half of the cause | Evidence |
| --- | --- |
| **The rule is not where an agent reads it.** | `grep -niE "odd\|feature record" AGENTS.md` → **0 hits** in 245 lines. `grep -rn "odd/\|feature record" .agents/skills/project-conventions/` → **0 hits**. D2 exists only inside a record that nobody opens unless they are already looking for it. |
| **Nothing enforces it.** | No workflow's `paths:` filter lists `odd/**` or `*.md`, and each workflow's `paths:` is nested under `pull_request` only — so a records-only change runs **no** automated check. The four workflows do run on `push: branches: [main]`, i.e. after the merge. |

The fix therefore moves the rule into the channel that is injected unconditionally (`AGENTS.md`,
read by every Pi session) and into the skill that is triggered by convention work
(`references/feature-records.md`), and it does **not** add a guard.

## Decisions

- **D1 — fix the text, not the detection.** The invariant is promoted to the two places an agent
  actually reads. A guard would detect the drift *after* the merge; making the header shape carry
  no live-state field prevents it *before*.
- **D2 — the invariant is inherited, not invented.** It is `odd-status-reconciliation.md` D2,
  verbatim in substance: *"the `**Status**:` line states durable state plus its evidence (PR number,
  merge commit, date) and never asserts a live PR-open state."* This slice does not change the rule;
  it changes where the rule lives.
- **D3 — no guard and no workflow.** Three reasons. (a) It removes the property that goes stale
  instead of watching it. (b) A guard would need a stated allowlist for records that legitimately
  contain the forbidden phrases as **quotations** (`odd-status-reconciliation.md` quotes the text it
  replaced; `purchase-order-goods-receipt.md:10` is the 5 770-character narrative line that its own
  D4 protects), and it would fire on a merged tree rather than on the PR. (c) The repository's one
  existing convention guard (`breakpoints.spec.ts`) needed two holes patched after independent
  verification; a guard over prose has a strictly harder false-positive surface. If enforcement is
  wanted later, it is added on top of a convention that no longer has special cases.
- **D4 — terminal state with evidence stays permitted.** `merged — PR #N (<branch> @ <commit>),
  <date>` is true forever and is checkable with one command. What was never true forever is the
  *pending* claim. The forbidden set is the live state, not the word "merged".
- **D5 — existing records are not rewritten.** Their frozen narrative is itself evidence. The four
  stale headers belong to the repair PR that owns them (`odd-header-hygiene`), which merges
  separately; this slice makes no claim about that PR's state (D2 applied to itself).
- **D6 — language follows each artifact.** Spanish in the root `AGENTS.md` (it is written in
  Spanish), English in the skill (every reference there is English).

## The edit contract (verbatim; apply exactly, do not restyle)

Four insertions across four files. No other line in any file may change. `I1` replaces an existing
block (the only edit that is not a pure insertion); `I2` creates a file; `I3` and `I4` each add
lines.

### I1 — `AGENTS.md`: new section between "Trabajo en paralelo con worktrees" and "Project Overview"

```text
BEFORE
> **`herdr workspace close --group` cierra el workspace primario y todos los worktrees linkeados.** No lo
> uses para saltear un error de cierre.

---

## Project Overview
```

```text
AFTER
> **`herdr workspace close --group` cierra el workspace primario y todos los worktrees linkeados.** No lo
> uses para saltear un error de cierre.

---

## Registros ODD (`odd/tasks/`)

Cada unidad de trabajo sustancial deja un registro versionado en [`odd/tasks/`](odd/tasks/). El detalle
completo, con la forma exacta del header, está en
[`references/feature-records.md`](.agents/skills/project-conventions/references/feature-records.md).

| Regla | Valor |
|-------|-------|
| Qué lleva el header | Estado **terminal** con su evidencia (PR, merge commit, fecha) o **qué trabajo queda** (diferido o bloqueado, con la razón) |
| Qué no lleva nunca | Estado vivo o pendiente: "sin pushear", "no hay PR abierto", "en progreso", "esperando review", "sin mergear" |
| Dónde va la entrega | En el log de evidencia fechado del propio registro, con fecha, PR y commit |
| Por qué | El header se escribe **antes** del merge y nadie lo revisita: un estado vivo queda falsificado por el próximo merge, sin que ningún commit toque la frase que mintió |
| Enforcement | Ninguno automático: es una convención de escritura. Los registros anteriores a esta regla no se reescriben, porque su narrativa congelada es en sí misma evidencia |

---

## Project Overview
```

### I2 — `.agents/skills/project-conventions/references/feature-records.md` (new file)

````text
INSERT AS THE WHOLE FILE
# ODD Feature Records

Read this before creating or updating a record under `odd/tasks/`, and before writing any
`**Status**:` line.

## The failure mode

A record's header is written **before** the merge it describes, and nothing revisits the file
afterwards. A header that asserts a *live* delivery state — "Not pushed; no PR is open" — is
therefore falsified by the next merge, silently, and by a commit that never touches the file that
made the claim.

This repository has reproduced it in three consecutive generations:

| Where | What happened |
| --- | --- |
| PR #162 (`odd-status-reconciliation`) | Repaired **14** headers that asserted a live or pending delivery state. Three of them had to be amended again **inside the same slice**, because the slice's own prescribed replacements still carried "No PR is open" (its decision D7). |
| PRs #164–#167 | The very next cycle wrote **four** fresh headers with "Not pushed; no PR is open" — the exact claim the previous slice had just deleted from 14 others. The last two PRs of that same cycle (#168, #169) wrote neutral headers on their own initiative, with no rule telling them to. |
| The repair in flight when this was written | Removes those four claims, plus body lines from earlier slices that had drifted the same way. This table deliberately does not assert that repair's state, or its number. |

The pattern is structural, not careless: the header is written while the delivery state is still
*unknown* (pre-merge) and is never revisited when it becomes *known* (post-merge). Removing the
claim is cheaper than scheduling a revisit, and the revisit has already failed twice.

## The invariant

| # | Rule | Why |
| --- | --- | --- |
| 1 | The `**Status**:` line states **durable state plus its evidence** (PR number, merge commit, date) or **what remains** (deferred / blocked, with the reason). | Both halves stay true: a merge commit and a deferral decision do not change when the branch is deleted. |
| 2 | It **never** asserts a live or pending delivery state. | A live claim is falsified by the next merge — silently, with no commit touching the file that made it. |
| 3 | Delivery detail belongs in the record's **dated evidence log** (`## Evidence log`, task log), not in the header. | A dated row with a commit hash stays true forever and is checkable with one command. |
| 4 | Records written before this rule are **not rewritten**. | Their frozen narrative is itself evidence; rewriting it destroys the audit trail of the drift. Repair only claims that are false *now*, and record the repair. |

## Header shape

A record whose work has landed:

```markdown
**Status**: merged — PR #164 (`refactor/breakpoint-vars` @ `4469cf717`), 2026-09-24. <what remains, or "No work left.">
**Created**: 2026-09-24 · **Risk**: <level> — <why>
```

A record whose work is still open, or whose delivery state is not yet known:

```markdown
**Status**: implemented — <what remains>. This header makes no claim about push or PR state; see the evidence log.
```

| Permitted in the header | Forbidden in the header |
| --- | --- |
| `merged — PR #<n> (<branch> @ <merge-commit>), <date>` | `not pushed` / `unpushed` / `Not pushed yet` |
| `No work left` | `no PR is open` / `PR is open` / `Nothing is pushed and no PR is open` |
| `deferred` / `blocked on <external decision>` | `in progress` / `awaiting review` / `pending review` |
| `nothing open in this slice; the follow-ups are separate work` | `not merged yet` / `yet to land` |
| `<what remains>`, with the reason | a PR number written before the PR exists |

## Anti-patterns

| Anti-pattern | Consequence |
| --- | --- |
| Writing the live state because it is true *right now* | It becomes false at the merge, and the file is never touched again |
| Weakening the claim instead of moving it ("probably merged by now") | Worse than the original: now it is unverifiable too |
| Keeping the delivery detail in the header *and* the log | Two sources of truth, and the header is the one nobody revisits |
| Writing a PR number before the PR is opened | Invents an identifier that may never exist |
| Rewriting an old record's frozen narrative to match this rule | Destroys the evidence that the drift existed |
| Repairing a stale header without recording the repair | Repeats the repair next cycle instead of closing it |

## Gaps

- **No automated guard enforces this**, and that is a deliberate choice, not an oversight: the rule
  removes the field that could go stale rather than watching it. A guard would need a stated
  allowlist for the records that legitimately contain the forbidden phrases as quotations — the
  audit record that documents the drift, and the frozen narrative that
  `purchase-order-goods-receipt.md` D4 protects — and it would fire after the merge instead of
  before it.
- **No CI workflow inspects `odd/**` or any markdown.** Each workflow's `paths:` filter is nested
  under `pull_request` and scoped to its own component, so a records-only change runs no automated
  check at all; the four workflows only run on `push` to `main`.
- **Nothing updates the header at merge time.** The merge is a manual `gh pr merge`; no step hooks
  it. Design the header so that it does not need updating.
````

> **This contract block was amended by F1**, after the writer had already applied it: its third row was
> first written as `PR #170 | Repaired those four…` and its second as `PRs #164–#169`. F1 records why
> both were wrong. The block now mirrors the committed file; T3's "byte-identical" claim refers to the
> contract **as it stood when the writer ran**, which is the only reading under which it is true.

### I3 — `.agents/skills/project-conventions/references/index.md`

Two insertions in `## Workflow` and one in `## Gaps`.

```text
BEFORE
- `references/pr-chains.md` — chained and stacked PRs: the base-branch deletion failure mode, invariants, the pre-merge guard, the order of operations, and anti-patterns.
- Absent: no automated guard enforces one-worktree-one-unit; it is a review-time convention.
```

```text
AFTER
- `references/pr-chains.md` — chained and stacked PRs: the base-branch deletion failure mode, invariants, the pre-merge guard, the order of operations, and anti-patterns.
- `references/feature-records.md` — ODD feature records in `odd/tasks/`: the header invariant, the live-state failure mode, the permitted and forbidden status shapes, and anti-patterns.
- Absent: no automated guard enforces the ODD record header invariant; it is a writing-time convention, and the invariant removes the field that would go stale.
- Absent: no automated guard enforces one-worktree-one-unit; it is a review-time convention.
```

```text
BEFORE
- No CI check or repository setting prevents deleting a base branch that an open PR depends on.
```

```text
AFTER
- No CI check or repository setting prevents deleting a base branch that an open PR depends on.
- No CI check inspects `odd/**` or any markdown: every workflow's `paths:` filter is scoped to its own component and nested under `pull_request`, so a records-only change runs no workflow at all.
```

### I4 — `.agents/skills/project-conventions/SKILL.md`

One hard rule and one reference line.

```text
BEFORE
4. A change without minimum evidence is non-conformant.
```

```text
AFTER
4. A change without minimum evidence is non-conformant.
5. An ODD feature record (`odd/tasks/*.md`) states durable state plus its evidence, or what remains, on its `**Status**:` line — never a live delivery state; delivery detail belongs in the record's dated evidence log. Read `references/feature-records.md` before creating or updating a record.
```

```text
BEFORE
- `references/pr-chains.md` — chained and stacked PRs: the base-branch deletion failure mode, invariants, the pre-merge guard, the order of operations, and anti-patterns.
```

```text
AFTER
- `references/pr-chains.md` — chained and stacked PRs: the base-branch deletion failure mode, invariants, the pre-merge guard, the order of operations, and anti-patterns.
- `references/feature-records.md` — ODD feature records in `odd/tasks/`: the header invariant, the live-state failure mode, the permitted and forbidden status shapes, and anti-patterns.
```

## Out of scope (decided)

- **Any guard, script, workflow or CI change** (D3). The absence is documented in the reference's
  `## Gaps` and in `index.md`, so the next reader knows it is a decision.
- **Rewriting any existing record** (D5). The repair that owns the four stale headers is in flight;
  this slice does not touch the 30 records, and asserts nothing about that repair's state.
- **The root `AGENTS.md`'s other gaps** — it still does not mention `odd/tasks/` anywhere else, and
  it never describes the ODD workflow itself, only this one artifact. Adding a whole ODD section is
  a larger question and was not authorized.
- **`odd-status-reconciliation.md`** — the record where D2 lives. It is the audit trail of this
  class of repair; rewriting it to point at the new location would destroy exactly what it proves.

## Evidence

- **The two halves of the cause**, measured: `grep -niE "odd|feature record" AGENTS.md` → 0 hits
  (245 lines); `grep -rn "odd/|feature record" .agents/skills/project-conventions/` → 0 hits;
  `.github/workflows/angular-ci.yml:3-9` shows `paths:` nested under `pull_request` while
  `push: branches: [main]` has no filter, and none of the four filters lists `odd/**`.
- **The inherited rule**: `odd/tasks/odd-status-reconciliation.md:84-90` (D2, verbatim), `:108-115`
  (D7, the three self-amended headers), `:379-388` (the preventive follow-up with its options a/b/c).
- **Why no guard** (D3): the repository's only convention guard is
  `life-control-app-angular/src/shared/constants/breakpoints.spec.ts`; its own record
  (`odd/tasks/mobile-breakpoint-guard.md`, D3) chose a spec over a CI step, and its verification
  round found two holes in it that had to be patched.
- **The precedent for the section shape**: root `AGENTS.md:65-89` (`## Trabajo en paralelo con
  worktrees`) is a 25-line table plus a link to `references/worktrees.md`; I1 copies that shape.
- **The precedent for the reference shape**: `references/pr-chains.md` — `# Title`, a "Read this
  before…" line, `## The failure mode`, a table, `## Invariants`, `## Anti-patterns`, `## Gaps`. I2
  follows it.
- **The principle I2 applies is already in the skill**:
  `.agents/skills/project-conventions/SKILL.md` `## Evidence Contract` — *"This skill carries
  process, not facts: it names the declaring file instead of repeating the value, so it cannot go
  stale."* The header invariant is that same principle applied to the record's own header.

## Findings

- **F1 (low, mine, found by my own diff gate before the commit) — the new reference committed, in its
  own opening table, the exact sin it prescribes.** Two defects, both in `references/feature-records.md`:
  (a) the third row read `PR #170 | Repaired those four…`, asserting the state of a PR that is still
  open — if that PR is ever abandoned, the sentence becomes false, which is precisely the failure mode
  the file exists to document; and (b) the second row attributed the four fresh headers to `#164–#169`
  when only `#164–#167` wrote them, and the two later PRs of that same cycle (#168, #169) had in fact
  written neutral headers on their own initiative. Both corrected: the range is `#164–#167`, the row
  about the repair names no PR state at all, and the two PRs that got it right without being told are
  now recorded as the evidence in favour of the design. Recorded rather than quietly fixed: a
  convention slice that violates its own convention in its first table is the most useful lesson it
  can leave behind.
- **F2 (low, mine, found by the independent verification) — the contract went stale inside the commit
  that applied it.** F1 was corrected in the artifact (`references/feature-records.md`) but not in the
  `I2` block of this record, so after the commit the "verbatim" contract still carried the pre-F1 text
  while its own T3/T4 rows claimed "0 mismatches" and "all six insertions match". The verification
  caught the divergence. Corrected here, together with the two assertions this record had kept about
  the in-flight repair's state (`:19-20` and the `Out of scope` item), which contradicted this record's
  own D5. Two lessons, and they are the same lesson twice: **the moment you correct an artifact you
  have to correct every copy of it, including the copy that prescribes it**, and an invariant is worth
  nothing if the document that states it is exempt from it. This slice now contains three instances of
  its own subject matter: F1, F2, and the artifact F1 fixed.

## Task log

| Date | Task | Evidence |
|---|---|---|
| 2026-09-24 | T1 — read-only mapping of enforcement options | `gentle-ai-explore` scout (workflows, scripts, guard precedent, skill structure) + parent verification of the path filters and the skill's absence of any ODD mention |
| 2026-09-24 | T2 — write this contract | the four exact insertions above |
| 2026-09-24 | T3 — apply the four insertions | delegated to one `gentle-ai-worker` with the exact text and 4 allowed edit surfaces; reported 6 applied, 0 mismatches, `git diff --stat` = 3 files, +21 −0, and the new reference byte-identical to the contract **as then prescribed**, by checksum |
| 2026-09-24 | T4 — parent diff gate | the full `git diff` plus the new file read end to end and compared against every block: all six insertions match, no collateral line changed, `---`/`## Project Overview` not duplicated; **two of my own imprecisions found and corrected — see F1** |
| 2026-09-24 | T5 — work-unit commit | `2ccddc8` — 5 files, +402 −0, pure insertions |
| 2026-09-24 | T6 — independent read-only verification | `gentle-ai-verify` over `2ccddc8`: C1, C2, C4, C6, C7 upheld (the measured cause re-derived against the base commit; the four workflows' `paths:` filters; the 14 / D7 / #164–#167 history; both index locations and the balanced fences; the pure-insertion shape); C3 upheld with a note that the new text strengthens rather than weakens D2; **C5 partial, see F2** |
| 2026-09-24 | T7 — correct the verification's findings | this second commit on the branch: F2 (the stale `I2` contract block, the two in-flight-state assertions, and the unverifiable "six body lines" figure this record had repeated into the reference). Gate: the `I2` block extracted from this record now diffs **empty** against `references/feature-records.md`, and every remaining hit for a forbidden phrase is either a quotation inside the prohibited-examples table or the description of the defect in F1/F2 — never an assertion by the document itself |
