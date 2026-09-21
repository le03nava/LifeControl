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

- `git worktree remove` rejects modified and untracked files. It **ignores ignored files**, so it succeeds despite `.env`, `secrets/`, or other ignored content. Verify ignored content is regenerable before removing; `git status` will not warn you.
- `--force` discards uncommitted work. Use it only after confirming there is none.
- Deleting the directory by hand leaves metadata in `.git/worktrees/`. Always finish with `git worktree prune`.

**`herdr workspace close --group` closes the primary workspace and every linked worktree workspace.** Never add it to bypass a close error.

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
