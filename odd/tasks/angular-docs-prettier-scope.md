# ODD task: angular-docs-prettier-scope

**Status**: merged — PR #127 (`chore/angular-docs-prettier-scope` @ `f576c3460`), 2026-09-20. No work left.
**Feature**: `angular-docs-prettier-scope`
**Type**: tooling / environment incident fix (not product work)
**Branch**: `chore/angular-docs-prettier-scope` · **Base**: `main` @ `c9630ec`
**Local doc**: `odd/tasks/angular-docs-prettier-scope.md` (gitignored)
**Requested by**: the user, after the second occurrence of the incident ("ok arreglemos primero lo del incidente, que podemos hacer para que no pase?").
**Decision**: option 1 of 4 presented — `.prettierignore` scoped to `*.md`. Locked with the user, 2026-09-20.

## Problem

`life-control-app-angular/AGENTS.md` gets rewritten end to end, outside the agent's turn, every time it is edited. Observed twice (229+/161−, 390 lines each time). First occurrence blocked a rebase and had to be stashed; second occurrence was discarded as noise.

**Root cause (verified, not guessed)**: pi-lens does not format. Its post-write pipeline shells out to `npx prettier --write $FILE` (`pi-lens/dist/clients/formatters.js:738`) and selects prettier for a file when the nearest `package.json` carries a `prettier` field (`formatters.js:524`) — which `life-control-app-angular/package.json` does. `.md` is in that formatter's supported extension list (`formatters.js:772`). `format.mode` defaults to `"deferred"`, so the rewrite lands at `agent_end`, after the agent already closed its turn. The file is not prettier-clean (`printWidth: 100`, `arrowParens: always`, aligned tables), so prettier re-emits the whole file.

**The actual defect is a missing scope boundary**: the package has no `.prettierignore`, so prettier considers the package's docs its own. `AGENTS.md` is not covered by `lint-staged` (which only globs `src/**`), evidence that the maintainers never intended prettier to touch docs.

## Two candidate fixes ruled out with evidence

1. **`.pi-lens.json` with `"ignore": ["AGENTS.md"]`** — does NOT work. That key only feeds jscpd, the LSP walk, tree-sitter and the source-filter (`jscpd-client.js:124`, `lsp/index.js:626`, `source-filter.js:405`, `pipeline.js:97`). The format dispatch never consults it.
2. **`"format": { "enabled": false }`** — works, but resolves per path (`getFlag("no-autoformat", filePath)`) and the finest granularity is a per-directory `.pi-lens.json`. Placed at the package root it would disable autoformat for all of `src/**`, not just docs. Only affects pi-lens; a manual prettier run or the pre-commit hook still rewrites docs. Too blunt.

## Scope

New `life-control-app-angular/.prettierignore` containing `*.md`, plus a short rationale comment. One new subsection in `life-control-app-angular/AGENTS.md` recording the boundary so a future agent does not delete the ignore file as "unused".

Effect: `AGENTS.md` and `README.md` become off-limits to prettier; `src/**` keeps formatting exactly as today.

## Non-goals

1. Reformatting `AGENTS.md`. Docs stay hand-formatted; that is the point.
2. Touching the other 9 markdown files in the repo. The root `AGENTS.md` is NOT affected: the repo root has no `package.json` and no prettier config, so pi-lens never selects prettier for it. `life-control-api/AGENTS.md` and `backstage/**` use different toolchains.
3. Adding build-output ignores (`dist`, `coverage`) the way `backstage/.prettierignore` does. Those are already covered by the package `.gitignore` and by prettier's fallback to it.
4. Changing pi-lens configuration at any tier.

## Verification plan

