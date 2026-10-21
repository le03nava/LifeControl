# ODD feature: odd-header-repair-delivery-and-blocker

**Repository**: LifeControl — two record headers under `odd/tasks/` (`access-provisioning-worker-repairs.md`,
`env-defaults-hardening.md`) plus this record. **No production code, no workflow, no `.env*` file, no
edit to any other record's narrative.**
**Status**: the repairs of issue **#470** are written and checked: both `**Status**:` paragraphs state
merged state with their merge commits, the two superseded claims are recorded where they stood, and
`odd-status-headers.test.sh` passes on this tree. The delivery is recorded in the log below.
**Created**: 2026-10-19 · **Branch**: `chore/odd-header-repairs` · **Base**: `main` @ `7dbd3aa` ·
**Worktree**: `~/workspace/LifeControl-worktrees/chore-odd-header-repairs` (herdr `w4Y`)
· **Issue**: **#470** · **Risk**: **low** — markdown only, nothing imports it; the one executable
check that runs (`odd-status-headers.test.sh`) reads `odd/tasks/*.md` and local `git` and writes only
inside its own `mktemp -d`.

## Specs

Quoted verbatim from issue #470's *Acceptance criteria*.

**S1** — "The `**Status**:` paragraph of `odd/tasks/access-provisioning-worker-repairs.md` — read as
`awk '/^\*\*Status\*\*/{f=1} f&&/^$/{exit} f' <file>` — matches neither `not merged` nor `What remains
is the delivery`, and the same paragraph names all five merged pull requests with their merge commits.
The whole file **still** carries the phrase, as the quotation the log keeps on purpose: a file-wide
grep cannot tell an assertion from the evidence of the drift, and erasing the quotation would destroy
that evidence."

**S2** — "`odd/tasks/env-defaults-hardening.md`'s `**Status**:` paragraph names `#458` → `af7807d`,
`#459` → `c7460af` and `#460` → `7ebb21b`, and no longer names `G45`–`G47` as an open blocker of
`T6`/`T9`."

**S3** — "`T6` is stated as complete with `L8` of the worker-repairs record as its evidence, and `T9` is
stated as remaining, scoped to the two candidates that have no independent verification."

**S4** — "`bash .github/scripts/tests/odd-status-headers.test.sh` exits 0, and its summary line still
reports the same number of records plus this unit's own."

**S5** — "A new `odd/tasks/*.md` record for this unit states its durable state without a live-delivery
claim, and its log carries this request verbatim."

**S6** — "The post-merge runs the criteria above rely on are unchanged in `gh run list`: `76d9d7a`,
`abd9343`, `dbb8f42` (API CI) and `e232b17` (Docker Build Integrity) all `success`."

## Tasks

| ID | Task | Specs | Route | Commit |
|----|------|-------|-------|--------|
| T1 | Repair `access-provisioning-worker-repairs.md`: the header becomes merged state with the five merge commits, and `L11` records the delivery while `L12` records the repair and keeps the superseded sentence visible | S1 | inline | see log |
| T2 | Repair `env-defaults-hardening.md`: the header becomes merged state with the three merge commits, `T6` closes on the live run that no longer blocks, `T9` is stated with its real remainder, and `L16` supersedes the two expired claims without rewriting them | S2, S3 | inline | see log |
| T3 | The guard over the repaired tree, and over the tree that also carries this record | S4 | inline | log |
| T4 | This record, and the delivery entry once the pull request exists | S5, S6 | inline | log |

## Log

**L1 — the user's original request, verbatim**: "si dale a 1 y 3"

**L2 — the request it answers, verbatim**: "que esta pendiente?" — and the plan that request's answer
proposed, accepted by the user's `L1`: repair the two record headers now, leave the guard hole that
lets the first of them pass green as its own report.

**L3 — the state measured before the repair, each claim re-read against `main` @ `7dbd3aa`, not taken
from the previous summary**:

- `odd/tasks/access-provisioning-worker-repairs.md`'s `**Status**:` paragraph said *"delivered on the
  branch, not merged"* and *"What remains is the delivery"*. Measured: its five chained pull requests
  are merged — `#465` (`76d9d7a`, 19:31Z), `#466` (`abd9343`, 19:42Z), `#467` (`dbb8f42`, 19:48Z),
  `#468` (`e232b17`, 19:50Z) and `#469` (`7dbd3aa`, 20:31Z) — and `#469` is the pull request that
  merged the header itself, so the falsifying event was the merge and not a later commit.
