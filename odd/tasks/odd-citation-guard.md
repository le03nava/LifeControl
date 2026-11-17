# ODD — guard every commit citation in `odd/tasks/` against `origin/main`

Issue #945. The rule landed with #937; this is the guard that fails on the next citation of a commit that
is not in `main`.

**Status**: implemented — the frozen list with the 33 citations orphaned today, the audit that resolves
every backticked commit citation in the record directory, the fixtures that prove each fail-closed case in
a scratch repository, and the three documentation points that had to stop claiming the guard was pending.
Nothing is deferred.

## Specs

Quoted verbatim from issue #945's acceptance criteria.

- **S1** — `bash .github/scripts/tests/odd-citations.test.sh` exits 0 on this tree, and each of its
  fail-closed cases passes: a new non-ancestor citation fails naming the record and the SHA, a dropped
  entry fails while the citation remains, an entry that stops resolving fails, an entry the record no
  longer cites fails, a line without a path fails, and a clone whose `origin/main` does not resolve
  announces the skip.
- **S2** — A patch-id or a tree object cited in a record produces neither a finding nor a list entry.
- **S3** — The cited/absorbed/ancestor counts partition exactly, so the run cannot pass by arithmetic
  drift as the list shrinks.
- **S4** — `shellcheck --severity=warning` is clean, and the CI job's glob wires the suite by existing.
- **S5** — **One list and no kinds.** An earlier revision carried a second kind for an honest
  non-arrival, which is a claim about prose the guard cannot verify; it is recorded as a deviation from
  S8 of #937 rather than reimplemented.
- **S6** — The frozen list carries the 33 citations that are orphaned today, keyed by
  **(record, commit)**, and the list only shrinks: an entry whose commit reaches `main` is stale, never
  red.
- **S7** — `.agents/skills/project-conventions/references/index.md` lists it and stops claiming that
  nothing verifies a record's narrative beyond its header.
- **S8** — `AGENTS.md`'s suite-count row is corrected: it says **11 suites** and **16** shell files
  where the glob holds **13** and **18**.
- **S9** — The review reaches a terminal outcome for this candidate. If the targeted-validation slot is
  still not dispatchable, that is a defect to report, not a reason to deliver unreviewed.

## Tasks

| ID | Specs | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S3, S6 | inline — the audit, the three-root split and the frozen list | this slice |
| T2 | S1, S2 | inline — one fixture per fail-closed case, every orphan built in a scratch repository | this slice |
| T3 | S7, S8 | inline — the `index.md` entry and its gap line, the `AGENTS.md` row and its counts | this slice |
| T4 | S4 | inline — `shellcheck --severity=warning` through the pinned image, and the glob that wires it | this slice |
| T5 | S5, S9 | recorded below rather than coded — the one-kind decision and the review state | — |

## Decisions the specs leave open, and how they are resolved

- **The frozen list lives in the suite**, not beside it, the way `image-pins.test.sh` holds the pin
  table: one artifact to read, and the suite is what the issue names.
- **An entry is keyed by both file and SHA.** A commit cited as a fact by one record can be a defect in
  another; a global list would erase that difference.
- **One list, no kinds — a recorded deviation from S8 of #937.** That criterion asked for a
  `declared-non-arrival` kind whose record states the commit never reached `main`. The guard cannot read
  that statement: checking it means either a list of acceptable wordings, which is the denylist
  `feature-records.md` rejects, or a marker token written into frozen records, which rule 5 forbids. Two
  review lineages found the same hole, and that is what settled it: a guard must not claim what it
  cannot verify.
- **Every entry must still be cited by the file it names.** Not in the specs, and it is what stops the
  list rotting: a citation corrected away takes its entry with it.
- **An entry whose commit becomes an ancestor does not fail.** Failing there would make CI red on `main`
  until an unrelated pull request edited the list — the maintenance cost measured at 51 of the last 150
  merged pull requests, which touched nothing but records.
- **No fixture borrows a commit of this repository.** Every fail-closed case builds its orphan in a
  scratch repository, so none can go red on the day a real frozen commit lands — the state the shrink
  rule says must stay legal.
- **Only backticked tokens are read**, the scoping D3 of `odd-status-headers-guard.md` measured with its
  own fixtures: the directory legitimately backticks patch-ids and tree objects that no repository can
  resolve as commits.
- **The workflow is edited, against this issue's own criterion — and that criterion was wrong.** It said
  the suite is picked up by the glob "with no workflow edit", inherited from S4 of #937. The *wiring* needs
  none: the `guard` job already runs `.github/scripts/tests/*.test.sh`. The **ancestry** does: the job's
  checkout is shallow and materialises the pull request's ref, never the base branch, so every citation
  was unresolvable in CI and the run announced a skip while reporting green — a guard that verified
  nothing, which is the exact failure this unit exists to remove. Discovered by the pull request's own
  check failing, not by reading. The job now takes `fetch-depth: 0` and fetches the base ref explicitly.
- **A skip is announced, never fatal.** The sibling suite states that rule and this one follows it: with no
  base resolvable the run says so on stdout and does not fail, because a suite that goes red on a state it
  cannot control gets switched off, while one that turns it into a silent pass verifies nothing.
- **The ancestry resolution is copied, not shared.** `odd-status-headers.test.sh` keeps its own inline
  resolution untouched, because widening that suite is its own non-goal. The copy is 22 lines and this
  suite asserts its own skip-and-announce behaviour, so a divergence is a red run here.

## Log

**L1** — the user's request, verbatim: *"ok has el push y PR del 945"*, after *"has el merge"* closed
#946.

