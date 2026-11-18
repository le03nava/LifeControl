# ODD feature: instruction-defects

**Repository**: LifeControl — `AGENTS.md`, `CONTRIBUTING.md`, `.agents/skills/project-conventions/references/feature-records.md`, `.agents/skills/project-conventions/references/worktrees.md`
**Branch**: `docs/instruction-defects` · **Worktree**: `~/workspace/LifeControl-worktrees/docs-instruction-defects`
**Issue**: #957 — `docs(conventions): the instruction defects the pin-enforcement unit paid for`
**Status**: implemented — the six items are the branch's content, and each one names the measurement that made it a defect. Nothing is deferred. Two boundaries the issue drew are held: item 1 is the **instruction** half only, because making `setup-env.sh` abort on a failed `chown` belongs to #897; and item 5 is the **documentation** half of #955, which owns the code.
**Created**: 2026-11-17
**Risk**: none. No code, no data, no security surface and no CI file moves: four Markdown documents change and every one of the sixteen suites that reads them still passes. Item 3 was considered and rejected as a contract change — it **defines** a table shape that no document defined, so nothing that existed stops working, and the 105 records that carry the old column are explicitly not rewritten (rule 5). Item 5 does name a constant that issue #955 adds to `workflow-pins.test.sh`, so the two units are **ordered**: this documentation is true once the code half is in the tree, which is a dependency between two approved units and not a claim about this unit's transport. Not item 1, not item 2, not item 4, not item 6.

## Problem

Six instructions asserted things their tools do not do, and each one cost time in the run that delivered #896. Grouped in one unit because one surface owns all six — `AGENTS.md` plus `.agents/skills/project-conventions/references/` — and because fixing one defect per pull request is the practice `references/feature-records.md` measures as costing 51 of the last 150 merges.

## Specs

- **S1 — the Loki guarantee is stated as conditional.** The `AGENTS.md` row no longer says `setup-env.sh` *leaves* `/loki`, `rules/` and `rules-temp/` owned by `10001:10001`. It says the script **tries**, that `chown` needs root, that until #897 makes the script abort a directory left with another owner is reported by nobody, and it names the failure the wrong owner produces — `mkdir /loki/chunks: permission denied`, a restart loop behind a green `health`. The measured pair (`leo3nava:leo3nava 755`, image 3.7.8) is in the row.
- **S2 — the cleanup order can be executed.** `references/worktrees.md` no longer ends the order with `prune`. It states that `git worktree remove` ignores ignored files in its safety check but **cannot delete what another user owns**, so it fails with `Directory not empty` on any worktree that started the stack; that pruning after that failure drops the registration while the bytes stay, so `git worktree list` reports a clean set that is false; that verification is `ls` on the path; and that deleting the root- and `10001`-owned state is a `sudo rm -rf` the operator runs. Both places that recited the order — the cleanup section and the branch-deletion block — carry the corrected sequence.
- **S3 — the `## Tasks` table has a definition, and it has no `Commit` column.** `references/feature-records.md` states the shape as `| ID | Specs | Task | Evidence |` and gives the reason the old column cannot survive: `odd-citations.test.sh` rejects a backticked 7–40 hex token that resolves and is not an ancestor of `origin/main`, and a branch commit is never an ancestor when the record is written, so the only legal value is empty. `Evidence` names what the task touched — a patch-id, a suite and its count, the file, `this record`. Records written before the section are not rewritten (rule 5), and no suite enforces the column set, for that same reason.
- **S4 — the review budget says what it counts.** `CONTRIBUTING.md` §3 states that the ~400 advisory lines are **authored** lines, that lock files and generated fixtures are excluded, that test code **is** counted but reviewed by its assertions rather than line by line, and that the budget therefore never rewards leaving a suite out.
- **S5 — one row lists every shellcheck surface, with the gap named.** `AGENTS.md` gains a `Superficie de shellcheck` row: the frozen roots are `EXPECTED_LINT_SURFACES` in `workflow-pins.test.sh`, `.github/scripts/**` and `.githooks/*` are linted by the guard's step, `docker/scripts/**` by `Shell & Artifact Integrity`, and `k8s/**` by the guard since #955; which glob covers a root is not the contract, that **some** glob does is; and the reason `k8s` belongs to the guard (no `paths:` filter, so it runs on every pull request) is stated so the next file that lands there lands somewhere visible. The file counts are read from the run, not copied.
- **S6 — one sentence orders the two rules that met.** `CONTRIBUTING.md` §3 says slices are commits inside one pull request, a chain is for units that are independent of each other, and the ODD record never gets a pull request of its own — with one clause in the `AGENTS.md` "Cuántos PRs por unidad" row pointing at it, so the row that looks contradicted names where the resolution lives.

The issue's own boundary: the closed verdict vocabulary, its guard, and the rule that counts are read from the run and not copied, **stand**.

## Tasks

| ID | Specs | Task | Evidence |
| --- | --- | --- | --- |
| T1 | S1 | The Loki row, restated as a conditional guarantee with #897 named | pid `fb14e47ad0a7` |
| T2 | S2 | The cleanup order, both recitals | pid `fb14e47ad0a7` |
| T3 | S3 | The `## Tasks` table section in `feature-records.md` | pid `fb14e47ad0a7` |
| T4 | S4, S6 | §3 of `CONTRIBUTING.md`: the accounting and the ordering sentence, plus the pointer in the `AGENTS.md` row | pid `fb14e47ad0a7` |
| T5 | S5 | The `Superficie de shellcheck` row, and the stale sentence in `CONTRIBUTING.md` §6 that named `.github/scripts/*.sh` only | pid `fb14e47ad0a7` |
| T6 | S1–S6 | The sixteen guard suites, and this record | this record |