- `odd/tasks/env-defaults-hardening.md`'s `**Status**:` paragraph said the work *"is delivered as the
  chained pull requests #458, #459 and #460"* — with no merge commit, so not checkable in one command
  — and that *"`T6` and `T9` are the two tasks still open, both behind the worker defects `G45`–`G47`"*.
  Measured: those three defects were closed and merged by `#465`, `#466` and `#467`, and the run `T6`
  was waiting on passes — `access-provisioning-worker-repairs.md` `L8` records
  `./docker/scripts/verify-provisioning-flow.sh dev` printing *"Access-provisioning flow verified end
  to end for dev"*.
- The guard was **green** with the first record in that state: `20 passed, 0 failed`. The reason is
  `has_delivery_evidence()` accepting any `#<number>`, and that paragraph cites `#460` — another
  unit's pull request. The hole is why this unit's `S4` can only prove the tree stays green, not that
  the guard would have caught the drift; it is a separate report.

**L4 — the repairs, as committed**:

- `access-provisioning-worker-repairs.md`: the header now reads *"merged — the five chained pull
  requests #465 (merge commit `76d9d7a`) … #469 (`7dbd3aa`, this record), 2026-10-19 … No work left in
  this unit"*. `L11` adds the delivery — branch names, labels, merge commits, the timestamps, the
  reopened issue and the four green post-merge `push` runs. `L12` records the repair and **keeps the
  superseded sentence quoted**, so the drift stays visible instead of being erased.
- `env-defaults-hardening.md`: the header names the three merges (`af7807d`, `c7460af`, `7ebb21b`),
  closes `T6` on `L8`'s live run and states `T9`'s remainder — the independent verification of
  `#459`'s and `#460`'s candidates, which no entry in that log records because `L10` covers `PR1`
  alone. `L16` supersedes the header sentence and the `T6`/`T9` rows **without editing them**, which is
  `references/feature-records.md` rule 4: repair the claims that are false now, keep the frozen
  narrative, record the repair.
- Neither record's `## Tasks` table was rewritten, and no other record was touched.

**L5 — the guard over the repaired tree and over the tree with this record** (S4):
`bash .github/scripts/tests/odd-status-headers.test.sh` printed `78 records` and `115 claimed merge
commit(s)` with `20 passed, 0 failed` after the two repairs — the count rose from 113 because the
header shape this unit writes introduces `merge commit <hash>`, which `delivery_hashes()` reads, and
the ancestry half confirmed each of the two new claims is an ancestor of `origin/main`.

**L6 — the state the next reader needs**: `T9` of `env-defaults-hardening` is the only work this repair
opens rather than closes, and it is stated on that record's header. The guard hole — a `Status`
paragraph that declines its own delivery but cites any other unit's `#N` passes — is **not** fixed
here and has no issue yet; the plan the user accepted leaves it as its own report.

**L7 — this unit's own first acceptance criterion was wrong, and it is corrected here rather than
satisfied by weakening the first check** (S1, 2026-10-19). `S1` was first written as
`git grep -n "not merged" odd/tasks/access-provisioning-worker-repairs.md` returning nothing. The repair
makes that fail, and correctly: `L12` **quotes** the superseded sentence, because
`references/feature-records.md` rule 4 keeps the frozen claim visible while superseding it. Measured
after the repair: `git grep -n "not merged"` matches `odd/tasks/access-provisioning-worker-repairs.md:162`
— the quotation, not an assertion. A file-wide grep cannot tell the two apart, so the criterion was
narrowed to the paragraph where the assertion lives:
`awk '/^\*\*Status\*\*/{f=1} f&&/^$/{exit} f' <file> | grep -cE 'not merged|What remains is the delivery'`
prints **0**. The issue body carries the corrected criterion, and it still fails if the record ever
asserts the false state again, which is what the original was protecting.

**L8 — delivery, 2026-10-19**. Published as issue **#470** — `status:approved` by the human decision
recorded there, applied after the user chose to publish — and pull request **#471**
(`chore/odd-header-repairs` → `main`, `type:docs`, `Closes #470`), carrying one commit and 180 authored
changed lines. Its two required checks pass: `Exactly one type:* label` (18s) and `A linked, approved
issue` (10s), with `mergeable=MERGEABLE` and `mergeStateStatus=CLEAN`. This entry is written **before**
the merge on purpose: the header above makes no claim about the delivery state, so the merge does not
falsify it and no follow-up commit is needed to keep this record true.

**Header repair, 2026-10-20.** The `**Status**:` paragraph above ended with *"This header makes no
claim about push or PR state."* One line earlier it names issue **#470** and the fact that the tree
passes, so the sentence disclaimed a claim it was making. Removing the sentence is the whole repair:
the header still says what the two repaired records now state, and this record's own finding — the
paragraph that sits above the delivery is the one that survives the merge — is the rule
`odd/tasks/odd-header-without-transport.md` generalises. Nothing else in this record changed, and the
closure it narrates stands.