1. **Static matrix, with the installed prettier 3.9.6 and the exact invocation shape pi-lens uses (`prettier --write <file>`, never `.`)**:
   - `prettier --write AGENTS.md` → no-op, exit 0, byte-identical file.
   - `prettier --write README.md` → no-op, exit 0.
   - `prettier --check AGENTS.md` → "All matched files use Prettier code style!", exit 0.
   - A `src/**` file is still formatted (the ignore is not over-broad).
   - **Regression guard**: creating `.prettierignore` must NOT disable prettier's fallback to `.gitignore`. Prove with a scratch project in `/tmp`: a path ignored only by `.gitignore` stays ignored.
   - Note the asymmetry to keep in mind: an ignored path passed explicitly is a clean no-op for prettier, but `formatters.js:792` documents that **oxfmt** exits 2 on an ignored path and pi-lens reads that as a formatting failure. Prettier is safe here; do not generalize the fix to a formatter that isn't.
2. **End-to-end, in band**: after the ignore file exists, edit `AGENTS.md` to add the rationale subsection and confirm the post-write pipeline leaves the file with only the intended diff — no whole-file re-emit. This is the real proof, because it exercises pi-lens's own pipeline rather than prettier in isolation.
3. Confirm the working tree ends with exactly the two intended files changed.

## Relevant files

- `life-control-app-angular/.prettierignore` (new)
- `life-control-app-angular/AGENTS.md` (one new subsection)
- Read-only evidence: `pi-lens/dist/clients/formatters.js`, `pi-lens/dist/clients/language-profile.js:114` (`TOOL_MARKERS_BY_RUNNER.prettier = [".prettierignore"]`), `pi-lens/docs/globalconfig.md` (mutation controls + `ignore`), `backstage/.prettierignore` (in-repo precedent for a package-level prettier ignore)

## Results

**Delivered** as commit `1d5efb5` on `chore/angular-docs-prettier-scope` (base `main` @ `c9630ec`). Range vs `main`: **2 files, +14−0** — `life-control-app-angular/.prettierignore` (new, 6 lines) and 8 lines in `life-control-app-angular/AGENTS.md`. Not pushed, no PR (the user's decision).

All plan items verified, plus two regressions the plan did not name:

| Check | Result |
| --- | --- |
| `prettier --write AGENTS.md` / `README.md` | no-op, exit 0, **byte-identical hashes** (before: rewritten in 28ms) |
| `prettier --check AGENTS.md` | exit 0 |
| Deliberately unformatted `.ts` inside the package | **still formatted** → the ignore is not over-broad |
| Package prettier options still resolve | yes — `singleQuote` applied, `printWidth: 100` respected |
| `.prettierignore` not swallowed by git | `git check-ignore -v` → not ignored |
| `.gitignore` fallback survives (scratch project) | intact |
| **End to end through the real pipeline** | editing `AGENTS.md` produced **exactly the 8 intended lines**, not a whole-file re-emit |
| Working tree == committed bytes | yes, md5 `b9063523…` both sides |

**Two findings that changed the shape of the fix:**

1. `prettier --check AGENTS.md` now says *"All matched files use Prettier code style!"* — because the file is **ignored**, not because it complies. The file is still deliberately not prettier-clean. This is the one signal that could make a future reader delete the ignore file as "already clean", so the `AGENTS.md` subsection names it explicitly. `--check` cannot distinguish ignored from clean in general; only `--write` plus a hash can.
2. The write pipeline reported `✓ Markdown clean` with no formatting-failure signal, so an ignored path does not trip the `oxfmt`-style exit-2 failure mode documented at `formatters.js:792`. Prettier is safe to ignore here; a different formatter would need its own check.

**Incidental confirmation of the root cause**: `lint-staged` printed *"could not find any staged files matching configured tasks"* on the commit, independently confirming that `AGENTS.md` is outside its `src/**` scope.

**Residual, accepted**: pi-lens still runs `prettier --write` on these docs on every write — inside `format.enabled: true` for the package. It is now a no-op. Nothing about pi-lens configuration changed, by design: the boundary belongs to the repo, not to one agent tool, which is also why a manual prettier run or an editor's format-on-save is now equally harmless.
