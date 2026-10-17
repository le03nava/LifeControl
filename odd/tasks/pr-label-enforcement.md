# ODD feature: pr-label-enforcement

**Repository**: LifeControl — `.github/` (new `PULL_REQUEST_TEMPLATE.md`, new `pr-label-guard.yml` workflow, two
new scripts and their tests), the project-conventions skill (`references/labels.md`, `SKILL.md`,
`references/index.md`), one section of root `AGENTS.md`, the two ODD records, plus one remote mutation of 328
merged pull requests. No application code, no schema, no endpoint, no consumed contract.
**Status**: implemented and verified; what remains is the delivery decision that belongs to the user — push the branch,
open the PR(s) with `type:chore` (the mapping this very slice installs) and merge. The retro-label of the 328 merged
PRs is **done** and read back from the remote (`L8`), and `## Delivery slices` marks where a reviewer can stop. No PR
number, merge commit or date is asserted here: this record is written before the PR exists, exactly the rule
`pr-type-labels`' `L9` states.
**Created**: 2026-10-17 · **Base**: `main` @ `b05b525` · **Risk**: **item 5** (delivery pipeline: a workflow that
gates every pull request) and, for the retro-label, a wide but reversible remote mutation of merged PRs. No stored
data, no secret, no application contract, no concurrency. The guard also touches **item 3** in the narrow sense
that it changes what every future PR must satisfy before merge — it is the requested change, not an unrequested
break of shared code.

## Specs

**S1** — *the user, 2026-10-17, verbatim*: «continua con Sigue diferido por decisión (L7): PULL_REQUEST_TEMPLATE.md,
guard de CI y re-etiquetar los PRs mergeados.»

This reverses the three non-goals the previous record froze in its own `S4` («no `.github/PULL_REQUEST_TEMPLATE.md`,
no CI guard that validates the label, and no retroactive re-labelling of the already-merged PRs»). The deferral was
a decision, not an oversight, so the reversal is recorded there too (`L12` of `pr-type-labels.md`) instead of being
silently overwritten.

**S2** — *the user's scope decision, quoted from the option as selected*: «Los 328: completar 307 y corregir 21
(recomendado)» — «Todo el historial queda con exactamente un type:* correcto. 307 altas + 21 bajas de type:chore
mal puesto.» The preview the selection carries is the acceptance criterion: `+307` altas by conventional-commit
prefix, `21` corrections of `type:chore` on PRs whose diff is `docs`/`feat`/`fix`/`refactor`, and the triage
labels (`documentation`, `bug`, `enhancement`, `java`, `dependencies`) untouched, because they are orthogonal.

**S3** — *the template, from the machine-installed `branch-pr` skill (Critical Rule 2 and its PR Body Format)*,
verbatim where it settles the shape: «Use the authorized target's `.github/PULL_REQUEST_TEMPLATE.md`. Fill it from
observed evidence, retaining its required sections» and «Check exactly ONE in the template and add the matching
label». So the template must carry six checkboxes, each naming the label it pairs with, plus the skill's sections:
Linked Issue, PR Type, Summary, Changes table, Test Plan, Contributor Checklist. It must also carry the skill's own
limit — «Do not mark an unverified `[x]` checkbox» — because a template that invites a false attestation is the
failure mode this repository already recorded in `references/labels.md`.

**S4** — *the guard, falsifiable*: a workflow fails the pull request when the PR does not carry **exactly one**
`type:*` label whose name is in the declared vocabulary. Two measured constraints from the previous slice's
`## Evidence` decide its shape: (a) all four existing workflows nest a `paths:` filter under `pull_request`, so a
markdown-only PR runs **zero** jobs — the guard must therefore carry **no `paths:` filter**, or it would be blind
to the very PRs the label rule under-serves; (b) no workflow reads labels today, so this is the repository's first
label gate and it must fail **closed** (an unreadable or empty label set is a failure, never a pass).

