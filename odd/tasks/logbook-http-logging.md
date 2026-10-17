# ODD feature: logbook-http-logging

**Repository**: LifeControl — `life-control-api` only: `logback-spring.xml`, the Logbook configuration
class, the `Logbook` block of `application.properties`, the component `AGENTS.md`, the test logback
configuration and three new test classes. **No endpoint, no schema, no contract, no dependency and no CI
change.**
**Status**: **delivered** — `fe376f5` (one code/configuration/test/documentation slice) plus the record commit
`6005b53` were merged to `main` as **PR #351 → `0e0bc69`** on 2026-10-17, tree-identical to the head that was
gated (`6005b53^{tree}` == `0e0bc69^{tree}` == `54c5a75fc4157c8319b29db22c21280db4159379`).
**Created**: 2026-10-16
**Risk**: **medium.** Nothing irreversible and no contract: the change is one logger line, one bean and
comments. What makes it more than cosmetic is that it **turns on** a stream that was previously dead —
HTTP request/response content in the logs — through a sanitizer that is deliberately partial, on a profile
default that reaches staging and prod. Both facts are now stated in the files a reader reaches.

## Objective

Make Logbook's HTTP request/response logging actually emit, and decide **what** it emits per environment.
The component was not misconfigured; it was silently suppressed: `LogbookConfig` built a fully filtered
`Logbook` and no line ever reached the log.

## Why this exists

The decision was deferred by its predecessor, PR #346 (`chore(config): drop a Logbook property that does
not exist`, merge commit `0e50283`, 2026-10-16), which removed a property no library reads and closed with:

> The operational question the dead line was reaching for — should the API emit HTTP request/response logs
> at all, and with which strategy — stays open and is not decided by this PR.

This unit answers that question. The user handed it over as an already-authored change set parked in
`stash@{0}`:

```
stash@{0}: On main: logbook-logging (reverted 2026-10-16: no compatible con la migracion a spring-boot-4 en paralelo)
```

The stash's own recorded reason for the revert is **dispositioned here by measurement, not by assumption**:
the change compiles and the whole suite passes on Spring Boot 4.1.1 with `logbook-spring-boot-starter`
4.2.0 (see the evidence log). The one real incompatibility was **formatting**: two of the three test files
predate `palantir-java-format` 2.98.0 and failed `spotlessCheck` until `spotlessApply` re-wrapped them.

## Evidence of the defect (measured, not inferred)

**Logbook writes through exactly one logger, at TRACE.** From
`logbook-core-4.2.0.jar` → `org/zalando/logbook/core/DefaultHttpLogWriter.class`:

| Fact | Evidence |
|---|---|
| The logger is `org.zalando.logbook.Logbook` | constant pool `#7 = Class org/zalando/logbook/Logbook` passed to `LoggerFactory.getLogger:(Ljava/lang/Class;)` |
| It writes at TRACE | `Logger.isTraceEnabled()` and `Logger.trace:(Ljava/lang/String;)V` |
| `isActive()` is that same check | `public boolean isActive()` calls `isTraceEnabled()` |

**The pinned logger therefore decided whether anything was emitted at all.**

| Before | After |
|---|---|
| `<logger name="org.zalando.logbook" level="WARN"/>` (`logback-spring.xml:34`) | `<logger name="org.zalando.logbook.Logbook" level="TRACE"/>` |

A package-level logger at WARN caps `org.zalando.logbook.Logbook` at WARN, `isTraceEnabled()` is false, and
`isActive()` is false: every exchange is discarded before it is ever formatted.

**Redaction, verified in the bytecode of the artifacts this component actually uses** (not from memory):

| Claim in `AGENTS.md` | Evidence |
|---|---|
| `Authorization` becomes `XXX` | `core.HeaderFilters.authorization()` → `replaceHeaders("Authorization", "XXX")` |
| an `access_token` query parameter becomes `XXX` | `core.QueryFilters.accessToken()` → `replaceQuery("access_token", "XXX")` |
| only **string** JSON values of the named fields are redacted | `JsonBodyFilters.replaceJsonStringProperty` — a JSON number, object or array under those names passes through |
| the named sensitive headers become `[REDACTED]` | `SensitiveDataSanitizer.SENSITIVE_HEADERS` = `cookie`, `set-cookie`, `x-csrf-token`, `x-xsrf-token`, `x-api-key` |

## Specs

- **S1** — *the user, 2026-10-16, verbatim*: «puedes aplicar  Logbook está mudo: logback-spring.xml:34 fija
  org.zalando.logbook en WARN mientras Logbook escribe en TRACE → no registra nada útil. Es exactamente lo
  que el stash arregla.» The request is to **apply the stash**; its content is therefore the specification,
  and the diagnosis it states is confirmed above against the bytecode.
- **S2** — **the logger pin is the class, never the package.** The stash's own line, kept verbatim:
  `<logger name="org.zalando.logbook.Logbook" level="TRACE"/>`. Measured reason, from the stash's comment:
  Spring Boot's condition-evaluation lines about the Logbook auto-configuration are emitted under those
  classes' own names, so a package-level logger turns them on too. The package they live in exists in this
  build: `logbook-spring-boot-ecs-autoconfigure-4.2.0.jar` ships `org.zalando.logbook.ecs.autoconfigure`
  and `org.zalando.logbook.ecs.autoconfigure.condition`, and the starter's POM declares that artifact as a
  dependency.
