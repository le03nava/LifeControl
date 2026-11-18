# ODD feature: gradle-version-catalog

**Repository**: LifeControl — root `settings.gradle`/`build.gradle` (deleted), `gradle/libs.versions.toml` (new), `api-gateway/` + `life-control-api/` (`settings.gradle`, `build.gradle`, `gradle/wrapper/`), `.github/workflows/`, `.github/scripts/tests/gradle-pins.test.sh`, `docker/scripts/_common.sh`, `.gitignore`, `AGENTS.md` + the two module `AGENTS.md`
**Branch**: `chore/gradle-version-catalog` · **Worktree**: `~/workspace/LifeControl-worktrees/chore-gradle-version-catalog`
**Issue**: #894 — `chore: the root build never runs, so nothing notices the two modules drifting apart`
**Status**: implemented — both slices are the branch's content. Slice A: one catalog reached by declaration, Spring Boot and loki converged on the newer value, one Gradle wrapper, the dead root build deleted, and the two silences a shared catalog creates closed with guards. Slice B: the gateway now runs the API's Spotless + SpotBugs gate, which reformatted the module and fixed four real aliasing findings. One decision is recorded instead of taken — the CI gate is a step of the existing `Check` job, not a job of its own, because job names are a contract `CONTRIBUTING.md` §6/§7 own; that split is the only thing left, and it belongs to whoever changes that contract.
**Created**: 2026-11-18
**Risk**: item 5 — this unit changes how both modules resolve every dependency, which wrapper they run, and what a CI `paths:` filter triggers. Nothing here is data (not item 1), no privilege boundary moves (not item 2), no public contract changes (not item 3), and the guards in T6/T7 are what keep it from being item 6: a version that leaves the catalog, a catalog change that does not rebuild the jar, and a catalog-only edit that triggers no workflow each get a check that fails.

## Delivery shape — two slices, chained

The two decisions the maintainer took on 2026-11-18 split this issue into two units that do **not** belong in one review:

| Slice | Branch | Why it is separate |
| --- | --- | --- |
| A — `gradle-version-catalog` | `chore/gradle-version-catalog` | One catalog, one Boot line, one wrapper, the dead root build gone, and the three traps the build actually has. Build-system work only. |
| B — gateway gates | follow-up branch | Spotless + SpotBugs in `api-gateway` reformats the whole module and can surface new findings. Its diff is measured in hundreds of reformatted lines that have nothing to do with versions |

Slice A carries `Refs #894` plus the chain declaration; slice B is the last one and carries `Closes #894`.

## Problem

Two independent Gradle builds, a third that never runs, and every version written by hand in two places.

- The root `settings.gradle` (2 lines) includes `api-gateway`, and root `build.gradle` (5 lines) declares Spring Boot `4.1.1` — while `api-gateway` and `life-control-api` each carry their own `settings.gradle` and `gradlew`, and there is **no root wrapper**. The root build is therefore never executed; the only thing in the tree that invokes `./gradlew` from the root is `AGENTS.md:679-682`.
- With no version catalog, each shared dependency is a literal in two files, and the literals already disagree: `loki-logback-appender` `1.5.2` (`api-gateway/build.gradle:30`) vs `1.6.0` (`life-control-api/build.gradle:72`); Spring Boot `4.1.1` vs `4.1.2` in the `plugins` block; the wrapper `9.8.1` vs `9.9.0`. `springdoc` is written four times across two pairs of coordinates.
- `spotless` and `spotbugs` exist only in `life-control-api/build.gradle:6-7`, so gateway code passes CI with no formatting or static-analysis gate (`gateway-ci.yml` runs `test` alone).
- `AGENTS.md` states Angular `20.3.0` against a lock that resolves `22.2.3`, and `life-control-api/AGENTS.md` states Spring Boot `4.1.1` against a build that says `4.1.2`.

