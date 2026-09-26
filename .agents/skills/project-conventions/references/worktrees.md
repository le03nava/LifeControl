# Worktrees and Workspaces

Isolation model for parallel work. Read this before creating a worktree, running two writers at once, or splitting agent work across panes.

## Three distinct concepts

Do not treat these as one thing. Confusing them is the root of most worktree incidents.

| Concept | What it is | Where it lives |
| --- | --- | --- |
| Herdr workspace | A cwd plus its tabs and panes. UI and session state. | Herdr `session.json` |
| git worktree | An extra checkout of the same repository sharing one object store. | Any path on disk |
| Herdr worktree workspace | Both at once: Herdr creates the git worktree and links it to the primary workspace as a group. | Both |

A Herdr workspace can exist without being a worktree. That is fine for ephemeral shells. It is **not** isolation: parallel writers need the git worktree.

## Invariants

| # | Rule | Why |
| --- | --- | --- |
| 1 | One branch, one worktree | Git enforces it; two checkouts of a branch corrupt each other's index |
| 2 | One worktree, one unit of work | A reused worktree accumulates unrelated branches and its artifacts become unattributable |
| 3 | The anchor stays on `main`, clean, with no writers | It is the reference for `fetch`, `log`, and `rebase`; uncommitted work there is one Git command away from loss |
| 4 | One worktree per Herdr workspace | Mixing worktrees in one workspace defeats the isolation |
| 5 | One agent per pane, one pane per worktree | Two agents in one cwd overwrite each other at the filesystem level; Git cannot prevent it |
| 6 | The worktrees directory lives outside the repository | Keeps the repo tree clean and needs no `.gitignore` entry |

## Path and naming

| Item | Value |
| --- | --- |
| Directory | `~/workspace/<repo>-worktrees/<slug>` — sibling of the repository, never inside it |
| Slug | The branch name with `/` replaced by `-`: `feat/x` → `feat-x` |
| Anchor | `~/workspace/<repo>` |

The directory slug must equal the branch slug. A directory that does not name its branch is unreconstructable after a few weeks.

## Create

Run from a clean anchor on `main`:

```bash
cd ~/workspace/<repo> && git checkout main && git pull

herdr worktree create \
  --cwd ~/workspace/<repo> \
  --branch <branch> \
  --base main \
  --path ~/workspace/<repo>-worktrees/<slug> \
  --label "<branch>" \
  --no-focus
```

- `--branch` accepts an existing branch and checks it out **without modifying it**. Verify the branch SHA before and after when rescuing existing work.
- **`--branch` is not optional in practice.** Omitting it does not fail: the command succeeds and names the branch `worktree/<random-slug>` — `worktree/green-harbor-f423` was one, measured on 2026-09-26. The worktree then carries a branch its directory does not name, which is the unreconstructable state the naming rule above exists to prevent, and nothing in the returned JSON flags it.
- `--base` applies when the branch does not exist yet.
- `--no-focus` keeps the caller's context. Use focus only when the user asked to switch.
- The command returns JSON. Read `workspace_id`, `tab_id`, and `pane_id` from it; never predict them.

Attach an existing checkout that has no linked workspace:

```bash
herdr worktree open --cwd ~/workspace/<repo> --branch <branch> --no-focus
```

## Verify

```bash
git worktree list
herdr worktree list --cwd ~/workspace/<repo>
```

`git worktree list` is authoritative for topology. `herdr worktree list` adds the linkage: a worktree with `open_workspace_id` set is a linked workspace in the group.

Confirm the isolation model holds: the anchor has a `.git` **directory** (the object store, including `.git/worktrees/<slug>/`); each linked worktree has `.git` as a **text file** pointing at the anchor.

## Cleanup order

Order matters. Closing the workspace after removing the worktree leaves a workspace pointing at a missing path.

```bash
herdr workspace close <workspace_id>            # 1. close the Herdr workspace
git worktree remove <path>                      # 2. remove the git worktree
git worktree prune                              # 3. clear orphaned metadata
```