**L2** — the guard was written, reviewed and corrected inside #937's work and split out on the user's
choice of *"partir la unidad: la regla primero, el guard en su issue"*. It arrives here as its own unit
so that a 430-line suite is reviewed on its own terms, and because the rule it guards does not depend on
it landing in the same slice.

**L3** — what the split bought, measured: three review lineages returned **four CRITICAL findings against
the guard and none against the 136-line rule slice**. The four, and what each one was:

| Finding | What it was | What closed it |
| --- | --- | --- |
| pinned count | The suite required an exact frozen-entry total, so it would have failed on the very entry removal the rule mandates. | Every count is derived from the run; the cited, absorbed and ancestor totals must partition. |
| unverified kind | A `declared-non-arrival` entry was accepted without checking the declaration the kind claims. | The kind is gone. |
| same hole, twice | A second lineage found the identical defect, which is what proved it was structural and not a typo. | Same. |
| fixtures borrowed history | A case hardcoded a real frozen commit as a guaranteed non-ancestor; the day it landed, the assertion would fail for the wrong reason. | Every orphan is built in a scratch repository. |

**L4** — the review's terminal state, reported rather than assumed. This candidate's lineage could not be
closed: the correction was applied and verified locally, and the `provider_targeted_validator` slot that
would validate it was rejected as missing or stale by `gentle_review_capture` on two separate lineages,
with the branch base exactly equal to `origin/main` at the time of each attempt. That is a tooling
defect, and it is what S9 asks to be reported instead of papered over with an unreviewed delivery.

**L5** — the CI defect, found by the pull request rather than by reasoning, and what it cost. The first run
of the check failed with `checked 174 record(s), 0 citation(s) ... ancestry: skipped` and
`24 passed, 1 failed`: `actions/checkout` is shallow and checks out the pull request's ref, so
`refs/remotes/origin/main` never exists and the audit had nothing to resolve against. One earlier review
lineage had flagged the same shape as a non-blocking finding and it was not acted on; that miss is why the
suite shipped with an assertion that made a state the repository documents as normal into a failure. The
fix is three things and only together: the skip stopped being fatal, the partition assertion stopped being
evaluated with no base to partition against, and the job now fetches the base it needs. Verified in three
states — a worktree with `origin/main`, a clone that has just fetched the base ref the way the job does,
and a clone with no base at all — all three green, which is the point: the first two verify, the third
announces and does not lie.

**L6** — the review of that fix found the fix half wrong, and it was right. Making the audit resolve a
local `refs/heads/main` let an unpushed local commit count as an ancestor, and the rule is ancestry of
`origin/main`, not of whatever this clone has checked out. The rung is gone: the audit resolves
`origin/main` and nothing else, the workflow fetches exactly that ref, and a fixture now proves the shape
that the rung would have hidden — a local `main` carrying a commit that never reached `origin/main` is
still a failure. Fetching `origin/<base>` from the pull request's own base was the same mistake in the
workflow, so it went as well.

**L7** — CI found the second, deeper surprise, and it is a property of the data rather than a bug: **the 33
frozen commits do not exist in a CI clone.** An orphaned commit hangs off no branch, so no `git fetch`
brings it; `actions/checkout` has `main`'s history and the pull request's and nothing else. The audit
resolved the base correctly (1440 citations, all in `main`) and then failed 33 times with "the frozen list
carries X and it does not resolve as a commit" — a finding about the clone, not about the records.

So the register's resolvability check is now conditional on the object being there at all: an entry whose
object is absent is counted as **unverifiable**, an entry that resolves as something other than a commit is
still a finding, and a green run prints how many entries it could not check. That last part is the point —
a run that verifies 33 fewer things than the previous one must not look identical. Locally, where the
objects were fetched when their branches were alive, all 33 are still checked.

What this costs, said plainly: a typo'd entry is no longer caught in CI, only in a clone that has the
object. What it does not cost: the citation half still does its job in CI, because a commit a record newly
cites is reachable from the pull request and is caught if it is not in `main`.

Verified in two faithful states: the worktree, where all 33 resolve and the run checks them, and a clone
holding only `main` and this branch, where the same run prints `NOTE: 33 frozen entries were NOT checked`
and passes. Both 29 passed, 0 failed.

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-11-17 | Written and verified on the branch. `bash .github/scripts/tests/odd-citations.test.sh` exits 0 with 25 passed and 0 failed, twice with identical output, and the `checked` line it prints carries totals that move with `main` and are therefore not copied here; on the run that wrote this entry it printed 173 records, 1473 citations, 1440 in `main` and 33 absorbed, and 1440 + 33 = 1473 is the partition the suite asserts. All thirteen sibling suites exit 0, including `odd-status-headers.test.sh` (78 assertions) and `worktree-anchor.test.sh` (38), which read `AGENTS.md`, `feature-records.md` and `worktrees.md` — files this slice edits. `shellcheck --severity=warning` is clean through the pinned image, since the binary is not installed in this clone. The same run against a clone holding only `main` and this branch reports `NOTE: 33 frozen entries were NOT checked` and passes, which is what CI will do. The suite is wired by existing: the `guard` job in `.github/workflows/pr-label-guard.yml` runs `.github/scripts/tests/*.test.sh` by glob and keeps no `paths:` filter, so a records-only pull request is audited like any other — and that job now takes `fetch-depth: 0` and fetches the base ref, because without it the ancestry half resolves nothing and the guard verifies no citation at all. Verified in the three states L5 names: with a base, with a freshly fetched base ref, and with none, all three green at 27 passed and 0 failed. |