Measured on `main` at `0b733e54` (after #896 and #898), every claim above still holds; see the Log for the line-by-line verification and the three lines that moved.

## Specs

- **S1 — one catalog, and it is the only place a version is written.** `gradle/libs.versions.toml` exists at the repository root, and both module build files resolve every catalog-managed dependency through it: a suite or a grep fails when a literal version appears outside the catalog. The catalog is shared by **declaration**, not by consolidation: each module's `settings.gradle` names it explicitly (`dependencyResolutionManagement { versionCatalogs { libs { from(files("../gradle/libs.versions.toml")) } } }`), which is the only mechanism Gradle offers here — a catalog is visible to the projects of *one* build, and a composite shares no configuration at all.
- **S2 — both modules declare the same Spring Boot plugin version**, and dependency insight on a BOM-managed library resolves the same version in both. The line to converge on is `4.1.2`, the newer of the two: it is a patch bump inside the same minor, which is what «the same Spring Boot patch line» asks for, and it is not the major-version upgrade the issue's non-goals forbid.
- **S3 — one wrapper.** `grep -h distributionUrl */gradle/wrapper/gradle-wrapper.properties` prints identical lines. The convergent line is `9.9.0`, and `gradle-pins.test.sh`'s pin table — which today freezes the two different versions on purpose — moves with it in the same commit.
- **S4 — the root build is gone, and no document claims otherwise.** Root `settings.gradle` and `build.gradle` are deleted; `AGENTS.md`'s "Backend (Gradle)" commands become module-scoped; `gradle-pins.test.sh`, which reads the root `build.gradle` today, stops depending on a file that no longer exists. Running a documented build command from the root must not be promised by anything the tree keeps.
- **S5 — no committed document states a version the corresponding manifest contradicts.** `AGENTS.md` states the Angular version the lock resolves; `life-control-api/AGENTS.md` states the Spring Boot plugin version its build declares. The skew between `keycloak-admin-client` `26.0.12`, the compose server `26.8.1` and the k8s manifests `24.0.1` is **recorded, not fixed**: images belong to the pinning unit (#896) and the client's version is «what the version is», which this issue's non-goals exclude.
- **S6 — the two modules run the same gate.** Slice B adds `spotless` and `spotbugs` to `api-gateway` with the configuration `life-control-api/build.gradle:33-60` already uses, adds `spotlessCheck spotbugsMain` to `gateway-ci.yml`, and triages whatever the analyzer reports. This is the first half of the acceptance criterion, chosen over the second (documenting why one module has no gate).
- **S7 — `./gradlew test` passes in both modules after the alignment.**
- **S8 — a catalog-only version change rebuilds the jar.** The container freshness decision in `docker/scripts/_common.sh:532-542` resolves its build inputs module-scoped and does not include repository-root Gradle files, so a version bumped in the shared catalog would reuse a stale jar **silently**. The catalog joins that pathspec, and a test fixes it.
- **S9 — a catalog-only edit triggers the workflows that build the modules.** `api-ci`, `gateway-ci` and `docker-build-integrity` filter by module path today, so the new file is in none of them — the same failure mode `pr-label-guard` was created for. The catalog joins each `paths:` list with `push`/`pull_request` parity, which `workflow-trigger-parity.test.sh` already enforces.

**Deliberately out of scope**, each with its reason: consolidating the two builds into one multi-project build (measured to cost both Dockerfiles, the compose contexts, `deploy.sh`, `_common.sh`, three workflows and both wrappers, for a result S1 reaches by declaration); any major-version upgrade or new dependency (the issue's non-goals); the `keycloak-admin-client`/server skew (S5); and the duplicated store-tree services, which are their own unit.

## Tasks

| ID | Specs | Task | Commit |
| --- | --- | --- | --- |
| T1 | S1 | `gradle/libs.versions.toml` with every shared version and both plugin versions, re-included in `.gitignore` (`/**/gradle/` would otherwise swallow it), and the `from(files(…))` declaration in both modules' `settings.gradle` | pid `f14751b80877` |
| T2 | S1, S2 | Both build files migrated to catalog accessors: the `plugins` block through `alias(libs.plugins.…)`, and every literal — loki, the four `springdoc` declarations, `keycloak-admin-client`, `bucket4j`, `logbook`, `Saxon-HE`, `springdoc` api, `spring-restdocs-mockmvc`, `spring-cloud-dependencies` — through `libs.…`, with Spring Boot converged on `4.1.2` | pid `f14751b80877` |
| T3 | S3 | The wrapper converged on `9.9.0` in `api-gateway` (properties, `gradlew` **and** the regenerated `gradle-wrapper.jar`), and `gradle-pins.test.sh`'s table updated to the single value it now freezes | pid `f14751b80877` |
| T4 | S4 | Root `settings.gradle` and `build.gradle` deleted, `gradle-pins.test.sh` no longer reading the deleted file, and `AGENTS.md`'s backend commands rewritten as module-scoped | pid `b857af66df75` |
| T5 | S8, S9 | The two traps: the catalog in `_common.sh`'s JAR-freshness pathspec, and the catalog in the three workflows' `paths:` lists with push/pull parity | pid `e4190c0b1ba6` |
| T6 | S1, S8, S9 | The guards: a suite that fails when a literal version appears outside the catalog, a case proving a catalog-only edit joins the freshness inputs, and the `paths:` parity case | pid `e4190c0b1ba6` |
| T7 | S5 | The documents: `AGENTS.md` Angular version, `life-control-app-angular/AGENTS.md` twice, `life-control-api/AGENTS.md` Spring Boot — each stating what its manifest resolves — plus the two `README.md` claims the slice's first pass left standing, and the recorded `keycloak-admin-client` skew | this record |
| T8 | S7 | `./gradlew test` in both modules, plus the offline suites the change touches | this record |
| T9 | S6 | `spotless` in `api-gateway/build.gradle`, with the configuration `life-control-api/build.gradle:33-60` already uses and its **plugin version from the catalog** (a literal would fail slice A's own guard), then the whole module reformatted by running it | pid `04ebf80ccd0c` |
| T10 | S6 | `spotbugs` likewise — the plugin, its config block and the `spotbugsMain` step in `gateway-ci.yml` — plus the triage: every finding either fixed or excluded with its reason written beside the exclusion | pid `be4ebfaf330d` |
| T11 | S6, S5 | `AGENTS.md` stops saying the gateway has no gate, and this record gains slice B's evidence and its final header | pid `04ebf80ccd0c` |

## Log

**L1 — the request.** «ok continua con 894», after the maintainer picked #894 from the four open `chore` issues as the next one: it closes the pin story #896 opened, and the measurement showed the drift is real.

**L2 — the measurement, run before any edit.** Yes, the design-fork decision that is worth reading twice.

**L3 — the two decisions the maintainer took on 2026-11-18, asked as one question because both change the size of the review.** (a) The root build **goes**, and the catalog is shared by declaration; the alternative — a real multi-project build — was measured to touch both Dockerfiles, the compose contexts, `deploy.sh`, `_common.sh`, three workflows and both wrappers, for a result the declaration reaches anyway. (b) `api-gateway` **gains** Spotless and SpotBugs rather than documenting why it has neither: the acceptance criterion allows either, and the maintainer chose the gate that is real over the sentence that excuses its absence.

**L4 — the measurement, run before any edit, on `main` at `0b733e54` (after #896 and #898).** All nine claims of the issue hold; three lines had moved and none of the drift had been repaired:

| Claim | Today |
| --- | --- |
| root build never runs | confirmed — `settings.gradle:1-2`, `build.gradle:1-5`, no root wrapper, and no workflow, script or hook invokes one except `AGENTS.md:679-682` |
| Boot `4.1.1` vs `4.1.2` | confirmed — `build.gradle:3` vs `life-control-api/build.gradle:3`, both literals in a `plugins` block, neither BOM-managed |
| no catalog | confirmed — no `libs.versions.toml` anywhere and no `versionCatalogs` reference in any `*.gradle` |
| loki `1.5.2` vs `1.6.0` | confirmed — `api-gateway/build.gradle:30` vs `life-control-api/build.gradle:72` |
| `springdoc` four times | confirmed, one line moved — `api-gateway:27,37` and `life-control-api:70,94`, all `3.1.1` |
| wrapper `9.8.1` vs `9.9.0` | confirmed, and deliberately frozen at `gradle-pins.test.sh:89-90` |
| spotless/spotbugs API-only | confirmed, `gateway-ci.yml:47` runs `test` alone while `api-ci.yml:53` runs `spotlessCheck spotbugsMain` |
| keycloak client vs server | confirmed — `life-control-api/build.gradle:79` `26.0.12`, `docker/docker-compose.yml:94` `26.8.1`, both k8s trees `24.0.1` |
| docs vs manifests | confirmed, one line moved — `AGENTS.md:179` and `life-control-app-angular/AGENTS.md:5,13` say Angular `20.3.0` against a lock that resolves `22.2.3`; `life-control-api/AGENTS.md:7` says Boot `4.1.1` |

**L5 — the three traps the measurement found, which are why this unit is not a find-and-replace.** (1) `.gitignore:32-33` ignores `/**/gradle/`, so a root catalog would be **untracked** unless re-included the way the two module wrappers already are (`:40-45`). (2) `docker/scripts/_common.sh:532-542` decides jar freshness from `$module_abs/{build.gradle,settings.gradle,gradle.properties,gradle/,buildSrc/}` — repository-root Gradle files are not in that pathspec, so a catalog-only bump would silently reuse a stale jar; `*.toml` is already scanned at `:565` for the module-scoped case, which is the shape to extend. (3) `api-ci`, `gateway-ci` and `docker-build-integrity` filter by module path and never name a root Gradle file, so a catalog-only edit triggers no workflow at all.

## Evidence log

Measured on 2026-11-18 in `~/workspace/LifeControl-worktrees/chore-gradle-version-catalog`, branched from `origin/main` at `0b733e54`.

| # | Spec | Measurement | Result |
| --- | --- | --- | --- |
| E1 | S1 | `./.github/scripts/tests/version-catalog.test.sh` — before the catalog existed | **RED**: 16 passed, 14 failed, rc 1 — no catalog file, neither `settings.gradle` declaring it, seven inline plugin versions, zero accessors |
| E2 | S1 | the same suite after T1/T2 | **GREEN**: 32 passed, 0 failed, rc 0 |
| E3 | S3 | `grep -h distributionUrl */gradle/wrapper/gradle-wrapper.properties` | the same line twice: `gradle-9.9.0-bin.zip`; the two wrapper dirs differ only in `retries`/`retryBackOffMs`, which the older generation of the same version did not write, and `diff api-gateway/gradlew life-control-api/gradlew` is empty |
| E4 | S2 | `./gradlew dependencyInsight --dependency org.springframework:spring-core --configuration runtimeClasspath` in **both** modules | rc 0 each, both resolve `org.springframework:spring-core:7.0.10`: the Boot `4.1.2` line is the same in both, so a BOM-managed library cannot diverge |
| E5 | S8 | `docker/scripts/tests/freshness-guard.test.sh` — before `_common.sh` changed | **RED**: 19 passed, 2 failed — case 14 returned 0 (fresh) where a root catalog change must return 1 (stale) |
| E6 | S8, S9 | the same suite and `workflow-trigger-parity.test.sh` after T5 | **GREEN**: 21/0 and 36/0, rc 0 each; the parity suite's RED was 30 passed, 6 failed, the catalog absent from the push **and** pull_request halves of all three workflows |
| E7 | S3, S4 | `./.github/scripts/tests/gradle-pins.test.sh` | **GREEN**: 21/0, rc 0 — it now freezes one wrapper version and the catalog's plugin block, and reads no root build file. Its RED after T1/T2 was 10 passed, 8 failed, which is the evidence the rewrite was required and not cosmetic |
| E8 | S7 | `./gradlew test` in **both** modules | `api-gateway`: 17 tests, 0 failures, 0 errors, 0 skipped, 23s. `life-control-api`: **5109 tests, 0 failures, 0 errors, 0 skipped**, 17m38s. Both on Gradle 9.9.0 |
| E9 | S5 | `grep -rn '20\.3\.0' --include=*.md .` and the Boot claim | no live document contradicts a manifest: `AGENTS.md`, both module `AGENTS.md` and both `README.md` state Angular `22.2.3` and Spring Boot `4.1.2`. The only remaining matches are ODD records, whose narratives are history and are not rewritten |
| E10 | — | shellcheck `--severity=warning` over `_common.sh` and the four touched suites, `bash -n _common.sh` | rc 0 each |

Provenance by **content**, per rule 8:

| Subject | `git patch-id --stable` |
| --- | --- |
| one catalog for two builds (catalog, `.gitignore`, both `settings.gradle`, both `build.gradle`, wrapper, `gradle-pins`) | `f14751b808778f20358db99a3f67230e5365d6f1` |
| the dead root build deleted | `b857af66df754caf647f3041f44ae29f1e546fcb` |
| the two silences closed, with their guards | `e4190c0b1ba6565bb0657f8d924a0450e38799f2` |
| the documents, the record's header and this log | this record (full id in the pull request) |
| slice B: the aliasing fix and its four tests | `be4ebfaf330d2839e37561238ff73fd87d529f13` |
| slice B: the gate, the reformat and its workflow step | `04ebf80ccd0cc369c33e006d821edf4ee7909637` |

**What these checks do not prove.** Nothing here ran a container build: S8 is proven by the freshness *decision* (the helper's own suite), not by building an image and watching it go stale. The `api-gateway` gate is the one thing slice A does not touch, so the mismatch it was written to end still exists until slice B lands — that is stated in `AGENTS.md` rather than papered over.

## Evidence log — slice B

Measured on 2026-11-18 in `~/workspace/LifeControl-worktrees/chore-gateway-static-analysis`, branched from slice A.

| # | Spec | Measurement | Result |
| --- | --- | --- | --- |
| E11 | S6 | `cd api-gateway && ./gradlew spotlessCheck` before applying | **RED**, rc 1: violations in **13 of 13** Java files — the whole module, which is the measurement that makes «the gateway had no formatting gate» concrete rather than rhetorical |
| E12 | S6 | `./gradlew spotbugsMain` before the fix | **RED**, rc 1, **4 `BugInstance`s**: `EI_EXPOSE_REP` twice (the two accessors) and `EI_EXPOSE_REP2` twice (the constructor) — the same aliasing defect from both ends |
| E13 | S6 | the four new `GatewayPropertiesTests` against the unfixed class | **RED**: 3 of 4 fail on the mutable view; the null-preserving case passed and stayed |
| E14 | S6 | after the fix, `spotlessCheck spotbugsMain` | **GREEN**, rc 0, report `total_bugs="0"`, and the baseline `api-gateway/config/spotbugs/exclude.xml` ships with **zero `<Match>`**: nothing was excluded, all four were fixed |
| E15 | S6, S7 | `cd api-gateway && ./gradlew cleanTest test` | **GREEN**: 21 tests, 0 failures, 0 errors, 0 skipped — the 17 that existed plus the 4 that pin the fix |
| E16 | S1, S3, S9 | `version-catalog` 32/0 · `gradle-pins` 21/0 · `workflow-trigger-parity` 36/0 · `contributing-job-names` 21/0 · `workflow-pins` 13/0 | all rc 0, run because the gateway now declares two more plugins and its workflow changed |

**The one decision this slice recorded instead of taking.** `gateway-ci.yml` runs `spotlessCheck spotbugsMain` as a step of its existing `Check` job, not as a second job named `Static analysis` the way `api-ci.yml` splits it. Job names are a documented contract — `CONTRIBUTING.md` §6 names each workflow's jobs and §7's branch-protection runbook quotes them — and `contributing-job-names.test.sh` fails on a name §6 does not carry; that was **proven**, not assumed, on a scratch copy where the second job made the suite report 20 passed / 1 failed («a workflow job name and §6 disagree»). Editing that contract and naming a required check is a branch-protection decision, so the split is left described here with its reason and its cost: the two gates serialize inside one job, which for this module is milliseconds.

**What slice B does not prove.** The gate is proven on this module's own suite and the analyzer's report; no CI run of the new step exists until the pull request runs it, so the workflow edit is verified by `workflow-trigger-parity`, `contributing-job-names` and `workflow-pins` rather than by a green CI job.