- **`herdr workspace close` does not remove the worktree, so steps 2 and 3 are not optional.** Measured on `herdr` 0.9.1 (2026-09-25): a probe worktree survived the close with its directory, its `git worktree list` entry, its local branch and its `.git/worktrees/<slug>/` metadata intact — only the workspace entry disappeared from `session.json`. A closed workspace is not a clean checkout.
- `git worktree remove` rejects modified and untracked files. It **ignores ignored files**, so it succeeds despite `.env`, `secrets/`, or other ignored content. Verify ignored content is regenerable before removing; `git status` will not warn you.
- `--force` discards uncommitted work. Use it only after confirming there is none.
- Deleting the directory by hand leaves metadata in `.git/worktrees/`. Always finish with `git worktree prune`.

**`herdr workspace close --group` closes the primary workspace and every linked worktree workspace.** Never add it to bypass a close error.

### Deleting a branch a worktree holds

`gh pr merge --delete-branch` is **worktree-aware**, so it does not simply fail on a branch a worktree
holds: it inspects `git worktree list --porcelain` and its action depends on *where* the branch is
checked out. On `gh` **2.101.0** the decision lives in `deleteLocalBranch`'s switch over `headWorktree`
(`pkg/cmd/pr/merge/merge.go`), and the removal it performs is `git worktree remove -- <path>`
(`git.Client.WorktreeRemove`, no `--force`).

| Where the head branch is checked out | What `gh pr merge --delete-branch` does |
| --- | --- |
| Nowhere | Deletes the remote ref, deletes the local branch |
| A **linked** worktree, command run anywhere else | Deletes the remote ref, **removes that worktree**, then deletes the local branch |
| The worktree the command runs in | Deletes the remote ref, **skips** the local delete, prints the manual follow-up |
| The **main** worktree, command run in a linked one | Deletes the remote ref, **skips** the local delete |
| The main worktree and the command runs there, base branch held by another worktree | Deletes the remote ref, **skips** the local delete |

The **second row is the one that surprises people, and it was measured rather than read** (2026-09-25,
`gh` 2.101.0, PR #177 in this repository): a pull request was merged from the anchor while its branch
was checked out in a linked worktree, and by the time the operator ran `git worktree remove <path>`,
git answered `fatal: '<path>' is not a working tree` — the registration was already gone, and so were
the directory and the local branch. Nothing in that merge's output said so.

Two properties of that removal decide whether you can rely on it:

- **It is not forced.** `git worktree remove` refuses a worktree that has modified or untracked files
  (`fatal: '<path>' contains modified or untracked files, use --force to delete it`, exit 128), and
  `gh` then warns `Could not remove worktree <path>; skipping local branch delete: <err>` and leaves
  both the worktree and the local branch in place. Measured on the git half (2026-09-25): a probe
  worktree carrying one modified tracked file and one untracked file survived `git worktree remove`
  intact. So uncommitted work is not destroyed by this path — but a **clean** worktree is removed
  without asking, which is why "clean" is not the same as "still needed".
- **An open workspace is left dangling.** Removing that worktree from under an open Herdr workspace
  produces exactly the failure this page's cleanup order exists to prevent — a workspace pointing at a
  missing path — arriving from the merge side instead of the cleanup side. Close the workspace before
  merging, or merge before opening a workspace on that worktree.

| Symptom | Reality |
| --- | --- |
| `error: cannot delete branch '<branch>' used by worktree at '<path>'` | You ran `git branch -d`/`-D` by hand. `gh` 2.101.0 does not produce it for a linked worktree: it removes the worktree or skips with its own message. On older `gh` it was the symptom of a partial failure — check the remote ref instead of reading the error |
| `fatal: '<path>' is not a working tree` | The registration is already gone, and `gh pr merge --delete-branch` is the likely cause (second row above). There is no metadata left to clean up either |
| `fatal: '<path>' contains modified or untracked files, use --force to delete it` | `gh` reported that it could not remove the worktree and skipped the local delete. The worktree and the branch both survive; decide what to do with them |
| The pull request shows as merged | True and unrelated: the merge landed before the deletion was attempted |

**Version note (measured 2026-09-23, corrected 2026-09-25).** The remote outcome is
`gh`-version-dependent, so treat the remote ref as unverified until you check it. On `gh` **2.45.0**
the merge aborted *before* the remote deletion, and `git ls-remote --heads origin` confirmed the remote
ref survived. On `gh` **2.101.0** the merge and the remote deletion both succeed. Where the earlier
note generalized the last step — *"only the local deletion is skipped … both outcomes leave the local
branch and the worktree untouched"* — that holds for the third row of the table above (the command
runs in the worktree that holds the branch) and **not** for the second (a linked worktree, command run
elsewhere), where the worktree is removed and the local branch is deleted. The 2026-09-23 session's
invocation shape is not recorded, so the earlier note is superseded only where it generalizes, not
retro-attributed.