- **S3** — **the profile decides the volume**, as `LogbookConfig.logbookStrategy`'s comment states: the
  `dev` profile logs every exchange; every other profile answers only a status `>= 400`, where
  `MINIMUM_LOGGED_STATUS = 400`. The profile, not a property, is the source of truth, because this
  component defines the `Logbook` bean itself and the starter's `logbook.strategy` /
  `logbook.write.status-code-based` properties bind to the auto-configured one instead.
- **S4** — **the sanitizer is not changed**, and what it does not redact is declared where a reader reaches
  it. The stash's rows in `AGENTS.md`, kept verbatim: *"**Not** redacted, and therefore what a refusal can
  put in staging/prod logs: non-JSON bodies (form-encoded, XML, plain text), non-string JSON values of
  those field names, headers outside the list above, and any query parameter other than `access_token`"*.
- **S5** — **the tests travel with the behaviour**: `LogbookLogbookLoggerPinningTest` fails if
  `logback-spring.xml` stops enabling that exact logger or re-introduces the package logger,
  `LogbookConfigTest` pins the strategy per profile and the halves it emits, and `LogbookEmissionTest`
  drives the built `Logbook` with the real sanitizer.

## Tasks

| ID | Spec | What | Route | Commit |
|---|---|---|---|---|
| T1 | S1, S2 | `logback-spring.xml`: package `WARN` → class `TRACE` | inline | `fe376f5` |
| T2 | S1, S3 | `LogbookConfig`: `logbookStrategy` bean + `QueryFilters.accessToken()` wired into the built `Logbook` | inline | `fe376f5` |
| T3 | S4 | `application.properties` and `AGENTS.md`: state what is and is not redacted, and which profile logs what | inline | `fe376f5` |
| T4 | S5 | restore the three test classes the stash carried **untracked** | inline | `fe376f5` |
| T5 | S1 | normalise formatting and run the applicable gates | inline | `fe376f5` |

**Slice boundary, recorded as required**: T1–T5 are **one** commit of 478 changed lines (79 of
configuration, comments and documentation; 399 of tests). It was not split, and the reason is in the rule
rather than in the numbers: tests, documentation and configuration of *one* behaviour are one reviewable
unit, and the tests are what make the other 79 lines checkable.

## Decisions

- **D1 — pin the class, drop the package logger.** The narrower logger is the one that turns the writer on;
  the broader one additionally enables Spring Boot's condition-evaluation lines for the ECS
  auto-configuration. The removal of the package-level suppression widens every other
  `org.zalando.logbook.*` logger to the root level, which is **measured as empty in practice**: those lines
  are emitted at DEBUG/TRACE, so the root `INFO` still hides them. The pinning test asserts the absence of
  the package logger, so a future re-introduction is a red test and not a silent one.
- **D2 — the profile decides, not a property.** A property would have to be kept in step across three
  compose environments; the profile is already passed by all of them, and it also carries the reason
  (`dev` is the environment where seeing every exchange is worth the volume).
- **D3 — the sanitizer stays untouched, and its gaps get written down.** Widening the redaction set is a
  separate decision with its own compatibility surface (form-encoded bodies need a different filter). What
  this unit owes the next reader is the honest list, which S4 puts in the two files that describe the
  configuration.
- **D4 — the acceptance the revert asked for is the suite, not a claim.** `spring.profiles.active` in this
  component defaults to `dev` (`application.properties:2`), so the write path this unit enables is exercised
  by every Spring context the suite boots; 3479 tests pass with it in place.

## Gaps and follow-ups

- **The `dev` default reaches deployments that forget the variable.** `spring.profiles.active=${SPRING_PROFILES_ACTIVE:dev}`
  means an unset variable logs **every** exchange, not only refusals. The compose files set it per
  environment; nothing fails closed if one stops doing so. Recorded, not fixed here: changing a profile
  default is not this unit's decision.
- **The sanitizer's partial coverage is now load-bearing.** With the logger silent, its gaps were
  unobservable; now they are the real exposure surface. Non-JSON bodies, non-string JSON values, headers
  outside the list and every query parameter other than `access_token` reach the log unredacted. No test
  pins the gaps as gaps.
- **Nothing measures log volume.** No test or dashboard asserts how many exchanges a profile actually
  emits; the strategy is pinned, the resulting volume is not.
- **Unrelated defect found while running the gates, not fixed here**: the repository-root Gradle build does
  not configure at all. `build.gradle` (untouched since the `Inicial` commit) pins the Spring Boot plugin at
  `3.4.0` while `api-gateway/build.gradle` declares `4.1.1`, so any Gradle invocation from the root fails
  with *"the plugin is already on the classpath with a different version (3.4.0)"*. CI does not see it
  because every workflow runs from a module directory. It belongs in its own issue. *(**Closed on 2026-10-17**
  by its own unit, `chore/root-gradle-boot-pin`, delivered as PR #353 → `b0ba086`; the measurement and why CI
  never saw it are in `L4`.)*

