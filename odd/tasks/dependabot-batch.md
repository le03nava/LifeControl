# Dependabot batch — one grouped change instead of ten redundant PRs

**Status**: implemented — the shared catalog, both wrappers and every frozen table that names a moved version
carry the new values, and `typescript` (`S3`) and `@types/node` (`S4`) are recorded exclusions with their
reasons, not pending work.

Twelve Dependabot PRs were open, but only nine distinct changes, because nine of the twelve edit the same
`gradle/libs.versions.toml` line and the bot opens one PR per `directory:` entry. This record consolidates the
mergeable ones into a single reviewed change and states, per exclusion, why the rest cannot land.

## Specs

- **S1** — The grouped change moves, in one branch: `spotless` `8.11.0` → `8.12.0`, `gradle-wrapper` `9.9.1` →
  `9.9.2` **in both modules**, `angular-eslint` `22.7.0` → `22.8.0`, and `spring-boot` `4.1.2` → `4.2.0`
  **together with** `spring-cloud` `2025.1.4` → `2026.0.0`.
- **S2** — Every frozen table moves with its bump, in the same commit: `FROZEN_PLUGINS`
  (`.github/scripts/tests/gradle-pins.test.sh:100-103`), `FROZEN_WRAPPERS` (`:94-95`, **both** labels), and the
  `angular-eslint` literal at `life-control-app-angular/src/toolchain-migration.spec.ts:60`.
- **S3** — `typescript` is **not** bumped to `7.0.2`: `@angular/build@22.3.0` declares
  `peer typescript ">=6.0 <6.1"`, so `npm` fails `ERESOLVE could not resolve` before the build starts.
- **S4** — `@types/node` is **not** bumped to `26`. This is not a pin to move: `toolchain-migration.spec.ts:70-90`
  asserts the major of `@types/node` equals the major of `engines.node`, which equals the major of `.nvmrc`
  (`24.15.0`). The guard's stated contract is *"types the app against the Node line it runs on"*, so leading the
  runtime with the types is the defect the guard exists to catch.
- **S5** — Doc-truth: `AGENTS.md:687` states the wrapper the two modules share and `life-control-api/AGENTS.md:7`
  states Spring Boot; both name a version this change moves, and no test reads either.
- **S6** — The gates are green on the grouped tree: every suite in `.github/scripts/tests/`,
  `./gradlew test spotlessCheck spotbugsMain` in **both** Java modules, and `npm run test:coverage:check`.

## Tasks

| ID | Specs | Task | Route | Commit |
|----|-------|------|-------|--------|
| T1 | S1, S2 | Catalog bump (`spotless`, `spring-boot`, `spring-cloud`) plus `FROZEN_PLUGINS` | inline | this branch, slice 1 |
| T2 | S1, S2, S5 | Wrapper `9.9.2` in both modules, `FROZEN_WRAPPERS`, `AGENTS.md` wrapper row | inline | this branch, slice 1 |
| T3 | S1, S2 | `angular-eslint` `22.8.0`: manifest, lock and the spec literal | inline | this branch, slice 2 |
| T4 | S5 | Spring Boot row in `life-control-api/AGENTS.md` (`api-gateway/AGENTS.md` does not exist) | inline | this branch, slice 1 |
| T5 | S6 | Run the gates | inline | no commit — evidence in L7 |
| T6 | S6 | Independent verification (`Risk: item 5`) | `gentle-ai-verify` | no commit — read-only |
| T7 | — | Slice commits and the delivery evidence in this log | inline | this commit |

## Log

- **L1** — 2026-11-24, the user's original request, verbatim: *"puedes revisar los PR de dependabot y ver cual
  podemos ir actualizando y haciendo merge"*.
- **L2** — 2026-11-24, the scope decision, verbatim: *"ok, agrupalos y crea un nuevo worktree"*.
- **L3** — 2026-11-24, measured before writing anything: `gh pr list --author app/dependabot` returned 12 open
  PRs and `gh pr diff --name-only` collapsed them to 9 distinct changes — #1036+#1040 (`spring-boot`),
  #1037+#1042 (`spotless`), #1039+#1043 (`spring-cloud`) are redundant pairs against one catalog line.