T1–T5 are one commit: they are six statements about one convention surface, and a reader of `AGENTS.md` who finds the new row and not the corrected budget would be reading two documents that disagree.

## Log

**L1 — the request.** «puedes hacer el issue 957 y 955»

**L2 — item 3's premise, checked, and it holds in a sharper form.** The issue asks to «remove the `Commit` column from the `## Tasks` table». Measured before any edit: **no document defines that table**. `grep` for `| Id | Spec`, `Id | Spec | Task` and `## Tasks` over `AGENTS.md`, `CONTRIBUTING.md` and `.agents/skills/project-conventions/` returns nothing, and `references/feature-records.md` has no tasks section at all — its headings are the failure mode, the invariant, the header shape, the anti-patterns and the gaps. The column exists only in the records: **105 of the 178** files in `odd/tasks/` carry it in one of two layouts (`| Id | Spec | Task | Commit |`, `| # | Spec | Route | Task | Commit |`). So there was no column to delete from a specification. The fix is the one the issue's own phrasing allows — *delete the column* — but written where it can bind: the shape is defined once, without it, and the reason is stated. Defining nothing and declaring the item done would have left the next writer free to add it again, which is the defect.

**L3 — item 5's dependency on #955, stated rather than hidden.** The row names `EXPECTED_LINT_SURFACES`, which does not exist on `main` and which #955 adds to `.github/scripts/tests/workflow-pins.test.sh`. The two units are therefore **ordered** — #955 then #957 — and the wording keeps the ordering visible: `k8s/**` is attributed to the guard «desde el issue #955», and the surfaces are frozen in a suite rather than promised in prose. Written the other way round, the row would have had to state the pre-#955 wiring (`k8s/` outside every glob), which stops being true the moment #955 merges and would leave the gap named as if it were still open — the exact falsification the header rule exists to stop, one document over.

**L4 — the counts in item 5 were re-measured, not copied.** `docker/scripts`: **40** shell files. `.github/scripts` plus `.githooks`: **21**, which is the number `AGENTS.md` carried. `k8s`: **3**, which no glob named. The issue's figures hold, and the row now says the counts are read from the run — the same rule the "Suite del guard" row already lived by, which is why that row's own count is left as the observation it was rather than re-dated.

**L5 — what S5 deliberately does not say.** It does not claim `Shell & Artifact Integrity` lints `k8s`, because it does not: that job's `paths:` filter covers `docker/**` and two build files, so a `k8s`-only pull request would never run it. It also does not claim the guard's step is where a *new* root must go; it claims that some glob must name it, which is what the freeze checks — and the freeze is a surface list, not a per-step assertion, because asserting that both globs name every root would be false today (`docker/scripts` is linted by one step only).

## Evidence log

Measured on 2026-11-17 in `~/workspace/LifeControl-worktrees/docs-instruction-defects`, branched from `origin/main` at `0b733e54`.

| # | Spec | Measurement | Result |
| --- | --- | --- | --- |
| E1 | S3 | `grep -rn '\| Id \| Spec` / `Id \| Spec \| Task` / `## Tasks` over `AGENTS.md`, `CONTRIBUTING.md` and `.agents/skills/project-conventions/`, **before** the edit | no match: no document defined the table, so the column had no specification to lose it |
| E2 | S3 | `grep -rlE '^\| *(Id\|ID\|#) *\|.*\| *Commit *\|' odd/tasks/*.md \| wc -l` | **105 of 178** records carry the column; none of them is rewritten |
| E3 | S5 | `find docker/scripts -name '*.sh' \| wc -l` · `.github/scripts` plus `.githooks` · `find k8s -name '*.sh' \| wc -l` | **40** · **21** · **3** — the third is the gap |
| E4 | S1–S6 | every suite under `.github/scripts/tests/`, **after** the edit | 16 suites, all exit 0: `odd-status-headers` 78, `check-issue-reference` 72, `worktree-anchor` 38, `issue-templates` 34, `retro-label-prs` 33, `odd-citations` 29, `workflow-trigger-parity` 28, `check-pr-type-labels` 26, `label-artifacts` 24, `contributing-job-names` 21, `vocabulary-source` 17, `adr-migration-citations` 14, `angular-formatting-gate` 14, `gradle-pins` 13, `workflow-pins` 13, `adr-line-citations` 11 |
| E5 | S2, S3, S5, S6 | the three suites that read the documents this unit edits, named so the pass is not taken on the count alone | `worktree-anchor` reads `references/worktrees.md` for the anchor rule and the session-start section; `odd-status-headers` reads the `**Opening verdict token**:` line of `references/feature-records.md` and the `Qué no lleva nunca` row of `AGENTS.md`; `contributing-job-names` reads the job names `CONTRIBUTING.md` quotes. All three pass, and all three would fail loudly if the section they read had become unreadable |

Provenance by **content**, per rule 8 — no branch SHA stands as evidence here:

| Subject | `git patch-id --stable` |
| --- | --- |
| the six instruction fixes | `fb14e47ad0a7ff5d38be71da594274c33e0c2377` |

**What these checks do not prove, stated so nobody reads more into them.** No suite asserts the new wording — there is no lint over prose, and the freeze S5 names lives in the code half. What is proven is that the four documents still parse for the suites that read them and that no instruction they already enforced changed meaning. And S2's cleanup order was not executed against a worktree in this run: its three facts are #957's measurements, taken on 2026-11-18, and reproducing them needs a stack-started worktree owned by two other users.
