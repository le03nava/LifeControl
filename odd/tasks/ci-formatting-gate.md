# ODD feature: ci-formatting-gate

**Repository**: LifeControl — `.github/workflows/angular-ci.yml`, `.github/scripts/tests/`, `.githooks/pre-commit`, `life-control-app-angular/.husky/pre-commit` and the app's `src/` tree.
**Status**: implemented — the formatting gate lives in `Lint & Build`, its offline suite pins it, both pre-commit entry points deliver both guarantees and the drifted source files are formatted so the gate starts green. Nothing is deferred.
**Created**: 2026-10-21 · **Risk**: item 5 (delivery/environment) — this changes a CI job that branch protection names, so a mistake is invisible locally and reaches every pull request. An independent verifier was run for that reason (`L4`).

## Specs

**S1** — The user's instruction, verbatim: *"si normaliza un comando"*, then *"si"* to *"¿Abro el issue de la barrera de formateo (opción 2, con las otras dos como criterios de aceptación relacionadas) para que lo aprobemos y quede en un PR?"* — issue [#515](https://github.com/le03nava/LifeControl/issues/515), approved by the user on the same instruction.

**S2** — The measured defect: formatting is enforced by exactly one mechanism, `life-control-app-angular/.husky/pre-commit` running `npx lint-staged`, reachable only while `core.hooksPath` points at `.husky/_`. `AGENTS.md`, `CONTRIBUTING.md` §4 and the `project-conventions` skill all instruct `git config core.hooksPath .githooks`, which silently turns formatting off, and the app's `prepare` script writes `.husky/_` back on every `npm install`. No workflow mentions prettier and `eslint.config.js` carries no prettier plugin, so CI could not see a formatting difference either.

**S3** — The gate: the `Lint & Build` job runs a prettier check over `life-control-app-angular/src/**/*.{ts,html,scss}` and fails on an unformatted file. Its `name:` stays byte-identical, because `CONTRIBUTING.md` §7 and branch protection name it, and both `paths:` blocks stay identical.

**S4** — The tree passes the gate it introduces: the drifted files are formatted in the same pull request, and nothing else about them changes.

**S5** — The gate cannot be deleted silently: one offline suite under `.github/scripts/tests/` fails when the step goes missing, in the style of `workflow-trigger-parity.test.sh`, and it is picked up by the existing `*.test.sh` glob.

**S6** — The two hook mechanisms stop competing: `core.hooksPath .githooks` keeps the anchor guard **and** formatting/lint reaches commits; a fresh worktree with no `node_modules` can still commit; `ALLOW_MAIN_COMMIT=1` still escapes deliberately.

**S7** — No behavior change hides in the sweep and no lint rule is weakened: `eslint.config.js` untouched, no prettier plugin added to ESLint, no dependency added, `npm run lint` still clean.

**S8** — What must keep working: `Lint & Build`'s name, the `paths:` parity suite, `Unit tests` and `Coverage` unaffected, and the whole Angular suite green.

## Slice boundary and delivery strategy

One pull request, **603+ authored lines** against the advisory ~400 budget, and that is deliberate rather than an oversight:

- **The gate cannot be split from the sweep.** A slice that adds the prettier step without formatting the tree ships a red `Lint & Build` on its own first run, so the two are one change by construction.
- **The hooks are what removes the cause**, and leaving them out would land a gate whose local complement still competes for `core.hooksPath` — the defect S6 names.
- **Review cost is bounded by what the diff is**: of the 24 non-doc files, 21 are formatting-only (whitespace, wrapping, trailing commas — the worker cross-checked each with a whitespace/comma-normalized comparison), and the logic lives in 10 workflow lines, one new offline suite, and two hooks.

Recorded here before the record commit, per the slice rule.

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| **T1** | The gate, RED first: the `Check formatting` step in `lint-build`, proven to fail on a probe file and pass once the probe is removed. | S3 | delegated (`gentle-ai-worker`) | `ad68d53` |
| **T2** | The offline suite `angular-formatting-gate.test.sh`, with 8 fixtures and a probe proving it fails when the step is removed. | S5 | delegated (`gentle-ai-worker`) | `ad68d53` |
| **T3** | The sweep: `prettier --write` over the app's source glob, formatting only. | S4, S7 | delegated (`gentle-ai-worker`) | `60d41bd` |
| **T4** | Both hook entry points delivering both guarantees, with the `node_modules` gate that keeps a fresh worktree commit-able. | S6 | delegated + parent fix (`L3`) | `5ccf6fa` |
| **T5** | Independent verification of the CI claim (Risk item 5). | S3, S6 | `gentle-ai-verify` | see `L4` |
| **T6** | This record plus the stale suite counts in `AGENTS.md` (11 suites, 364 assertions, 16 shell files). | S5 | inline | the commit that carries this record |

## Log

**L1** — *2026-10-21, the user's words, verbatim*: *"si normaliza un comando"* (normalising `core.hooksPath` to `.githooks`, done and measured in this session: anchor refusal exit 1 on `main`, exit 0 on a feature branch, `ALLOW_MAIN_COMMIT=1` exit 0), then *"si"* to the offer of opening the formatting-gate issue, then *"Aprobalo y arrancá el PR"* to the approval question. Issue #515 carries `status:approved` on that instruction.

**L2** — *2026-10-21, the measurements the issue is built on.* `npx prettier --check "src/**/*.{ts,html,scss}"` → 20 files, exit 1; the sweep measured in a throwaway worktree → 20 files, 89 insertions, 65 deletions; `grep -rn "prettier\|lint-staged" .github/workflows/ .github/scripts/*.sh` → no output; `eslint.config.js` → no prettier plugin; the husky hook → `npx lint-staged`; `package.json`'s `prepare` → `git config core.hooksPath life-control-app-angular/.husky/_`.

**L3** — *2026-10-21, the parent's fix to the worker's hook chaining.* The worker delivered the husky entry point delegating to the repository hook and then running `npx lint-staged` again, which it flagged: with `core.hooksPath` at `.husky/_` every commit paid the `eslint --fix` + `prettier --write` pass twice, and in a checkout without `node_modules` `npx` would reach the network. The husky entry point now stops after a successful delegation and keeps its own call only as the fallback for a checkout with no repository hook, gated on `node_modules`. Measured with a stub `npx` on `PATH`: one `lint-staged` call per entry point, not two.

**L4** — *2026-10-21, the independent verification demanded by Risk item 5.* See the verifier's verdict recorded with this unit; it is the reason a CI change is not self-certified here.

**L5** — *2026-10-21, the sweep grew by one file.* The issue measured 20 drifted files; by implementation time `main` had moved to `8b03687` (PR #514) and `store-inventory-settings-panel.html` arrived drifted, so the sweep is 21 files, 91 insertions, 67 deletions. Reported as a difference with its cause instead of padded into the original number.

**L6** — *2026-10-21, the gate's own blind spot, kept on purpose.* `.husky/pre-commit` and `.githooks/pre-commit` never run in CI, so the local half of this unit is proven by the offline suites and by fixtures, while the half that actually gates every pull request is the prettier step. The AGENTS.md count this record updates said the number must be read from the run, not copied — so it was: 11 suites, 364 assertions, 16 shell files, measured in this worktree.