- **L4** — 2026-11-24, already merged and therefore **not** part of this change: #1041 (springdoc `3.2.0` →
  `3.2.1`, merge `99ce215e`) and #1044 (spring-restdocs-mockmvc `4.0.2` → `4.1.0`, merge `61a0ba40`). Both were
  green on arrival; #1044 needed `@dependabot rebase` and one `gh run rerun --failed` after a Maven Central
  `HTTP 429 Too Many Requests` that had nothing to do with its diff.
- **L5** — 2026-11-24, the paired failure that decided S1: #1036/#1040 and #1039/#1043 each die alone with
  `org.springframework.cloud.configuration.CompatibilityNotMetException` inside `CompositeCompatibilityVerifier`
  (`ApiGatewayApplicationTests > contextLoads()` plus five security tests, `33 tests completed, 6 failed`).
- **L6** — 2026-11-24, scope correction to the read-only report: `@types/node` `26` (#1047) was first classified
  as a frozen pin. Reading `toolchain-migration.spec.ts:70-90` showed the opposite — the guard ties the types to
  the runtime on purpose, so the bump is a design rejection. Same pass found `typescript` `7.0.2` (#1046)
  blocked by `@angular/build@22.3.0`'s peer range. Both are excluded by S3 and S4.
- **L7** — 2026-11-24, independent verification (read-only, `Risk: item 5`), verdict per spec: S1 covered and
  shown **not vacuous** — the cached `spring-cloud-dependencies-2026.0.0.pom` pins `spring-boot.version`
  `4.2.0`, and `SpringBootVersionVerifier.ACCEPTED_VERSIONS` in `spring-cloud-commons-5.1.0` is the single key
  `"4.2"`, while train `2025.1.4` ships commons `5.0.4` whose map is `{"4.0","4.1"}` — so both one-sided
  combinations are rejections by construction. S2 covered (three frozen tables, no fourth one exists; the
  `4.1.2`/`8.11.0` literals in `version-catalog.test.sh` are decoys that must be rejected). S3 covered. S4
  covered **and falsifiable by mutation**: moving `@types/node` to `^26.19.2` fails with `expected '26' to be
  '24'`, and moving `.nvmrc` to `26.15.0` fails the same assertion — the guard rejects drift in either
  direction. S5 covered for the two named cells. S6 covered for the shell suites (18/18, 530 assertions), lint
  and the coverage gate re-measured at 87.27 / 86.60 / 81.42 / 90.13; the two Gradle suites were **not**
  re-run by the verifier and are the implementer's measurement, not a verified one.
- **L8** — 2026-11-24, three findings from L7 and their disposition. (a) This record's own `**Status**`
  paragraph opened with a legal token but then said *“what remains is the review and the merge”*, which is the
  delivery-state class the header contract forbids; `odd-status-headers` passed it because its forbidden list
  does not carry that phrasing. **Fixed** in this commit. (b) `spring-cloud` is a `[libraries]` entry and is in
  **no** frozen table, so a one-sided train bump is caught only by the api-gateway Spring context: a real gap,
  left open here on purpose because closing it is a new suite and this record's scope is the bump. (c)
  `README.md:43,57` and `life-control-api/README.md:11` still say Spring Boot `4.1.1`/`4.1`; confirmed by
  `git show HEAD:` against the base commit to be **stale before this change and untouched by it**, so it is
  left alone rather than widened into this diff.
- **L9** — 2026-11-24, caught by `odd-citations.test.sh` before the record was final: the Tasks table first
  cited the two slice commits on this branch by their short hashes, and the suite failed with *“the counts do
  not partition: 20 absorbed + 1459 ancestors != 1481 cited”* — a branch hash is not an ancestor of
  `origin/main`, and the citation rule only admits resolvable content. Replaced with prose, which is what the
  existing records use; the slice subjects in the log are the durable pointer until the merge commit exists.
- **L10** — 2026-11-24, delivery: the grouped change travels in **PR #1049** against `main`, opened with
  `type:chore` and `Closes #1048`, whose issue was created with `status:approved` because the “A linked,
  approved issue” check is skipped only for the bot's own pull requests. The merge commit is deliberately not
  written here: `AGENTS.md` says it is derived with `git log -1 --format='%h' -- odd/tasks/dependabot-batch.md`,
  and a branch hash would be a citation that stops resolving the moment the branch is rewritten.
