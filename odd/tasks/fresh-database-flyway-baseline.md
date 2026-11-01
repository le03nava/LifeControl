# ODD feature: fresh-database-flyway-baseline

**Repository**: LifeControl — `life-control-api/src/main/resources/application.properties` (the Flyway baseline pair), the integration suite that pins it, and the two documents that assert the old claim. Issue #722.
**Status**: implemented — a database created from scratch now applies `V1`→head instead of recording `V1` as a baseline it never ran, because delivery baselines at version 0, which is the only true statement about a schema that holds no table. Pinned by an integration test that reads the delivered properties, models the object the roles script creates in `public` before Flyway, and refuses both a baseline row and a schema without `V1`'s tables. The pre-Flyway database, the only case the baseline version 1 existed for, is now an explicit operator action instead of a delivered default. Nothing is deferred.
**Created**: 2026-11-01 · **Risk**: item 1 — the change decides whether a migration runs, and the defect it fixes failed **silently in the one direction that matters** (a recorded baseline that never executed). No stored data is rewritten and no existing database changes behaviour, but a wrong value here is a database that comes up missing a migration's objects.

## Specs

S1 — issue #722, *What happens* and *Acceptance*, verbatim:
> On a **fresh** database the API never starts. Flyway baselines the empty schema at version 1 and then runs V2 onward, so the very first migration that assumes a table from V1 dies: `ERROR: relation "countries" does not exist`
> - A datadir created from scratch initializes end to end: `V1…head` applied, `deploy.sh <env> health` green, the pool connected as `lifecontrol_app`.
> - A database that already has a full history is unaffected.

S2 — the repository flow for a bugfix:
> "si dale con 722"

Issue-first with the approved issue #722, a `fix/` worktree through `new-unit.sh`, this record, RED observed on the untouched tree, the fix, and a pull request carrying `Closes #722` with exactly one `type:*` label.

S3 — the delivered configuration must be the one the test proves.
The schema the suite builds and the schema delivery builds must be produced by the **same** Flyway settings, read from `application.properties` rather than restated in the test, so the two cannot drift into the disagreement that hid this defect for every environment that already had a history.

## Tasks

| # | Task | S# | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | Worktree `fix/fresh-database-flyway-baseline` through `new-unit.sh`; this record | S2 | done — branch from `origin/main @ 58bbfdf3` |  |
| T2 | RED: an integration test that creates a scratch database, makes `public` non-empty the way `docker/postgres-init/lifecontrol-roles.sql` does, migrates with the **delivered** baseline pair, and asserts `V1…head` ran with no baseline row | S1, S3 | done — on the untouched tree `migrate()` logs `Successfully baselined schema with version: 1` and dies in `V2__seed_countries.sql` with `SQL State 42P01`; the second case (already-at-head) passed, which is what separates the defect from the fixture |  |
| T3 | `application.properties`: the baseline pair the fresh-database path needs, with the reason written next to it | S1 | done — `baseline-version` 1 → 0, `baseline-on-migrate` kept, and the pre-Flyway database named as an explicit operator action |  |
| T4 | `README.md` and `life-control-api/AGENTS.md`: the claim that "existing DBs are baselined at v1" replaced with what the tree now does | S1 | done — both state that a fresh database runs `V1`→head, that an existing one is untouched, and that a pre-Flyway one is a one-time operator `flyway baseline -baselineVersion=1` |  |
| T5 | Gates: the new test green, the migration suites, `spotlessCheck`, the docker shell suites | S1 | done — `./gradlew cleanTest test` on the final bytes: **1023 classes / 4256 tests / 0 failures / 0 skipped**; `spotlessCheck` green; `shellcheck --severity=warning` over all 31 docker scripts green; `db-roles.test.sh` **167 passed, 0 failed** (four assertions added beside the resolver pair; the fourth pins `baseline-on-migrate=true`, which an independent check proved is what keeps a fresh volume from being *refused* instead) |  |
| T6 | Pull request `type:bug` with `Closes #722`, linked to the approved issue | S2 | pending |  |

## Log

L1 — Request that opened the work, verbatim: *"si dale con 722"*.

L2 — 2026-11-01, requirement source. Issue #722 was filed on 2026-11-01 while reproducing the fresh-volume path of #720, and carries the measurement: on a datadir created from scratch the roles applied correctly, `flyway_schema_history` held exactly one row (`1 | << Flyway Baseline >> | success`), and startup died on `relation "countries" does not exist`.