**S5** — *the retro-label, exact*: for each of the 341 merged PRs, the single `type:*` label its own
conventional-commit prefix maps to, per the skill's table; any other `type:*` already present is removed because a
second `type:*` violates the same invariant as zero. One title is `refactor(product)!: …` (#134): the table names
only `feat!`/`fix!`, so the rule is stated explicitly rather than guessed — the `!` is the conventional-commits
breaking marker **regardless of type**, and a breaking refactor is a breaking change, not a refactor. Idempotency
is part of the spec: running it a second time must plan **zero** mutations.

**S6** — *non-goals, restated so their absence is a decision*: no enforcement of the skill's Critical Rule 1
(approved issue link) — that is a second gate with its own failure modes; no branch-protection or
required-check configuration, which lives in repository settings this slice does not own and cannot see in a diff;
no new labels (the vocabulary was settled in `pr-type-labels`); no rewrite of PR bodies or of history; no change to
the triage namespace.

## Evidence

Measured 2026-10-17 against the remote, not inferred. The previous slice measured "the 120 most recent PRs"; this
slice measured **all** merged PRs because a retro-label has to cover the history it claims to cover.

| Fact | Measurement |
| --- | --- |
| Merged PRs | **341** |
| Carrying no `type:*` label | **307** |
| Carrying exactly one `type:*` label | 34, of which **21 name a type the diff is not** and only 13 are correct |
| Total needing a mutation | **328** (307 additions + 21 corrections) |
| Conventional-commit prefix coverage | total over all 341: `feat` 160 · `docs` 80 · `fix` 42 · `chore` 23 · `refactor` 20 · `test` 10 · `ci` 2 · `style` 2 · `build` 1 · `perf` 1 — no unprefixed title, so no PR is unmappable |
| Titles carrying `!` | **1** — #134 `refactor(product)!: split variants into a global definition and per-store stock` (`S5`) |
| Label inventory | 18 labels, the six `type:*` the vocabulary names all present (`S3` of the previous slice, re-read) |
| Template | `.github/PULL_REQUEST_TEMPLATE.md` **absent**; `.github/` held only `dependabot.yml` and four workflows |
| Enforcement | **none**; the four workflows filter by `paths` under `pull_request`, so a markdown PR runs zero jobs |
| Open PRs affected by a new gate | **0** — no open PR was retro-failed by adding the check |
| After the retro-label (read back from the remote) | **342** merged PRs, each with exactly one `type:*` label: **0** missing, **0** duplicated, **0** naming a type their prefix is not, **0** unmappable titles (#365 merged mid-run and already carried `type:docs`) |
| The same guard, over real label sets | **34 pass / 307 fail** on the pre-apply snapshot, **342 pass / 0 fail** on the reconciled history |

## Tasks

| ID | Spec | Task | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | S3 | `.github/PULL_REQUEST_TEMPLATE.md`: the skill's six sections, six `type:*` checkboxes, the "never mark an unverified box" rule | inline | `f8e528f` |
| T2 | S4, S2 | Test-first for the guard rule: `.github/scripts/tests/check-pr-type-labels.test.sh`, observed RED before the script existed | inline | `db50252` |
| T3 | S4 | `.github/scripts/check-pr-type-labels.sh` — the rule as one executable, no network, no `gh` | inline | `db50252` |
| T4 | S4 | `.github/workflows/pr-label-guard.yml` — no `paths:` filter, PR events only, shellcheck + self-tests + the guard | inline | `f8e528f` |
| T5 | S5 | Test-first for the retro-label plan and its `gh` invocation, against a stubbed `gh` (offline, deterministic) | inline | `7fff0a8` |
| T6 | S5 | `.github/scripts/retro-label-prs.sh` — idempotent, `--apply` opt-in, removes a second `type:*` | inline | `7fff0a8` |
| T7 | S2, S5 | Run the retro-label on the 328 and read the result back from the remote | inline (remote) | — (remote state, not a file) |
| T8 | S3, S4 | Wire the documents: `references/labels.md` (invariant 6, gaps → enforcement), `SKILL.md` Hard Rule 6, `references/index.md`, `AGENTS.md` § "Labels de PR" | inline | `2fc50ab` |
| T9 | S1 | Reverse `S4` of `pr-type-labels.md` with a dated log entry instead of editing its history, and point its `L7` at this record | inline | records commit |
| T10 | S2, S5 | Verification: the three suites green, shellcheck clean, the guard exercised against real PR label sets, the remote read-back | inline | `L8` |
| T11 | — | The commits as reviewable work units, and the slice boundary recorded for the reviewer | inline | `db50252` .. records commit |

No task needs a worker: the slice is two shell scripts, one workflow, one markdown template and four document
edits, all in one repository area, and a writer would spend longer reading the house style than doing the work
(`Mechanisms`, Writer rule — no independent units heavy enough to earn a delegation).

## Delivery slices

The slice is long enough that the advisory review budget had to be decided rather than ignored: 1,013 authored lines
before the records commit, against a budget of about 400. The split follows the feature's own structure, not the line
count, so each slice is something a reviewer can hold in one pass:

| Slice | Commits | Content | Stands alone because |
| --- | --- | --- | --- |
| **1 — the rule, enforced from now on** | `db50252`, `f8e528f` | `check-pr-type-labels.sh` and its suite; `PULL_REQUEST_TEMPLATE.md`; `pr-label-guard.yml` and the wiring suite | It changes what a **future** PR must satisfy. Nothing in it reads the history: the guard sees one PR's labels at a time. |
| **2 — the history, reconciled** | `7fff0a8`, `2fc50ab`, records | `retro-label-prs.sh` and its suite; the four document edits; this record | It makes the **past** consistent with slice 1 and documents both. It was applied before the branch was pushed, so the merged history is already reconciled when the gate lands. |

`f8e528f` is its own commit because it carries the workflow — a delivery-pipeline change (`Risk` item 5) — and the
house rule is that a unit whose risk line names a high-risk item gets its own commit so native review can take it
alone. The order inside slice 1 is not cosmetic either: the guard suite asserts that the template and the workflow
exist, and that the template offers exactly the vocabulary the guard accepts, so committing the suite before those
artifacts would leave a red suite at that commit. Test-first is kept **within** each artifact — both suites were
observed RED before their scripts existed (`T2`, `T5`) — not by separating a rule from the artifacts it asserts.

## Log

**L1** *(the user, verbatim — `S1`)*: «continua con Sigue diferido por decisión (L7): PULL_REQUEST_TEMPLATE.md,
guard de CI y re-etiquetar los PRs mergeados.»

**L2** *(the scope decision — `S2`)*: the re-label was the only consequential open decision, so it was asked once,
with the measured size of each option, before any write: `Los 328: completar 307 y corregir 21 (recomendado)` ->
selected. The template and the guard needed no question: `S4` of the previous record already names both, and the
skill settles the template's shape.

**L3** *(why "the merged PRs" is 328 and not 3)*: the previous record framed the non-goal as «the already-merged
PRs (#355, #356, #360 keep what they carry)», which is the three PRs it was looking at, not the inventory. The
inventory is 341 merged PRs with 307 unlabelled and 21 mislabelled, so a retro-label that touched only three would
have left 325 exceptions under a rule that the same slice was about to automate. The user chose the full
reconciliation with that measurement in front of them.

**L4** *(the `!` case, decided instead of guessed — `S5`)*: exactly one merged title carries `!` and it is
`refactor(product)!`, which the skill's table does not enumerate (it names `feat!`/`fix!`). The rule applied is the
conventional-commits one — `!` marks a breaking change whatever the type — so #134 gets `type:breaking-change`, not
`type:refactor`. Recorded here so the decision is reviewable rather than buried in a script.

**L5** *(the triage namespace stays, and the file that contradicted it was fixed)*: the retro-label touches `type:*`
names only. #360 and the other 33 PRs carrying `documentation` keep it beside the new `type:docs`, because the triage
vocabulary records how the change was routed at the time and removing it would destroy information to make a filter
look tidy. That decision exposed a contradiction inside `references/labels.md`, whose own anti-pattern table said
*"Keeping `documentation` on a PR now that `type:docs` exists"*. The row now says what it always meant — applying
`documentation` **as the answer** to the mandate — and states that the historical label on a merged PR is a different
thing. A slice that adds enforcement has to survive the documents it enforces.

**L6** *(two consequences of enforcing a rule that was review-time only, stated instead of discovered later)*: the
guard is **not a required check** — it reports on every pull request, but only a branch-protection rule can make a
merge wait for it, and that setting is not in this repository's diff. And a **Dependabot** pull request arrives with
`dependencies` and no `type:*` label, so the guard reports red on a dependency bump until a human adds `type:chore`;
auto-labelling was deliberately not added, because it needs `issues: write` and this gate runs with
`permissions: contents: read`. Both are recorded in `references/labels.md` under "Gaps" rather than left as
surprises.

**L7** *(the commit shape, and why the tests-first order does not break it)*: five commits, split by artifact rather
than by line count — `db50252` (guard + its suite), `7fff0a8` (planner + its suite), `f8e528f` (template + workflow +
the wiring suite), `2fc50ab` (the four documents), and the records. `f8e528f` is alone because the workflow is the
delivery-pipeline change (`Risk` item 5). The guard suite asserts that the template and the workflow exist and agree
with the guard's vocabulary, so the suite cannot precede its artifacts *in a commit* — test-first is kept within each
artifact (both suites were run and observed RED before their scripts existed), not by publishing a commit whose own
suite is red. The `## Delivery slices` table records where the review budget splits, because 1,013 authored lines
exceed the advisory 400 and that is the reviewer's problem to pay for, not to discover.

**L8** *(verification, 2026-10-17 — every number below measured, not asserted)*: the retro-label ran over the 328
planned mutations and exited 0 after editing **328 pull requests** ("apply: 328 pull request(s) edited"). Three checks
followed, deliberately not sharing an implementation: (1) the planner itself, re-run on a fresh snapshot, plans
**0 additions and 0 removals over 342** — the idempotency the offline suite asserts; (2) an independent **Python**
cross-check that re-derives the expected label from each title, with no bash and no reuse of the script's mapping,
reports `missing 0 · multi 0 · wrong 0 · unmappable []` over all 342; (3) the **guard executable** over every real
label set reports **342 pass / 0 fail**, while the same guard over the pre-apply snapshot reported **34 pass / 307
fail** — the rule's own verdict on real data, before and after. Offline: the three suites are **26 + 12 + 33 = 71
assertions, 0 failed**; `shellcheck --severity=warning` over the five new scripts exits 0; `actionlint` over the
workflow exits 0. One pull request (#365, a sibling session's docs record) merged into `main` while the run was in
flight and already carried `type:docs`, which is why the count is 342 and not 341 — the guard passes it too.

**L9** *(what remains)*: the push, the pull request and its label (`type:chore`, per this slice's own mapping), and
the merge are the user's decisions, and the record asserts none of them. Two further things are deliberately **not**
done here and are stated in `references/labels.md` under "Gaps": the guard is not a branch-protection required
check, and Dependabot pull requests still need a human to add `type:chore`.
