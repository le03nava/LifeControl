# ODD — state the branch-sync and citation rules once, and repair two orphaned citations

Issue #937. The branch-sync rule is stated where it can be enforced, a record's citation rule is stated
where records are governed, and the two citations in this repository that named commits no longer in
`main` are repaired.

**Status**: implemented — the branch half of the rule in `worktrees.md`, the record half as rules 7 and 8
of `feature-records.md`, the single `AGENTS.md` row that points at both, and the repair of the two
orphaned citations in `tax-rate-write-honesty.md` are the branch's content. The citation **guard** is
deliberately not here: it is issue **#945**, and what that defers is written below instead of implied.

## Specs

Quoted verbatim from issue #937's acceptance criteria, as corrected on 2026-11-17.

- **S1** — `.agents/skills/project-conventions/references/worktrees.md` carries a **Sync the branch
  with `main`** row stating: unpublished → rebase; published → merge, never rebase; and the
  consequence, that a rewrite after the evidence entry orphans the citation.
- **S2** — `.agents/skills/project-conventions/references/feature-records.md` states the two record
  rules: the evidence entry is written after the last rewrite and nothing rewrites after it, and a
  citation is content or a merge commit.
- **S3** — `AGENTS.md` carries **one** row pointing at those two files with the consequence in a
  line, and duplicates neither's text.
- **S4** — A new suite `bash .github/scripts/tests/odd-citations.test.sh` exits 0 on this tree, is
  picked up by the `guard` job's glob at `.github/workflows/pr-label-guard.yml:82` with **no workflow
  edit**, and is listed in `.agents/skills/project-conventions/references/index.md`.
- **S5** — Adding a citation of a commit that is not an ancestor of `origin/main` to any record
  makes that suite fail, naming the file and the SHA.
- **S6** — Removing an entry from the exception list while its citation is still in the tree makes
  that suite fail.
- **S7** — The exception list is **derived at implementation time**, not copied from this issue (29
  entries on 2026-11-17, not 33). Each entry resolves as a commit exactly once; an entry that stops
  resolving fails the suite. An entry that now resolves as an ancestor is **removed** in the same
  pull request, and removing one while its citation still appears in an unedited record fails.
- **S8** — `odd/tasks/project-conventions-skill.md`'s `cec4578` sentence is carried as a **declared
  non-arrival**, not as an exception: the suite reads the declaration and requires it neither to
  stay orphaned nor to resolve.
- **S9** — A patch-id or a tree object cited in a record produces no failure and no exception entry.
- **S10** — The ancestry helper is **not duplicated silently**: `odd-status-headers.test.sh:238-346`
  already resolves `origin/main`, proves `cat-file -e` and skips with an announcement when the
  remote is absent. Either both suites share it, or the copy is stated in the record with its reason.
- **S11** — `odd/tasks/<slug>.md` exists for this unit, as every unit here carries one, with its
  `**Status**:` opening token and a dated evidence-log entry for the citation correction.
- **S12** — The two `22b9531e` citations in `odd/tasks/tax-rate-write-honesty.md` are corrected to
  the commits that are in `main` (`73be3998` the fix, `1920276d` the record), and the correction is
  recorded as its own dated log entry rather than a silent edit.

## Tasks

| ID | Specs | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1 | inline — the `Sync the branch with main` section in `worktrees.md` | this slice |
| T2 | S2 | inline — rules 7 and 8 in `feature-records.md` | this slice |
| T3 | S3 | inline — the single `AGENTS.md` row | this slice |
| T4 | S12 | inline — the citation repair and its dated log entry | this slice |
| T5 | S4–S10 | deferred to issue **#945** — the guard, its frozen list and its fixtures | not here |
| T6 | S5–S10 | deferred with T5; what this slice does verify is below | — |

## Why the guard is not in this pull request

It is written, it works, and it is on branch `chore/odd-citation-provenance` in the same worktree. It is
not here because three review lineages returned **four CRITICAL findings against it and none against the
rule**: a 430-line suite is a review of its own, and the rules that this pull request states do not
depend on it landing in the same slice. Rule 4 of `feature-records.md` is one pull request per unit, and
a unit whose second half is still drawing CRITICAL findings is two units.

Its last lineage also cannot be closed by the tooling available: the correction is applied and verified
locally, and the `provider_targeted_validator` slot that would validate it is rejected as stale by
`gentle_review_capture` on two separate lineages with the branch base exactly equal to `origin/main`.
Issue #945 carries that, with the four findings and their corrections.

**What this slice does not claim.** Rules 7 and 8 arrive without automation, so they are review-time
conventions until #945 lands — the same standing `pr-chains.md` gives its own gaps. Nothing in this
repository fails today when a record cites a commit that left `main`; 33 such citations exist, and they
are named in #945 rather than silently repaired.

## Log

**L1** — the user's request, verbatim: *"ok comienza con el 937"*, after *"puedes revisar el issu
937"* and *"ok, crees que si convenga hacerlo y si solucione problemas con futuros PR?"*.

**L2** — the issue was corrected before implementation, on the user's *"si"* to *"¿Te lo aplico igual
que al 937 — cuerpo corregido más un comentario fechado que registre el cambio?"*. Two contract
corrections, both recorded on the issue: the exception list is derived at implementation time and only
shrinks, and decision **D3** of `odd-status-headers-guard.md:43` is answered instead of ignored, so the
honest non-arrival is never asked to become an ancestor.

**L3** — the split, on the user's choice of *"partir la unidad: la regla primero, el guard en su
issue"*, after four CRITICAL findings across three review lineages had all landed on the suite and none
on the rule. The guard's material stays on `chore/odd-citation-provenance` and issue **#945**.

**L4** — two measurements, and the difference between them is a definition, not drift. The scan the
issue was written from read **bare** hex tokens: `records=168 citas-de-commit=1532 huerfanas=29
records-con-huerfanas=20`. Reading only **backticked** tokens that resolve as a commit — D3's scope,
because the directory legitimately backticks patch-ids and tree objects — gives **1473** citations
across **173** records, of which **33** are not ancestors of `main`, in **20** records, over **27**
distinct commits. Distinct commits and that total move on their own as branches merge, which is why
neither is written into a rule as a load-bearing number.

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-11-17 | The two orphaned citations in `tax-rate-write-honesty.md` were corrected to the commits that are on `main` — `73be3998` the fix, `1920276d` the record — and the superseded `22b9531e` is named here as the commit that existed before two rebases and is **not** an ancestor of `main`, the shape this unit exists to stop producing. The record's own prose is otherwise unchanged, per rule 5 of `feature-records.md`: repair only claims that are false now, and record the repair. |
