# ODD feature: flyway-head-single-source

**Status**: implemented — the head the schema suites assert is derived from the migration files instead
of repeated as a literal, the ordering rule is pinned on its own, and the seven suites that carried a
copy now read one source. Nothing is deferred.
**Created**: 2026-10-26 · **Risk**: low — test-only. No `src/main` file, no migration, no contract and
no CI configuration changes. The one shared surface is `AbstractPostgresIntegrationTest`, which every
PostgreSQL integration suite extends (1029 suites): the change there is additive, and a mistake in it
fails the module build loudly rather than silently — the ordering defect it replaces did the opposite.

## Objective

Adding a migration must not require editing test files. The newest Flyway version is resolved once,
from the migration files themselves, and every suite that asserts the applied head compares against
that resolution.

## Problem

Seven suites asserted the applied Flyway head as the literal `"35"`:

`goodsreceipt/GoodsReceiptIntegrationTest`,
`hr/{EmployeeContract,Employee,EmployeeScopeAssignment,Hr}SchemaMigrationIntegrationTest`,
`product/ProductCategorySchemaMigrationIntegrationTest` and
`provisioning/AccessProvisioningSchemaMigrationIntegrationTest`. A migration therefore meant seven
hand edits, and the ones that were forgotten stayed green until the next run of that suite — the
failure mode the record `odd/tasks/product-category-sat-code.md` measured in its own comments (`V33`
next to an assertion of `35`) and then declared: *"that duplication deserves its own task"* (its
`L11`). This unit is that task.

## Why this shape

- **The source is the migration files, not `flyway.info()`.** Comparing Flyway's report against
  Flyway's own scan would be a tautology. Against the file listing it is not: a file Flyway does not
  recognise (a missing `__`, a lower-case `v`) is on disk and absent from the report, so the two
  sources disagree exactly where the literal could never look. The assertion is stronger than the one
  it replaces, not weaker.
- **`MigrationVersion`, not string comparison.** `V10` sorts *before* `V9` as a string, so a naive
  comparison silently reports an older two-digit migration as the newest. Flyway's own version type
  carries the ordering, and `MigrationHeadTest` pins it against a fixture directory so the rule cannot
  regress without a red test.
- **Fail loudly on an empty location.** A pattern that resolves to nothing would otherwise compare the
  applied head against nothing at all; it throws instead.
- **The residual trade-off, stated rather than hidden.** The literal also pinned "V35 exists". That
  specific claim is gone on purpose — it is the cost of removing the tax, symmetric to it. What keeps
  covering the same ground is `pending()` empty, `ddl-auto=validate` and Flyway's `validateOnMigrate`
  (default `true`, not overridden in `application.properties`).

## Specs

- **S1** — adding a migration requires no test edit: the seven suites derive the head from the
  migration files and assert the applied head against it.
- **S2** — the ordering rule holds: `V10` is newer than `V9` and `V2`, pinned without touching the real
  migration directory.
- **S3** — the derivation fails loudly when its location carries no migration.
- **S4** — the delivered bytes pass the gate `api-ci.yml` runs: `spotlessCheck spotbugsMain` and
  `test`, the latter with no failure, error or skip.
- **S5** — the delivery is a worktree of its own, an approved issue and one pull request carrying
  exactly one `type:*` label (the user's instruction, `L2`).

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S3 | `latestMigrationVersion()` in `AbstractPostgresIntegrationTest`: classpath listing, `MigrationVersion` ordering, empty location fails loudly | `cc35ab30` |
| T2 | S2 | `MigrationHeadTest` + `src/test/resources/migration-order-fixture/{V2,V9,V10}__*.sql` | `cc35ab30` |
| T3 | S1 | the seven suites assert against the helper; zero head literals left in `src/test` | `cc35ab30` |
| T4 | S4 | gates in the delivering worktree | `cc35ab30` |
| T5 | S5 | this record | this commit |

## Evidence log

**L1** *(the user, verbatim)*: «ok dale con ese» — authorizing the front the session had recommended,
after the record above was quoted as declaring this duplication a pending follow-up.

**L2** *(the user, verbatim)*: «si crea un worktree propio genera el issue apruebalo y has el PR» — the
delivery shape of `S5`, including the approval of the issue by the maintainer, recorded here because
`CONTRIBUTING.md` §1 states that approval is a human decision and not a label the author applies to
their own request: the human gave it, this session applied it.

**L3** *(RED, the defect the duplication hides)*: the helper was first written with a raw string
comparison and `MigrationHeadTest` failed `expected: "10" but was: "9"` — the two-digit inversion.
Replaced with `MigrationVersion`, green.

**L4** *(RED, the tax itself)*: with a probe migration `V36__head_probe.sql` in the tree (never
committed, deleted after the measurement), `ProductCategorySchemaMigrationIntegrationTest` failed
`expected: "35" but was: "36"` — 12 tests, 1 red. That is the hand edit the old assertions demanded.

**L5** *(GREEN, the differential to L4)*: with the same probe still in the tree, the seven refactored
suites ran **103 tests, 0 failures**, without editing a literal. The probe was then removed and the
migration count returned to 35.

**L6** *(gate, on the delivering worktree from a cold `build/`)*: `spotlessCheck spotbugsMain test` — the
command pair `.github/workflows/api-ci.yml` runs — returned `BUILD SUCCESSFUL` with 8 tasks executed:
**983 result files, 3975 tests, 0 failures, 0 errors, 0 skipped**; SpotBugs `0 BugInstance`, `0 errors`,
`0 missingClasses`. The eleven suites of `.github/scripts/tests/` that `PR Label Guard` runs also passed
against this tree, `odd-status-headers.test.sh` included, which is what audits this record's header.

**L7** *(review posture)*: the RDD assessment for this candidate reported `nativeRisk: unassessable`
(untracked files without a declaration) and a plan of writer self-verification with no independent
verifier, no Task Size high-risk item escalated. The self-verification is `L3`–`L6`; no native review
was admitted for this candidate.
