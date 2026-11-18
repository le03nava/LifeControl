**Status**: implemented — `@types/node` moved to the 24 line, every `@angular/*` package and
`angular-eslint` follows one exact-pin policy, `npx tsc --noEmit` passes for the first time (it was 46
errors before this unit and none of them was the version), `backstage/` is gone from the tree, and every
document that named a version `package.json` does not resolve now names the one it does. `jsdom` is
recorded as a risk, not upgraded, per the non-goal.
**Created**: 2026-11-24 · **Risk**: item 3 (the `@angular/*` pins and the `types` of the root tsconfig are a
contract every Angular tool in the tree consumes) and item 5 (the dependency set).
**Issue**: #895 · **Branch**: `chore/dependency-runtime-parity` · **Spec**: this file, `## Specs`.

## Specs

Verbatim acceptance criteria of issue #895. S1 and S3 are the runtime halves, S2 is the pinning half, S4
is the Backstage half and S5 is the documentation half.

- **S1**: `npm ls @types/node` reports a 24.x line and `npx tsc --noEmit` passes in
  `life-control-app-angular`.
- **S2**: Every `@angular/*` and `angular-eslint` dependency follows one policy, so `npm install` cannot
  move one of them alone; `npm ls @angular/core @angular/cdk @angular/material` reports no peer conflict.
- **S3**: `npm run lint` and `npm run test:coverage:check` pass.
- **S4**: Backstage either has a committed lockfile plus a CI job that installs and builds it, or it no
  longer exists in the tree; the decision is written down where the next person will find it.
- **S5**: No committed document names an Angular version that `package.json` does not resolve.

Non-goals, verbatim: "No Angular upgrade and no test-runner migration: `jsdom` is named as a risk to
record, not as a suite to rewrite here."