## Evidence log

| Date | Evidence |
|---|---|
| 2026-10-16 | Applied `stash@{0}` with `git stash apply`: 5 tracked files modified, and **3 test classes restored as untracked files** from the stash's third parent `5b04fc8a` — `git stash show --stat` does not list them, the record states them because a delivery that trusted that listing would have committed the configuration without the tests. |
| 2026-10-16 | `cd life-control-api && ./gradlew test --tests 'com.lifecontrol.api.config.logbook.*' --no-daemon` → `BUILD SUCCESSFUL`, 7 tests, 0 failures (the 6 new ones plus the pre-existing `SensitiveDataSanitizerTest`, unchanged and green: the sanitizer's own behaviour is not a regression of this unit). |
| 2026-10-16 | `./gradlew spotlessCheck spotbugsMain --no-daemon` → **failed** on `LogbookEmissionTest` and `LogbookLogbookLoggerPinningTest` (pre-`palantir-java-format`-2.98.0 wrapping). `./gradlew spotlessApply` re-wrapped both; the pre/post diff is line wrapping only; the gate then passed. This is the one real incompatibility behind the stash's revert note. |
| 2026-10-16 | `./gradlew test --no-daemon` on the **formatted** tree → `BUILD SUCCESSFUL`, **3479 tests, 0 failures, 0 errors, 0 skipped** (the suite boots the Spring context, so it also proves the new `Strategy` bean introduces no injection ambiguity: the starter's own `strategy()` is `@Bean @ConditionalOnMissingBean(Strategy.class)` and backs off). |
| 2026-10-16 | Bytecode and artifact checks, all against the versions this component resolves (`logbook-core` 4.2.0, `logbook-spring-boot-autoconfigure` 4.2.0): `DefaultHttpLogWriter` logger/level/`isActive()`, `HeaderFilters.authorization()`, `QueryFilters.accessToken()`, the `ecs.autoconfigure` package in the starter's own dependency list. |
| 2026-10-16 | `fe376f5` — one commit, 8 files, 478 insertions / 12 deletions, on `fix/api-logbook-logging` off `main` @ `b8b82a6`. |

## Log

- **L1** — *the user, 2026-10-16, verbatim*: «puedes aplicar  Logbook está mudo: logback-spring.xml:34 fija
  org.zalando.logbook en WARN mientras Logbook escribe en TRACE → no registra nada útil. Es exactamente lo
  que el stash arregla.»
- **L2** — the user then asked for the pull request, which is what moves `fe376f5` and this record onto a
  branch and into delivery. The delivery itself is recorded when it is known, not predicted here.
- **L3** — *2026-10-17, the delivery.* PR **#351** (`fix/api-logbook-logging`, head **`6005b53`**) was merged to
  `main` as **`0e0bc69f8023d8d2a9c56b95418cb6021399a679`**, `--merge` and never a squash, with **no
  `--delete-branch`** (`delete_branch_on_merge` reads `false` in this repository): the branch stays in `origin` as
  evidence. **The parents fix the order and the proof is tree identity, available because `main` had not moved
  under the cut**: `0e0bc69` ← `b8b82a6` (the previous `main`, which is also the base the branch was cut from)
  + `6005b53`, and `6005b53^{tree}` == `0e0bc69^{tree}` == `54c5a75fc4157c8319b29db22c21280db4159379`, so the
  merge added the gated bytes and not one byte more. **The diff the merge added to `main`** is **9 files / +645
  −12**, equal to the numbers the pull request itself reports.
- **L4** — *2026-10-17, the defect this record declared belonged in its own issue was fixed in its own unit.* The
  repository-root `build.gradle` — untouched since the `Inicial` commit, pinning `org.springframework.boot` at
  `3.4.0` while `api-gateway/build.gradle` declares `4.1.1` — was aligned by the unit
  `chore/root-gradle-boot-pin` (PR **#353**, head `686d22a`, merged as **`b0ba086`**): `org.springframework.boot`
  **3.4.0 → 4.1.1** and `io.spring.dependency-management` **1.1.6 → 1.1.7**, 1 file / **+2 −2**. **RED was measured
  before the change and GREEN after it**: `./gradlew projects --offline` answered **BUILD FAILED**, *"the plugin
  is already on the classpath with a different version (3.4.0)"* pointing at `api-gateway/build.gradle:3`, and
  after the pin `projects` answered **BUILD SUCCESSFUL in 2s** and `:api-gateway:compileJava --offline`
  **BUILD SUCCESSFUL in 10s**. **Why it survived this long is the part worth keeping**: all four workflows run
  with a `working-directory` of their own module, so none of them ever invoked the root build, and **no test pins
  it** — the defect was real and invisible to CI at the same time, which is what this record's own bullet meant by
  *belongs in its own issue*. Tree identity is not available for this merge — `main` had moved by #354's merge
  before it landed — so the proof is content: the diff `b0ba086` added to its own previous `main` (`11275c4`) is
  **1 file / +2 −2**, equal to the pull request's own numbers. **No source line of this record changed.**
