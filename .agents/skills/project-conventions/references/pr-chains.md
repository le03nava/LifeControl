# PR Chains and Base Branches

Read this before merging any PR whose branch is the base of another open PR, and before deleting a
branch that a chain still uses.

## The failure mode

Deleting a branch that an open PR uses as its **base** closes that PR. The close is not the problem.
The problem is that it cannot be undone.

| # | Mechanism | Consequence |
| --- | --- | --- |
| 1 | The documented provider behavior is to retarget open PRs based on a merged PR's head branch to that PR's base. | The documented path is the safe one, but do not rely on it: the observed result is a base-branch-deleted event followed by a plain close, with no retarget attempted. |
| 2 | A closed PR cannot be retargeted; the base change is rejected. | The child cannot be pointed at `main` after the fact. |
| 3 | The restore action applies to a pull request's **head** branch only. | A deleted base branch has no native restore path. |
| 4 | The web interface hides its delete-branch action while another open PR references the branch; the command-line merge path deletes the ref directly. | The guard exists only where it is easiest to respect, and is bypassed by the path that is easiest to script. |

Upstream tracker: [cli/cli#14223](https://github.com/cli/cli/issues/14223), reported as a
platform-side defect. It is cited as an external reference because no local documentation covers
provider behavior (Hard Rule 3).

Recovery exists on paper and is unreliable in practice: recreate the deleted ref from a commit that
is already on `main`, reopen the child, then retarget it. Reopening can still fail when the head
branch moved while the PR was closed, and at that point the PR identity is lost and the work has to
be re-presented as a new PR. Treat this as a last resort, never as the plan.

The recipe has a verified precedent, so run it in this order rather than improvising:

```bash
git push origin <old-tip-sha>:refs/heads/<deleted-base-branch>   # 1. the base ref must exist again
gh pr reopen <child>                                            # 2. reopen fails while the base is gone
gh pr edit <child> --base main                                  # 3. retarget the reopened PR
git push origin --delete <deleted-base-branch>                  # 4. delete the temporary ref again
```

Executed successfully on 2026-09-23 (PR #156, `gh` 2.101.0): the PR number, body, commits and diff
survived intact, and the diff stayed at its pre-close size because both bases already contained the
parent slice. Three prerequisites decide whether it works. Step 1 must recreate the ref at the **old
tip**, not at the current one. Step 2 fails first while the base ref is missing
(`GraphQL: Could not open the pull request`). Step 3 must run while the child is open again, because a
closed PR rejects a base change with `Cannot change the base branch of a closed pull request` — that
is exactly the deadlock that makes the failure permanent when the base ref cannot be recreated.

## Invariants

| # | Rule | Why |
| --- | --- | --- |
| 1 | Never delete the base branch of an open PR | The close is immediate and the retarget is impossible afterwards |
| 2 | Retarget before deleting, never after | The only order that works; the reverse order has no remedy |
| 3 | Freeze the merge order before merging the first PR of a chain | Each child's diff carries its parent's work until the parent lands on `main`, so the reviewer must know which slice is being read |
| 4 | Keep automatic head-branch deletion off while a chain is open | It removes the human decision at the moment the branch still has dependents |
| 5 | Only the **last** slice carries the closing keyword; every earlier slice carries `Refs #N` beside a `Part of a chain: #N` declaration | A slice is retargeted to the default branch before it merges, and there a bare `Refs` is a missing closing keyword while a `Closes` closes an issue the later slices still need |

## The issue reference across a chain

A slice's reference changes meaning twice in its life, and the guard has one verdict for each state.
The unit that produces the rule, and the chain that measured it, are issue #598 and #572.

| State of the slice | Body carries | Guard verdict |
| --- | --- | --- |
| Opened against its parent branch | `Refs #N` | Passes — closing keywords are ignored off the default branch |
| Opened against its parent, as the last slice | `Closes #N` | Passes, and closes the issue as soon as it merges. Use it only when no later slice still needs the issue |
| Retargeted to the default branch, mid-chain | `Refs #N` + `Part of a chain: #N` | Passes, because the declaration says the closing keyword is deferred to the last slice |
| Retargeted to the default branch with no declaration | `Refs #N` | Fails: a closing reference is required, or the declaration that explains its absence |

The declaration is number-scoped — the number must be one the body references, so it cannot unlock a
`Refs` to an issue nobody declared — it is written in the documented form, colon included, and starts
a line of its own, so neither a sentence that happens to contain the phrase nor a four-space indent
(a Markdown code block is documentation) declares anything, and it is read from prose only, so quoting
it in a fenced block or a code span does not count either. Off the default branch it is ignored, and
beside a closing keyword it changes nothing. A body that carries the declaration and no reference
still fails: naming an issue is not linking it.

One limit is worth stating where the rule is written, because the gate cannot read intent: the
declaration is the author's assertion that the slice is mid-chain, exactly as `status:approved` is a
maintainer's assertion that the issue may be built. The gate verifies the assertion's shape and the
issue's state; it cannot verify that a fourth slice really exists, so a body can still choose the
wrong keyword on purpose and nothing here will catch it.

Retargeting stays the maintainer's action; nothing here automates it, and the ordering rules above are
what decide when it is safe.

## Pre-merge guard

Run this before every merge on a chain, and before any manual ref deletion:

```bash
gh pr list --state open --base <branch> --json number,title
```

| Result | Action |
| --- | --- |
| Empty | The branch has no dependents; deleting it is safe |
| Non-empty | Retarget each listed PR first: `gh pr edit <n> --base main`. Merge the parent only once the list is empty. |

Read the provider-side setting that deletes head branches automatically, and keep it off while a
chain is open. It is a provider setting, not a repository fact:

```bash
gh api repos/{owner}/{repo} --jq .delete_branch_on_merge
```

## Order of operations

| Step | Command | Why this order |
| --- | --- | --- |
| 1 | `gh pr list --state open --base <parent-branch>` | Establish whether the parent has dependents |
| 2 | `gh pr edit <child> --base main` | Remove the dependency while the child is still open and retargetable |
| 3 | `gh pr merge <parent> --delete-branch` | The deletion now has no victims |
| 4 | Re-run the guard before the next parent | A chain merges one parent at a time; the guard is per-parent, not per-chain |

The variant that removes the failure mode entirely: merge every PR in the chain without deleting
branches, then delete them once the chain is complete.

## History inside a chain

A chain rewrites history at the one moment a single branch never does: while other pull requests are
based on it. The rule that decides every case here is already written once, in
`references/worktrees.md` (*Sync the branch with `main`*): a branch is rewritten only while it is
**unpublished**. What a chain adds is not a second rule but a second ref — *unpublished* then has to be
true of every branch the operation would move, not only of the one that is checked out. This section is
that application, and it restates nothing else.

### Rewriting a parent

Rebasing the parent of a chain obliges rebasing every child onto the new parent tip. The named mechanism
is `git rebase --update-refs <new-base>`, **and it is legal only while every branch it would move is
unpublished.** The command moves any ref whose commits fall inside the rewritten range, silently and
without asking, and it moves the **local** ref only: nothing is pushed for you. A published descendant
inside that range is therefore a rewrite of published history, which the rule above forbids outright, and
it changes the diff under reviewers who already approved it. Measured available here: `git version
2.51.0` (`--[no-]update-refs` since 2.38).

When any descendant is published, the parent **is not rebased**: the chain goes by merge. Turning the
behaviour on by default (`rebase.updateRefs`) is machine configuration, not repository policy, and the
precondition above — not the default — is what makes the command safe.

### Bringing a child up to date

A child of a chain has two bases and one rule, and the rule is `references/worktrees.md`'s: against
`main`, a child is brought up to date exactly as any other branch is, which is why this file does not
repeat the mapping between published and unpublished — it reads it there.

Against its **parent** the child uses the sequence `## Order of operations` already prescribes, and no
other: the parent is merged **without deleting its branch**, then the child is retargeted to `main`
(`gh pr edit <child> --base main`), and only then merged. Rebasing a child is a pre-publication move only,
for the same reason as above: a published child's commit ids are what a reviewer read, and a rewrite
changes the diff it approved.

### Where the chain's evidence is committed

The record's evidence entry has two requirements in a chain, and satisfying one does not satisfy the
other:

| Requirement | Value | Consequence when it is missed |
| --- | --- | --- |
| **Carrier** | the chain's trailing tracker pull request — the last of the chain to land, and the one carrying no code of its own | the entry rides a code slice that a later rebase rewrites, so the record cites commits that left the chain |
| **Timing** | committed **after the penultimate pull request merged** | a record written while earlier slices were still open does not know their merge commits, so it describes a state of `main` that never existed |

The two are independent: the tracker branch may be opened on an empty commit **before the first slice
lands**, so its pull request exists early and still carries no code — what it cannot do is carry the entry
before the chain's last rewrite. Both shapes are measured in this repository rather than asserted:
`odd/tasks/employee-access-provisioning.md:697` (the record landed as PR #307, after slice S7),
`odd/tasks/hr-org-structure.md:391` (tracker #318, after eight code slices) and
`odd/tasks/angular-22-upgrade.md:220` (the tracker branch opened on the empty commit `e0571d5`).

Because nothing follows the tracker, its commits are final when it lands. That is what makes the rule of
`references/worktrees.md` stateable for a whole chain: the evidence entry is written after the **last**
history rewrite of the chain, and nothing rewrites after it.

### What is not a gate

No suite is added here. The evidence half is already enforced by
`.github/scripts/tests/odd-citations.test.sh`: it reads every record in `odd/tasks/` and fails a citation
that does not resolve as an ancestor of `origin/main`, so a record citing a parent commit rebased
afterwards fails CI without this section contributing anything. The history half — the unpublished
precondition — is a **review-time convention**, the same class as the one-worktree-one-unit rule, and the
gap it leaves is stated in `## Gaps`.

## Anti-patterns

| Anti-pattern | Consequence |
| --- | --- |
| Merging a parent with branch deletion while its children still target it | Every child closes and none can be retargeted |
| Retargeting a child after the parent's branch is gone | Impossible: the PR is already closed |
| Enabling automatic head-branch deletion during a chain | The deletion fires without the guard being consulted |
| Deleting a base branch by hand | Identical outcome to the command-line merge path |
| Merging a chain out of order | Each child's diff keeps carrying its parent's work, so the reviewer reads more than the slice |
| Rebasing a parent while a descendant is published | `--update-refs` moves that ref silently, so the branch is rewritten under reviewers who already approved it and the unpublished window was never open for it |
| Writing the record's evidence entry into a mid-chain slice | The entry cites merge commits the later slices do not have yet, and any rebase of that slice orphans the citation — the failure `odd/tasks/tax-rate-write-honesty.md` already froze once |

## Gaps

- No CI check and no repository setting prevents deleting a base branch that an open PR depends on;
  it is a review-time convention, exactly like the one-worktree-one-unit rule.
- The provider setting that automates head-branch deletion is unverifiable from the repository; read
  it with the command above.
- Whether the provider retargets dependents on branch deletion is provider behavior, not repository
  policy, so it can change without a commit here.
- Nothing detects that a child is based on a **stale parent tip**. `gh pr list --state open --base
  <branch>` reads dependents, not whether a child's base still contains the parent's commits, and there
  is no local record of the tip a child was cut at. So a parent rebased while a descendant was published
  is caught by a reviewer reading commit ids, not by a gate; stated as a limit, never as covered.