The pinning policy is **exact pins** for every `@angular/*` package and `angular-eslint`. It is not a
style preference: the peers already demand it (`@angular/material` requires `@angular/cdk` exactly
`22.2.3`, `@angular/compiler-cli` requires `@angular/compiler` exactly `22.2.3`, and `@angular/core`
requires `@angular/compiler` exactly `22.2.3`), and it is the only policy that satisfies S2's "`npm
install` cannot move one of them alone", because a caret range moves exactly one package on the day only
that one is published.

## Tasks

| ID | Spec | What | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | S2 | Every `@angular/*` package pinned exactly to `22.2.3`, `angular-eslint` left exact at `22.6.0`, lockfile regenerated so the resolution is unchanged | inline | slice 1 |
| T2 | S1 | `@types/node` from `^20.17.19` to `^24.19.2` (the newest 24.x published) | inline | slice 1 |
| T3 | S1 | `npx tsc --noEmit` made to pass: `types` declared in the root tsconfig, the application program kept node-free with `types: []`, and the four TS4111 the node types expose fixed | inline | slice 1 |
| T4 | S4 | `backstage/` removed from the tree together with the three live references and the two ignore rules that would dangle | inline | slice 2 |
| T5 | S5 | Every committed document and user-visible copy that named Angular 20 or Node 20 corrected | inline | slice 2 |
| T5b | S2, S1 | The existing guard at `src/toolchain-migration.spec.ts` rewritten: it encoded the mixed policy it should have forbidden, and a new guard makes the `@types/node` major unable to drift from `engines.node` again | inline | slice 1 |
| T5c | S5 | `src/version-claims.spec.ts`: the guard that makes S5 durable, deriving the major from `@angular/core` instead of hardcoding it | inline | slice 3 |
| T6 | non-goal | The `jsdom` risk recorded in the component guide, with no upgrade | inline | slice 2 |
| T7 | S3 | `npm run lint` and `npm run test:coverage:check` green on the changed dependency set | inline | slice 2 |
| T8 | S1–S5 | Independent verification of the five criteria | verify | pending |

## Log

- **L1** — the human's request, verbatim: «puedes continuar con el issue 895».
- **L2** — the two decisions taken before any write: issue #895 approved with `status:approved` on
  2026-11-24 ("Sí, aprobalo y arrancá"), and **Backstage is removed from the tree** rather than kept with a
  lockfile and a CI job ("Eliminarlo del árbol"). S4 is therefore satisfied by its second branch.
- **L3** — no prior work existed for this issue: no branch, no worktree and no `odd/tasks/*.md` mentioned
  it. The worktree was created with `./.github/scripts/new-unit.sh chore/dependency-runtime-parity` from
  `origin/main` (`484bf454`).
- **L4** — **the baseline was red before this unit touched anything, and the `@types/node` version was not
  the reason.** `npx tsc --noEmit` reported **46 errors, all 46 `TS2591`** (`process`, `Buffer`,
  `node:fs`, `node:path`, `node:crypto` not found) because the root `tsconfig.json` declares no `types`,
  so `@types/node` never enters the program at all — measured: 1107 files in that program and **zero** of
  them from `@types/node`. The four `TS4111` do **not** exist at baseline and could not: while `process`
  itself is unresolved the error is `TS2591`, and `noPropertyAccessFromIndexSignature` never gets to judge
  `process.env.CI`. They appear only once `types` is declared, which is when the same sources report
  exactly 4 (`e2e/mocks/api.ts:3`, `e2e/mocks/keycloak.ts:4`, `playwright.config.ts:3,4`). An earlier
  version of this entry said "42 × TS2591 + 4 × TS4111" for the baseline; that decomposition was wrong and
  the independent verifier reproduced the correct one.
- **L5** — a measurement I got wrong and corrected, recorded because it changed nothing but was stated to
  the human: I first reported that `backstage/yarn.lock` existed, contradicting the issue. It does **not**
  exist (`ls` returns `ENOENT` and `git ls-files` lists no `backstage/` lockfile), so the issue's premise
  "no lockfile anywhere" was correct and my correction of it was the error. The removal decision stands on
  its own merits either way.
- **L6** — the shape of T3 is decided by a rule the tree already wrote down, not by preference:
  `src/test-timeouts.ts:86-89` records that `tsconfig.app.json` includes `src/**/*.ts` and excludes only
  `*.spec.ts`, so the application build type-checks that module too, "and that program has no
  `@types/node`, where a bare `process` fails `ng build` with `TS2591`". Node types must therefore not leak
  into the browser program, which is why the root config gains `types` and `tsconfig.app.json` pins
  `types: []` in the same commit.
- **L7** — the root `tsconfig.json` has no `include`, so it already swept **575 files** (`src/`, `e2e/` and
  `playwright.config.ts` together). That is why it is the config `types: ["vitest/globals","node"]` belongs
  in: it is already the whole-folder program, and declaring its `types` makes it an honest one instead of
  a program that silently resolves none.
- **L8** — peer evidence for the exact-pin policy, read from `node_modules` rather than assumed:
  `@angular/material` → `@angular/cdk` exactly `22.2.3`; `@angular/compiler-cli` → `@angular/compiler`
  exactly `22.2.3`; `@angular/core` → `@angular/compiler` exactly `22.2.3`; `@angular/cdk` → `@angular/core`
  `^22.0.0 || ^23.0.0`; `@angular/build` and `@angular/compiler-cli` → `typescript >=6.0 <6.1`, satisfied by
  the tree's `~6.0.3`. `npm ls @angular/core @angular/cdk @angular/material` reports no `UNMET`, `invalid`
  or conflict.
- **L9** — a document claim that was not merely version-stale but **false**:
  `life-control-app-angular/AGENTS.md:838` said `@angular/build:unit-test` in Angular 20 "no soporta
  `codeCoverageThresholds`". `@angular/build` 22.2.3's unit-test schema lists `coverageThresholds`, so the
  option exists. The paragraph was rewritten to say what is true — the enforcement still lives in
  `scripts/check-coverage.mjs` because that is the only point covering both the sharded `ng test
  --coverage` runs and the `vitest --merge-reports` path, which has no builder to apply a threshold — and
  the migration itself was left out of scope, since it is a test-runner change and therefore a non-goal.
- **L10** — **the tree already had a guard for the pin policy, and it encoded the defect instead of
  catching it.** `src/toolchain-migration.spec.ts:44-62` asserted `toMatch(/^\^22\.2\.3$/)` for the nine
  caret packages and `toBe('22.2.3')` for `cdk` and `material` — that is, it required the mixed policy that
  S2 exists to remove, so the first full-suite run after the pin change failed on it. The assertion now
  reads the keys (`Object.keys(all).filter(name => name.startsWith('@angular/'))`) and requires
  `22.2.3` on every one, which is what makes a package added later inherit the policy instead of escaping
  a hardcoded list.
- **L11** — the two new guards were checked for vacuity rather than assumed green, because a guard that
  passes by construction is the failure this repository already recorded once
  (`odd/tasks/v64-vacuous-self-check.md`). Three runs of `src/toolchain-migration.spec.ts`: clean tree →
  6 passed; `@angular/core` mutated to `^22.2.3` → 1 failed; `@types/node` mutated to `^20.19.2` → 1
  failed; tree restored → 6 passed, and `git diff package.json` confirmed the mutation left no residue
  (exactly the ten intended lines).
- **L12** — the `@types/node` guard is new and is what keeps S1 from regressing: it reads `.nvmrc`,
  `engines.node` and the `@types/node` major and requires all three to agree. The issue's first bullet is a
  drift between exactly those three numbers, and before this unit nothing in the tree compared them.
- **L13** — two boundaries interpreted rather than followed blindly, both recorded so a reviewer can
  disagree: (a) the `angular-21` row in `AGENTS.md:23` described the generic skill as "Angular 18+
  patterns", and the skill's own frontmatter really does say `v18+`, so changing the number would have
  made the document describe the skill wrongly; the version claim was dropped instead and the skill id kept,
  because an id is not a version claim. (b) `src/features/products/pages/product-edit/product-edit.spec.ts:627`
  said `setValue` does not mark a control dirty "in Angular 20"; re-stamping the number would have asserted
  a behaviour nobody measured on 22, so the version qualifier was dropped and the behaviour claim kept.
- **L14** — slice boundary, recorded because the harness flags the slice as past the ~400-line budget:
  **two commits, not three.** slice 1 holds T1, T2, T3 and T5b (the dependency and type-check contract,
  80 authored lines); slice 2 holds T4, T5, T6 and T7 (the tree's self-description and the dead scaffold,
  148 authored lines). A three-way split was designed and rejected: `README.md:58` and `:59` are adjacent
  lines, so the version correction and the Backstage row land in the same git hunk and cannot be staged
  apart without hand-editing a patch — the split would have cost more than it bought. The 1890 deleted lines
  are a scaffold nothing built, so they are not review surface in the sense the budget measures.
- **L15** — the base moved while this unit ran: `origin/main` went from `484bf454` to `4d08e7d2` (PR #963,
  `docs/instruction-defects`), which touches `AGENTS.md` and therefore overlaps this unit's edits. The branch
  was rebased onto it with no conflict, so the record cites its slices by position rather than by SHA, and
  `git diff --name-status origin/main` lists no deletion outside `backstage/`.
- **L16** — independent verification (`gentle-ai-verify`, read-only, on a scratch copy of the tree) returned
  S1, S2, S3 and S4 **met** and **S5 unmet**, plus the L4 correction above. What it found and what was done:
  - **S5 was not met.** `life-control-app-angular/README.md:13` still said `Angular Material 20 (M3 theme)`
    — the same table slice 2 had already corrected two rows of. T5's sweep missed it because its pattern was
    `Angular (18|19|20|21)`, which does not match with the word "Material" between the two. Corrected to
    `Angular Material 22`.
  - Same defect class, adjacent line: `src/core/layout/footer/footer.html:24` rendered `TypeScript 5.0+` to
    the user while the tree resolves `~6.0.3`, two lines from copy slice 2 did fix. Corrected to `6.0+` and
    recorded as the same class of false claim, not as an S5 item, because S5 is scoped to Angular.
  - It also proved, rather than read, that the two guards are not vacuous, that `types: []` is necessary
    (deleting it makes the application program pull **66** `@types/node` files through `extends`), and that
    `ng build` is green on the changed tree.
- **L17** — S5 had **no guard**, which is exactly why a stale claim survived the sweep meant to catch it.
  `src/version-claims.spec.ts` closes that: it derives the major from `@angular/core` instead of hardcoding
  it, scans the four live documents plus every `src/**/*.{ts,html}`, strips comments from `.ts` only (a
  comment saying "changed in Angular 20" is history and must stay), and never reads `odd/tasks/**`.
  Mutation-proved: `Angular Material 22` → `Angular Material 20` in the app README makes it fail with
  `README.md: Angular Material 20`; restored, it passes. It taught its own limit on the first run —
  `Angular Material 3` is **Material Design 3**, the theme generation, and a guard that failed on correct
  text would be deleted rather than trusted, so a single-digit number after "Material"/"CDK" is not read
  as a package version.
- **L17b** — writing that guard cost a real defect and it is worth recording, because it is a trap any new
  spec in this tree can fall into. A spec file with no `import` and no `export` is a **script**, so its
  top-level `const`s live in the global scope: the first version reused the names `fs`, `path`, `read`,
  `withoutComments` and `walk` from `toolchain-migration.spec.ts` and the Angular test builder refused to
  compile with `TS2451`/`TS2393` for every shared name — while `npx tsc --noEmit` reported **0 errors**, so
  the two programs disagree about this file. Adding `export {}` at the end did **not** settle it. What does
  is the convention the tree already follows and never wrote down: unique helper names per spec, which is
  why `build-environment.spec.ts` calls its own `readProjectFile` and `test-timeouts.spec.ts` calls its
  `readSetupSource` and `stripNonCode`. The helpers are now `claimFs`, `claimPath`, `readClaimSource`,
  `stripComments` and `walkSources`, verified unique across `src/`, and the reason is stated in the file's
  header so the next author does not rediscover it.
- **L18** — three findings accepted as **deferred**, none of them an acceptance criterion:
  (a) `npx tsc --noEmit` — the command S1 names — runs in **no CI workflow**, so the pass this unit bought
  can regress without a red job; wiring it into `angular-ci.yml` is a delivery change (risk item 5) and
  belongs to its own unit, not to a dependency-pin fix. (b) S4 has no guard by design: re-adding a scaffold
  is a review question, and a spec asserting a path does not exist would be noise. (c) The S3 gate is
  timeout-fragile on a loaded host — 6 and 13 `Test timed out` failures at load 11–37, green at the same
  tree with a reduced worker count — which is pre-existing and already documented in the component guide.