L3 — 2026-11-01, mechanism read from the tree before any edit. A delivery volume is **never empty** when Flyway first runs: `docker/postgres-init/lifecontrol-roles.sql:108` executes `CREATE EXTENSION IF NOT EXISTS btree_gist` as `lifecontrol_bootstrap` (a superuser, which is why the extension can be created at all — `V21__employee_contracts.sql:27` finds it already there), and step 6 of the same script creates `task_employee_company(uuid)`. Both land in `public`. `baseline-on-migrate=true` with `baseline-version=1` reads a schema holding any object as a pre-existing database, records `V1__baseline_schema.sql` as applied without running it, and the next migration to touch a V1 table fails.

L4 — 2026-11-01, the same trap was already paid for once in the suite, which is the strongest evidence the delivery line was wrong rather than unlucky. `application-test.properties` baselines at **0** and its comment records the measurement (2026-11-01) — `V2__seed_countries.sql` failing with `SQL State 42P01` after `Successfully baselined schema with version: 1`. The test profile was corrected; the delivered pair that produced the trap was not.

L5 — 2026-11-01, scope decision. Rejected: moving the roles script's objects out of `public` so a fresh schema stays empty for Flyway. `btree_gist` is not relocatable in this image and `task_employee_company` is called by the V53 policies and by Java, so relocating them would touch migrations that are already applied in every environment — a far larger and worse-reviewed change than the configuration line, and one that would leave the *delivery* default (baseline at 1) still wrong for any future init script that creates an object. Rejected too: `baseline-on-migrate=false`, which fails a fresh volume outright with *"Found non-empty schema(s) but no schema history table"*.

L6 — 2026-11-01, RED observed on the untouched tree, from the delivered properties read off the classpath. The scratch database holds the roles script's two objects, `migrate()` logs `Successfully baselined schema with version: 1`, and the chain dies in `V2__seed_countries.sql` with `SQL State 42P01`. Both acceptance criteria are asserted in one class: the fresh database reaches head with `V1` recorded as `type = SQL`, and a database already at head applies `0` migrations and gains no history row.

L7 — 2026-11-01, **which** object makes the schema non-empty, measured because the first premise probe was wrong. `pg_class` in `public` counts **zero** on that scratch database: `btree_gist` installs functions, operators and operator classes, and an extension's objects carry `pg_depend` rows with `deptype = 'e'`. Flyway 12's own predicate (`PostgreSQLSchema#doEmpty()`, `flyway-database-postgresql-12.4.0`) counts relations of kind `r,v,S,t`, types that are neither arrays nor composites, **and functions** — each one only when it does not belong to an extension. So the extension is invisible to it and `task_employee_company(uuid)` is precisely the row that flips the answer. The test's premise now runs Flyway's query verbatim instead of a plausible-looking stand-in that measured nothing.

L8 — 2026-11-01, GREEN after `spring.flyway.baseline-version=0`. `application-test.properties` keeps its own line on purpose: this suite tests the delivered pair, so it must not be built on it — a regression in `application.properties` now fails one named assertion instead of turning 64 fixture classes red for a reason that says nothing about the difference. The same trap is pinned twice because it has two readers: `FlywayDeliveryBaselineIntegrationTest` proves the behaviour, and `db-roles.test.sh` keeps a text pin next to the resolver assertions it already had, since the roles script is the reason the pair matters.

L9 — 2026-11-01, independent verification over the committed bytes, in a scratch copy at `/tmp` (never the worktree). It reproduced the RED by flipping only the scratch copy back to `1` (`FlywayMigrateException` in `V2__seed_countries.sql`, `SQL State 42P01`), confirmed the copied emptiness predicate matches `PostgreSQLSchema#doEmpty()` of `flyway-database-postgresql-12.4.0` verbatim, and ran 160 targeted cases plus `db-roles.test.sh` with 0 failures. It found **no defect**, one advisory worth keeping: a database seeded before Flyway existed (V1's tables, no history) now fails loudly with `relation "countries" already exists` where version 1 applied 52 migrations — the DDL rolls back, no data is lost, and the documented one-time `flyway baseline -baselineVersion=1` restores that path (measured: `migrationsExecuted=52`). Its hardening suggestion is the fourth assertion in T5: `baseline-on-migrate=false` was measured to make a fresh volume fail with *"Found non-empty schema(s)"*, so the pair is pinned, not the version alone.