**The command's output is not evidence either way.** In the runs measured here, `gh pr merge --delete-branch`
produced no visible output at all while its stdout was piped — including the run that removed a
worktree — while the same binary did print to stderr in a later invocation. Read `git worktree list`
and the refs, and conclude nothing from silence.

Three ways to a clean end state, depending on what you need: remove the worktree before merging; merge
from inside the worktree when you intend to keep it (the third row above — `gh` then skips the local
delete and hands you the commands); or run the cleanup order above and delete the branch yourself, then
realign the anchor:

```bash
herdr workspace close <workspace_id>
git worktree remove <path>
git worktree prune
git branch -d <branch>
git push origin --delete <branch>
git merge --ff-only origin/main
```

Verify the deletion instead of trusting the merge output:

```bash
git ls-remote origin <branch>                                            # empty once the ref is gone
gh api "repos/{owner}/{repo}/branches?per_page=100" --jq '.[].name'
```

## Trust requirement

Pi loads project skills from `.agents/skills/` in the `cwd` and its **ancestor directories**, and only after the project is trusted. Trust resolves against the folder **or a parent folder** in `~/.pi/agent/trust.json`.

A worktree is not a child of the repository, so a trust entry for the anchor **does not cover it**. Without an entry covering the worktrees directory, `.agents/skills/` is not loaded inside any worktree.

| Entry | Covers |
| --- | --- |
| `~/workspace/<repo>` | The anchor only |
| `~/workspace/<repo>-worktrees` | Every current and future worktree of that repository |

Prefer the parent entry. Trusting a project lets Pi load project settings, install packages, and execute project extensions, so grant it only for directories dedicated to worktrees of an already trusted repository.

## Anti-patterns

| Anti-pattern | Consequence |
| --- | --- |
| Two agents in one worktree | Filesystem-level overwrites; Git cannot recover them |
| Anchor on a feature branch with uncommitted work | Any Git operation on the group can destroy it |
| Worktrees directory inside the repository | Requires `.gitignore`; `git status` noise; accidental commits |
| `herdr worktree remove` without a workspace ID | Fails: that command requires a linked workspace. Use `git worktree remove` |
| Removing the worktree before closing the workspace | Workspace pointing at a missing path |
| Assuming `node_modules`, `build/`, or `.gradle/` are shared | Every worktree needs its own install and build |
| Merging a PR with branch deletion while a worktree still holds the branch | On `gh` 2.101.0 a **clean** linked worktree is removed and its local branch deleted, silently, with no output and no question asked; an older `gh` fails the local deletion instead. Neither outcome is announced: check `git worktree list` and the refs |
| Trusting a merge command's output as proof the branch is gone | A live remote ref looks exactly like a deleted one until it is queried |

## Verifying a background process

`pgrep -f <pattern>` matches every command line containing the pattern, **including the shell that runs the check** when the pattern sits in its own command line. `bash -c "pgrep -f 'deploy.sh dev start'"` therefore always reports the job as still running. Background builds and cleanups are routine here, so a false "still running" stalls the next step while a false "done" reads a half-written log.

| Do | Don't |
| --- | --- |
| Capture the PID at launch — `./script.sh & PID=$!` — then test it with `kill -0 $PID` or wait on `wait $PID` | Trust a bare `pgrep -f 'script.sh args'` issued from a shell whose own command line contains `script.sh args` |
| Break the self-match with a character class: `pgrep -f '[d]eploy.sh dev start'` | Read an empty `pgrep` output as proof the job finished |
| `ps -eo pid,etime,cmd \| grep '[d]eploy.sh'` when the PID was not captured | Grep the process table without the bracket, which matches the grep itself |

## Gaps

- No automated guard enforces one-worktree-one-unit; it is a review-time convention.
- The worktrees directory convention is per-repository. Herdr's `[worktrees] directory` setting is a single fixed directory, not a per-repository template, so `--path` must be passed explicitly on every create.
