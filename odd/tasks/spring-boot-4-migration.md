# ODD feature: spring-boot-4-migration

**Repository**: LifeControl — module `life-control-api/` (`build.gradle`, Gradle wrapper); `.github/dependabot.yml` read as evidence only
**Status**: deferred — not scheduled, and no source change was made. PR #205 was closed on 2026-10-03 (see the evidence log). What remains is the whole migration, unstarted; it is a major-version migration, not a dependency bump. This header makes no claim about branch or worktree state.
**Created**: 2026-10-03
**Origin**: read-only audit of the five open Dependabot PRs against `main` (`77f7387`).

## Objective

Decide what to do with PR #205 (`chore(deps): bump org.springframework.boot from 3.5.16 to 4.1.1`),
which proposed a major-version jump of the Spring Boot Gradle plugin on a single changed line.

The outcome of this record is a decision plus the evidence behind it, not code. No file in
`life-control-api/` was modified by this work.

## Evidence of the state (measured, not inferred)

### E1 — The proposed bump does not build

API CI run `36506947468`, job `109210380045`, fails at static analysis:

```
> Task :compileJava FAILED
* What went wrong:
Execution failed for task ':compileJava' ...
> Could not resolve all files for configuration ':compileClasspath'.
   > Could not find org.springframework.boot:spring-boot-starter-aop:
```

The empty version after the colon is the signature of a managed dependency whose version the BOM no
longer supplies — not of a compile error in the sources.

### E2 — Spring Boot 4 renamed the starter

Verified against Maven Central POMs, not from memory:

```bash
curl -sS -o /tmp/boot411.pom \
  https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom
grep -c "spring-boot-starter-aop" /tmp/boot411.pom          # -> 0
grep -oE "<artifactId>spring-boot-[a-z0-9-]*aspect[a-z0-9-]*</artifactId>" /tmp/boot411.pom | sort -u
# -> spring-boot-starter-aspectj, spring-boot-starter-aspectj-test

curl -sS https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom \
  | grep -oE "<artifactId>spring-boot-starter-aop</artifactId>" | sort -u
# -> spring-boot-starter-aop
```

So `4.1.1` manages `spring-boot-starter-aspectj` where `3.5.16` managed `spring-boot-starter-aop`.
`life-control-api/build.gradle` declares `spring-boot-starter-aop` (used by `ActivityLogAspect`), so
the rename is the first blocker, not the only one.

### E3 — The branch was not validated against current `main`

All five Dependabot branches were **152 commits behind `main`** at audit time
(`git rev-list --count <branch>..main`), and every check run was dated 2026-09-29, against the `main`
of that day. `mergeStateStatus: CLEAN` proves only that the one-line diff does not conflict; it is not
fresh validation of the change.

### E4 — Repository limits observed while auditing

- `.github/dependabot.yml` declares `open-pull-requests-limit: 5` and five version-update PRs were
  open. Per the GitHub options reference: "If five pull requests with version updates are open, no
  further pull requests are raised until some of those open requests are merged or closed."
  Security updates are outside that limit.
- `main` has no branch protection (`gh api repos/le03nava/LifeControl/branches/main/protection` ->
  404 `Branch not protected`), `allow_auto_merge: false`, and every open PR had an empty
  `reviewDecision`.

## Decisions

### D1 — Close PR #205 instead of merging or parking it

Merging the bump is impossible while CI is red, and leaving it open consumes one of the five
version-update slots indefinitely. Closed on 2026-10-03 with the reason stated on the PR.

### D2 — Do not add an `ignore` rule to `.github/dependabot.yml`

The consequence accepted with this decision: when Spring Boot publishes the next 4.x, Dependabot will
open a new major-bump PR, and it will need closing again. A narrow `ignore` (`org.springframework.boot`,
versions `>= 4.a`) would suppress that noise permanently, but the GitHub options reference documents
`allow.update-types` as version-update-only and carries **no equivalent note for `ignore`**, so it
cannot be assumed that `ignore` leaves security updates for this dependency intact. Trading automatic
security-update PRs for less noise was judged not worth it, and the rule's dependency name for a
Gradle *plugin* declaration was not verified either.

### D3 — Record the deferred work here rather than as a GitHub issue

The repository's issues are all closed and its live convention for durable work state is
`odd/tasks/`. See `references/feature-records.md`.

## Tasks (not started)

The migration surface is deliberately **not** enumerated here: enumerating it from memory would
invent scope. What is known and verified is the first step and the known-affected call site.

| # | Task | State |
| --- | --- | --- |
| T1 | Rename `spring-boot-starter-aop` to `spring-boot-starter-aspectj` in `life-control-api/build.gradle` and re-check `ActivityLogAspect` | not started — first verified blocker (E2) |
| T2 | Re-validate `io.spring.dependency-management` (`1.1.7`) against the Boot 4 Gradle plugin, or move to a Gradle `platform()` BOM declaration | not started — **GAP**, not verified locally |
| T3 | Re-evaluate `springdoc-openapi` (`2.8.17` / `2.9.1`) against Boot 4 — the 2.x line targets Spring Framework 6/7 and Boot 4 may need 3.x | not started — **GAP**, not verified locally |
| T4 | Scope the full migration (Spring Framework 7, Spring Security 7, Jakarta EE 11, transitive BOM moves) and re-plan as its own ODD record | not started |

## Out of scope

The other four Dependabot PRs audited in the same pass (#203, #204, #205-adjacent, #206, #207) are
independent version updates and are not part of this record's work.

## Gaps

| # | Gap | Why it matters |
| --- | --- | --- |
| G1 | A Dependabot `ignore` condition is in force for `org.springdoc:springdoc-openapi-starter-webmvc-api` (`[>= 3.a, < 4]`, reported by Dependabot in PR #204's body) although `.github/dependabot.yml` declares no `ignore` at all. | Dependabot configuration exists outside the repository file. Anyone "fixing" `dependabot.yml` would be editing a partial source of truth. |
| G2 | Whether an `ignore` rule also suppresses security-update PRs for the ignored dependency | Blocks D2's alternative; see D2. |
| G3 | The Dependabot dependency name that matches a Gradle plugin declaration (`id 'org.springframework.boot' version '...'`) | Needed before any `ignore` for the Boot plugin could be written correctly. |
| G4 | T2 and T3 above | They define the real size of the migration and were not verified. |

## Evidence log

| Date | Item | Evidence |
| --- | --- | --- |
| 2026-10-03 | PR #205 audited and closed | https://github.com/le03nava/LifeControl/pull/205, head commit `d0858def` |
| 2026-10-03 | CI failure captured for the closed PR | API CI run `36506947468`, job `109210380045` — `:compileJava FAILED`, `Could not find org.springframework.boot:spring-boot-starter-aop:` |
| 2026-10-03 | BOM rename verified against Maven Central | commands and outputs in E2 |
| 2026-10-03 | Branch staleness measured | `git rev-list --count origin/dependabot/gradle/life-control-api/org.springframework.boot-4.1.1..main` -> `152` |

## Next step

Nothing, until the migration is scheduled. When it is, start at T1 in a worktree off the then-current
`main`, then resolve G4 before scoping T4.
