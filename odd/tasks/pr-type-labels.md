# ODD feature: pr-type-labels

**Repository**: LifeControl — the GitHub label inventory (13 → 18 labels), `.agents/skills/project-conventions/`
(`SKILL.md`, `references/index.md`, the new `references/labels.md`) and one section of the root `AGENTS.md`. No
application code, no schema, no endpoint, no contract, no workflow.
**Status**: merged — PR **#362** (`docs/pr-type-labels` @ `8727627`), 2026-10-17, so `main` carries the change; the
five `type:*` labels the mandate names are live on the remote (`L6`), and the delivery close-out — merge, branch and
worktree cleanup, anchor realignment — is recorded in `L11`. What remains is not work of this slice: the two
deferred pieces in `L7` (a PR template and a CI guard) are new candidate work that needs its own authorization.
**Created**: 2026-10-17 · **Base**: `main` @ `fc20a15` (merge of PR #360) · **Risk**: **none** — no stored data,
no secret, no contract others consume, no concurrency, no delivery pipeline. The two deferred pieces in `L7`
would be item 5 (delivery/CI) and are explicitly not part of this slice; the label set in `S2` is the tooling's
vocabulary mirrored, not one invented here.

## Specs

**S1** — *the user, 2026-10-17, verbatim, after quoting their own review comment on PR #360*: «de este comentario
Un detalle que vale para el label: el repo no tiene type:docs — entre los type:* sólo existe type:chore, y los PRs
de docs previos (#355, #356) no llevaban label. Le puse documentation, que existe en el repo y dice exactamente
lo que es, en vez de forzar un type:chore que mentiría sobre el contenido., que labels se deben agregar y como».

The stated requirement, made falsifiable: the repository must be able to satisfy the "exactly one `type:*` label"
mandate **honestly** — a documentation-only PR must have a label that describes it, and no PR may be forced onto a
label whose own description misdescribes its diff.

**S2** — *the user, 2026-10-17, verbatim*: «quiero crear types para futuros PR, ademas de docs deber[ia agregar?»
and, one turn later, «no hay algun skill de PR que lo especifique?».

There is one, and it settles the vocabulary instead of letting this repository invent it:
`gentle-ai-branch-pr`, Critical Rule 2, *"Every PR MUST have exactly one `type:*` label"*, with a documented
checkbox-to-label table and a commit-type mapping. Its own qualifier is the trap this feature had to respect:
the mapping is *"a suggestion, not proof of label availability or permission"*, so a name the mandate uses and
the repository lacks cannot be applied honestly. The skill is installed per machine by `gentle-ai sync` and is
**not** versioned in this repository.

Exact set, taken from the skill and not renamed:

| Commit type | Label |
| --- | --- |
| `feat`, `perf` | `type:feature` |
| `fix`, `revert` | `type:bug` |
| `docs` | `type:docs` |
| `refactor` | `type:refactor` |
| `chore`, `style`, `test`, `build`, `ci` | `type:chore` |
| `feat!`, `fix!` | `type:breaking-change` |

**S3** — *the scope the user selected on 2026-10-17, quoted from the option as presented*: «Además agrega una
referencia de labels en .agents/skills/project-conventions/ con el vocabulario del skill y un puntero desde
AGENTS.md, para que sobreviva sin depender del skill de la máquina». The user's own reply was «No, sólo quería el
spec»-shaped only until that choice: the selection above is the authorization for the write, and the surviving
requirement in it is that the convention must be readable **from the repository**, by a contributor who runs no
gentle-ai skill.

**S4** — *the non-goals, from the options the user did not choose*: no `.github/PULL_REQUEST_TEMPLATE.md`, no CI
guard that validates the label, and no retroactive re-labelling of the already-merged PRs (#355, #356, #360 keep
what they carry). Recorded so the omissions are decisions, not oversights.

**S5** — *the user, 2026-10-17, verbatim*: «agrega el registro formal de odd» — this record.

## Evidence

Measured 2026-10-17 against the remote label inventory and the 120 most recent pull requests, not inferred.

| Fact | Measurement |
| --- | --- |
| Inventory before | **13** labels; the whole `type:*` namespace had **one** member (`type:chore`, introduced at PR #325 on 2026-10-15) |
| All 120 PR titles carry a conventional-commit prefix | `feat` 49 · `docs` 46 · `chore` 10 · `fix` 10 · `refactor` 5 — no unprefixed title, so the label adds filtering, not information |
| Labelling over those 120 PRs | **62 unlabelled** · `enhancement,java` 18 · `documentation` 16 · `enhancement` 6 · `type:chore` 6 · `dependencies,java` 5 · `bug` 4 · `java,type:chore` 2 · `bug,java` 1 |
| By prefix against the labels actually carried | `feat`: 27 unlabelled, `type:chore` 1, `enhancement` 21 · `docs`: 25 unlabelled, `type:chore` 3, `documentation` 16, `enhancement` 2 · `chore`: 2 unlabelled, `type:chore` 3, `dependencies` 5 · `fix`: 5 unlabelled, `bug` 5 · `refactor`: 3 unlabelled, `type:chore` 1, `enhancement` 1 |
| The one-member namespace's accuracy | **5 of its 8 uses name a type the diff is not** — `type:chore` on #325 `docs`, #335 `feat`, #336 `docs`, #338 `refactor`, #339 `docs`. Genuine: #348, #349, #357 |
| The older vocabulary's accuracy | `documentation` on 16 docs PRs (16/16) and `bug` on 5 fix PRs (5/5) were never wrong — but both belong to the issue-triage namespace, and both predate the mandate |
| Enforcement available | **None**: all four workflows (`angular-ci`, `api-ci`, `docker-build-integrity`, `gateway-ci`) nest a `paths:` filter under `pull_request` scoped to their own component, and none reads labels or validates an issue link. `.github/PULL_REQUEST_TEMPLATE.md` does not exist |

## Tasks

| ID | Spec | Task | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1, S2 | Create the five missing `type:*` labels with the skill's exact names and one shared colour `#bfdadc`; `type:chore` was left as it was | inline (remote only) | — (remote label state, not a file) |
| T2 | S3 | Write `references/labels.md`: the rule, the vocabulary, the measured failure mode, 6 invariants, the commands, 6 anti-patterns and the gaps | inline | `2e03338` |
| T3 | S3 | Wire the reference: `SKILL.md` Hard Rule 6 + References entry, `references/index.md` Workflow bullet + gap line, and the `AGENTS.md` section "Labels de PR" | inline | `2e03338` |
| T4 | S1, S2 | Measure the evidence above (inventory, the 120-PR cross-tab, the mislabel set, the enforcement gaps) before writing any of it | inline | `## Evidence` |
| T5 | S5 | This record | inline | `2e03338` |

No task needs a worker: the whole slice is three markdown files, one remote label mutation and one measurement, and
a worker would spend longer reading the house style than doing the work.

## Log

**L1** *(the user, verbatim)*: «de este comentario Un detalle que vale para el label: el repo no tiene type:docs —
entre los type:* sólo existe type:chore, y los PRs de docs previos (#355, #356) no llevaban label. Le puse
documentation, que existe en el repo y dice exactamente lo que es, en vez de forzar un type:chore que mentiría
sobre el contenido., que labels se deben agregar y como». Answered read-only first: the inventory, then the
question of which labels the repo should carry.

**L2** *(the user, verbatim)*: «quiero crear types para futuros PR, ademas de docs deber[ia agregar?» — the request
that turned the answer into a write.

**L3** *(self-correction inside this feature)*: the first recommendation named **`type:feat` and `type:fix`**, which
are not the skill's names, and it argued against creating `type:breaking-change` because 0 of 120 PRs used it. Both
parts were wrong for the same reason: the vocabulary is not this repository's to author, so `type:feat`/`type:fix`
would have looked compliant while the skill's own mapping pointed at labels that did not exist. Corrected before
anything was created, after reading the skill the user pointed at in «no hay algun skill de PR que lo
especifique?».

**L4** *(the user's decision, verbatim as presented and selected)*: «Sí, los 5 + documentar la convención» —
«Además agrega una referencia de labels en .agents/skills/project-conventions/ con el vocabulario del skill y un
puntero desde AGENTS.md, para que sobreviva sin depender del skill de la máquina».

**L5** *(measured, and the reason the write is not cosmetic)*: the `type:*` namespace existed for two days with one
member and was wrong in **5 of its 8 uses**. Applied to a documentation-only PR — 46 of the last 120, 38% of the
volume — it read as *"no other `type:*` existed"*. The agent that wrote `documentation` on #360 did not improvise:
refusing a label that misdescribes the diff was the correct instinct, and it is the reason the defect surfaced at
all. The fix is not a per-PR judgement call.

**L6** *(verification, 2026-10-17)*: the created labels read back as `type:docs`, `type:feature`, `type:bug`,
`type:refactor`, `type:breaking-change`, each with its description, plus the pre-existing `type:chore`. The
declared vocabulary was then reconciled across all three sources and the remote — the set extracted from the
skill's table, from `references/labels.md`'s two mapping tables, from the `AGENTS.md` "Vocabulario" row and from
`gh label list` are **the same six names**. The three occurrences of `type:feat`/`type:fix`/`type:doc` in the new
prose were checked in context: every one of them is inside a prohibition (`labels.md` invariant 2, its
anti-patterns table, and the `AGENTS.md` "Qué no se crea" row), none is a prescription. `git diff --stat` showed
3 modified files (+22 lines) — `SKILL.md`, `references/index.md`, `AGENTS.md` — and 1 new file.

**L7** *(deferred, stated so it is not lost)*: `.github/PULL_REQUEST_TEMPLATE.md` and a CI guard are **not** in
this slice (`S4`). Without the template, the skill's *"check exactly ONE in the template"* step has no in-repo
widget; without the guard, the rule stays review-time only. Both are new candidate work needing their own
authorization, and the guard specifically is a change to the delivery pipeline.

**L8** *(working-tree note)*: `odd/tasks/angular-22-upgrade.md` is **untracked and pre-existing** at the time this
feature started — it is not part of this slice and was not modified. `L6`'s diff is the whole of it.

**L9** *(the invariant this record keeps, per `references/feature-records.md`)*: the header states durable state
plus what remains and asserts no delivery state. The delivery identity of this unit — PR number, merge commit, date
— is deliberately **not** written here: a PR number invented before the PR exists is exactly what the rule forbids.
A follow-up slice records it after the merge, the way `docker-infra-image-refresh.md` recorded its own.

**L10** *(the two delivery decisions the user made on 2026-10-17)*: give the PR an approved issue rather than open
it unlinked, and use the repository's worktree convention rather than branching in place. Issue **#361** was created
for this work and carries `status:approved`, read back through the API (`gh api
repos/le03nava/LifeControl/issues/361`) with an authenticated actor whose `permissions` report `admin: true`; the
branch is `docs/pr-type-labels` in `~/workspace/LifeControl-worktrees/docs-pr-type-labels`, and the anchor stays on
`main` and clean. The five changed paths were moved there by copy with a `sha256sum` equality check before the
anchor was reverted, so no path exists in two places and nothing was edited twice.

**L11** *(delivery evidence, 2026-10-17)*: merged as a merge commit — `8727627`
(`87276278976d4fcc4455ea651fb3c17a2a5bdd90`) at 16:26:27Z — by PR #362, which closed issue #361 two seconds later;
the issue keeps `status:approved`. The PR ran **zero** CI jobs, which its body stated before the merge instead of
assuming green: all four workflows nest a `paths:` filter under `pull_request` scoped to their own component, and
this unit changed markdown only. `gh pr merge --delete-branch` deleted the remote ref and **skipped** the local
branch deletion, because the command ran inside the worktree holding the branch — the third row of the measured
table in `references/worktrees.md`, which is why it printed the manual follow-up. The close-out then followed the
documented order: `herdr workspace close w3F` (never `--group`, which would have closed the primary workspace and
every linked worktree), `git worktree remove`, `git worktree prune`, the anchor fast-forwarded `fc20a15..8727627`,
and `git branch -d docs/pr-type-labels` — which needed no `--force` because `git merge-base --is-ancestor` had
confirmed the branch was an ancestor of `origin/main` first.
