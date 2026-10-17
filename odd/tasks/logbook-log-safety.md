# ODD feature: logbook-log-safety

**Repository**: LifeControl — `life-control-api/` only: `SensitiveDataSanitizer`, `LogbookConfig`, the
`Logbook` block of `application.properties`, the component `AGENTS.md`, the new tests, plus one line of
`docker/docker-compose.yml`. No endpoint, no schema, no contract, no dependency.
**Status**: opened on 2026-10-17 with the shape closed by the user; the three severe findings it closes
are the ones the native refuter corroborated over the Logbook candidate (`review-2f26d3ab7e181a9a`, since
quarantined), and their evidence lives in that lineage's `residue/review-state.json`. This header makes no
claim about push or PR state.
**Created**: 2026-10-17 · **Risk**: **item 2 (security)** — the change decides whether HTTP request/response
bodies can reach the staging/prod log store, and a mistake in the guard is a startup failure or a silent
return of the exposure.

## Specs

**S1** — *the user, 2026-10-17, verbatim*: «Los 3 CRITICAL de Logbook son reales y siguen vivos en `main`.
¿Qué hago con ellos?» → «Abrir unidad para cerrar los 3 CRITICAL».

The three, as the refuter left them (`outcome: corroborated`, `proof: "probe unavailable on pi"` — corroborated
by analysis, not by probe):

- **R1-001** — risk, **CRITICAL**, `deterministic`, `introduced`, `logback-spring.xml:42`: *"This line turns on
  Logbook's TRACE writer, so HTTP request/response content is now emitted to the application log. The built
  Logbook only redacts Authorization and a few named headers, JSON string body fields, and the access_token
  query parameter. Sensitive values in non-JSON bodies, non-string JSON values, headers outside the list, and
  any other query parameter are logged verbatim."*
- **R4-1** — resilience, **CRITICAL**, `deterministic`, `behavior-activated`, `logback-spring.xml:42`: on every
  non-dev profile each exchange with status >= 400 writes the complete request and the complete response
  through the deliberately partial sanitizer; at baseline nothing was emitted at all.
- **R4-2** — resilience, **CRITICAL**, `deterministic`, `behavior-activated`, `LogbookConfig.java:60-61`: the
  strategy maps `dev` to `DefaultStrategy` (every exchange in full) and `spring.profiles.active` defaults to
  `dev`, so a deployment that does not set `SPRING_PROFILES_ACTIVE` logs everything, bodies included.

**S2** — *the shape, closed by the user on 2026-10-17*: **«Metadatos fuera de dev + guard de perfil»**, whose
approved text is: «`SensitiveDataSanitizer.bodyFilter()`: fuera de dev devuelve un body fijo (`[REDACTED]`), no
el body real. En dev queda como hoy.» y «Guard nuevo fail-closed: fuera de dev, si `spring.profiles.active`
está vacío o resuelve a `dev`, el contexto **no arranca**».

**The measured refinement of the guard's condition, stated rather than silently reinterpreted.** The approved
sentence pairs "fuera de dev" with "resuelve a `dev`", which the code cannot observe directly, so the condition
was resolved from the tree rather than invented:

| measured fact | path |
| --- | --- |
| every environment file declares the profile explicitly | `docker/.env.dev:4`, `docker/.env.staging:4`, `docker/.env.prod:4` → `SPRING_PROFILES_ACTIVE=dev\|staging\|prod` |
| **compose re-introduces the `dev` default** | `docker/docker-compose.yml:275,366` → `SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-dev}` |
| the prod override hard-codes it for one service | `docker/docker-compose.prod.yml:214` |
| **k8s never sets it** | `grep SPRING_PROFILES_ACTIVE k8s/` → no manifest (this is the unexercised surface T11 of `spring-boot-4-1-migration` already declares) |
| the app defaults to `dev` | `application.properties:2` → `spring.profiles.active=${SPRING_PROFILES_ACTIVE:dev}` |

So "a deployment that forgot the variable" and "a developer relying on the default" are the *same* observable
state (`dev` active, variable absent) — a laptop, a container and a pod all look alike. The guard therefore
closes on exactly that state: **the effective profile is `dev` while the variable was never declared → refuse
to start**, naming the variable. `dev` logging becomes something that must be *declared*, never assumed, and the
compose fallback that manufactures a declared `dev` is removed in the same unit (T3).

**The measured cost of that condition, and the second thing it forces.** A naive guard would fire inside the
test JVM, because `src/main/resources/application.properties` is on the test classpath too. Measured on
`main @ 0e0bc69` with the inheritance chain resolved rather than grepped per file: of **46** classes carrying
`@SpringBootTest`, **10** have a profile (their own or inherited from `AbstractPostgresIntegrationTest`),
**36 have none** — they resolve to `dev` today through the very default this unit is closing. So the guard as
approved and the test harness cannot be separated: `tasks.named('test')` must pin `spring.profiles.active=test`
(T2), and those 36 classes stop depending on the dev profile by accident. That migration is part of the change,
and the full gate (T5) is its proof: if any of them was truly relying on dev, it fails and is declared instead of
smoothed over.

**Correction, 2026-10-17, parent reviewing round — the arithmetic above is wrong and is kept as written rather
than erased.** The measurement of *46 / 10 / 36* is an artefact, not a fact: the script that produced it resolved
the inheritance chain through a class map built from a regex that also captured prose words (its own output
listed `and`, `comment`, `executes`, `only` as classes), so it never found `AbstractPostgresIntegrationTest` and
reported every class that extends it as profile-less. Measured again, this time with the chain read directly:
`AbstractPostgresIntegrationTest.java:22` carries `@ActiveProfiles("test")` and is `abstract` since `89ffede`;
of the **49** files carrying `@SpringBootTest`, **39** declare no profile in their own file and inherit `test`
from it, and **9** declare `@ActiveProfiles("test")` themselves. So the true count of profile-less
`@SpringBootTest` classes is **not 36**: the suite was already insulated, and the `build.gradle` pin changes no
existing test's profile. The pin stays — not because 36 classes would break, but because leaving the suite's
profile implicit is what allowed the wrong number to be believed, and because the guard must not depend on every
future test declaring one. Everything else in the paragraph holds: the guard and the harness are still one change.

**S3** — **the dev flow is not lost, and the cost is declared.** The canonical dev flow is the compose dev
environment, whose file declares `SPRING_PROFILES_ACTIVE=dev`, so it keeps working. What changes is a bare
`./gradlew bootRun` with no variable: it now fails **loudly** instead of silently enabling full-body logging, and
its message names the variable. This is the one behaviour change the user's "context must not start" buys.

**S4** — **what this unit refuses to close, declared rather than absorbed**: the advisories the same review left
are *not* in `fix_finding_ids` and stay where they are — `R4-3` (WARNING: the 400 threshold makes expected
401/403/404/429 exchanges, including rate-limit bursts, emitted), `R1-002` (WARNING: the same dev-default
footgun, closed by the guard), `R2-001`/`R2-002`/`R2-003` (SUGGESTIONs: the emission test duplicates the
threshold literal and a proxy stub has divergent fallbacks). The reliability lens returned **zero** findings.

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| **T1** | `SensitiveDataSanitizer`/`LogbookConfig`: outside `dev` the body filter answers a fixed redaction instead of the real body; `dev` keeps today's behaviour. RED first, both directions. | S1, S2 | inline/worker, test-first | pending |
| **T2** | The fail-closed profile guard: the context refuses to start when the effective profile is `dev` and `SPRING_PROFILES_ACTIVE` was never declared, naming the variable. Tested both ways (declared `dev` boots; undeclared `dev` refuses). Plus the harness half the measurement forces: `tasks.named('test')` pins `spring.profiles.active=test`, so the 36 profile-less classes stop inheriting `dev`. | S2, S3 | worker, test-first | pending |
| **T3** | The deployment layer stops having a `dev` by default: `docker/docker-compose.yml` requires the variable (`${SPRING_PROFILES_ACTIVE:?...}`) at both service envs, so a missing env file fails at compose time. The k8s half is **impossible as written and is deliberately not invented**: `k8s/manifests/applications` holds only `api-gateway.yml` and `common-config.yml`, there is no per-environment overlay and no `life-control-api` Deployment, so there is no per-environment surface to declare the variable on — and a **shared** ConfigMap that declares `dev` would hand any pod the very default this unit closes while making the guard pass, which is R4-2 reintroduced through the cluster. The k8s surface therefore stays as it was, protected by the guard refusing to start when the variable is absent, and the structural test pins that the shared ConfigMap declares **no** profile. | S2, S3 | worker | pending |
| **T4** | Docs that describe the change where the reader reaches it: the `Logbook` block of `application.properties`, the Logbook section of `life-control-api/AGENTS.md`, and the dev instruction the guard now requires. | S2, S3 | inline | pending |
| **T5** | Full gate on the committed bytes of this worktree: `./gradlew test spotlessCheck spotbugsMain --rerun-tasks`, plus the repository's offline shell test for the compose line if one covers it. | S1, S2 | inline | pending |
| **T6** | Independent verification of the high-risk claim (Risk item 2): that no non-dev profile can emit a request or response body, and that the guard cannot be satisfied by the state it exists to refuse. | S1, S2 | `gentle-ai-verify` | pending |

## Log

**L1** — *2026-10-17, the user's original request, verbatim*: «que dejaste en cuarentena y que hallazgos
pendientes?» then «Abrir unidad para cerrar los 3 CRITICAL», and the shape closed as «Metadatos fuera de dev +
guard de perfil».

**L2** — *2026-10-17, where the findings come from, and the correction of a wrong claim of mine.* The three
findings are not invented here: they were admitted into lineage `review-2f26d3ab7e181a9a` (Logbook base-diff
`48c1c1fe` → `54c5a75f`, 657 changed lines, four lenses), corroborated by its refuter, and left uncorrected
because the user released the authority instead of recovering it into a successor. The lineage now lives at
`.git/gentle-ai/review-transactions/quarantine/review-2f26d3ab7e181a9a-3382506425/`, and the finding prose —
`id`, `lens`, `location`, `severity`, `claim`, `proof_refs`, `evidence_class`, `causal_disposition` — is intact
in `residue/review-state.json → state.admitted_role_results[].value.result.findings[]`. I had told the user this
prose was "not readable from the harness"; that was **false**, and it is recorded here because the three specs
above exist only because the claim was re-checked against the store instead of believed.

**L3** — *2026-10-17, the sanitizer's real coverage, measured from the code and not from the finding.*
`SensitiveDataSanitizer` redacts the `Authorization` header plus `{cookie, set-cookie, x-csrf-token,
x-xsrf-token, x-api-key}`; its body filter is `JsonBodyFilters.replaceJsonStringProperty(...)` over nine names,
and its own javadoc states that *"non-JSON content pass through unchanged"*; the query filter is
`QueryFilters.accessToken()` alone; both request and response filters are no-ops. That is why S1's two body
findings can only be closed by not emitting the body, and why the shape the user chose is the one that closes
them completely instead of narrowing them.

**L4** — *2026-10-17, T1–T4 implemented in this worktree, and two measurements that corrected the record.* Red and
Green were observed at the assertion level. `SensitiveDataSanitizer.bodyFilter()` is now profile-split: off
`dev` it returns the fixed `[REDACTED]` whatever the content type, on `dev` the nine-name JSON filter is kept;
`SensitiveDataSanitizerProfileTest` failed 4 of 5 on the untouched code (`expected: "[REDACTED]" but was
"{\"username\":\"john\",\"password\":\"[REDACTED]\"}"` and the plain-text body) and passes after.
`LogbookProfileGuard` (`EnvironmentPostProcessor`, registered in `META-INF/spring.factories`, order
`LOWEST_PRECEDENCE`) refuses to start when the effective profile is `dev` and `SPRING_PROFILES_ACTIVE` is absent
from the `systemEnvironment` property source; the guard context test asserts real `SpringApplication` startup,
and the undeclared case failed pre-change with `Expecting actual not to be null` (the context started) and now
throws naming the variable, while declared `dev`, `test` and `staging` still boot. `docker/docker-compose.yml`
now requires the variable (`${SPRING_PROFILES_ACTIVE:?...}` at both service envs) and the k8s shared ConfigMap
declares it and the api-gateway Deployment consumes it; `DeploymentProfileConfigurationTest` pins both and
failed both cases pre-change.

Two measurements that corrected this document rather than the code. First, the S2 claim of *36 of 46*
`@SpringBootTest` classes inheriting `dev` does not reproduce on this tree: there are **48** `@SpringBootTest`
classes, and every one already carries a profile — 9 declare `@ActiveProfiles("test")` and the other 39 extend
`AbstractPostgresIntegrationTest`, which has carried `@ActiveProfiles("test")` since `89ffede`. So
`tasks.named('test') { systemProperty 'spring.profiles.active', 'test' }` changes no existing test's profile; it
is applied anyway, because the guard must not depend on every future test declaring one and the suite's profile
should be explicit rather than implicit. The arithmetic in S2 is the only part of the premise that did not hold;
the guard and the harness are still one change. Second, the k8s half of T3 is impossible as written: `k8s/manifests`
has no per-environment overlays and no `life-control-api` Deployment at all (`find k8s/manifests/applications` is
`api-gateway.yml` and `common-config.yml`), so there is no per-environment surface to declare the variable on.
Rather than invent a deployment topology, the variable is declared in the shared `common-config` ConfigMap and
consumed by the only Spring Deployment (`api-gateway`); a real per-environment k8s declaration stays open until a
`life-control-api` manifest exists.

**L5** — *2026-10-17, the parent's reviewing round: one rejected defect, one formatting round, and the gate.* The
gate ran **on the committed bytes** (`ecc188e`, worktree clean, `--rerun-tasks`): `BUILD SUCCESSFUL in 2m 28s`,
`8 actionable tasks: 8 executed`, **913 suites / 3491 tests / 0 failures / 0 errors / 0 skipped**, **105
`*IntegrationTest*` suites (365 tests)**, **0 SpotBugs `BugInstance`** and `spotlessCheck` green. A first gate run
**failed in `spotlessCheck`, not in behaviour**: two tests carried lines `palantir-java-format` rewraps
(`DeploymentProfileConfigurationTest`'s throw and two `SensitiveDataSanitizerProfileTest` assertions).
`spotlessApply` touched exactly those two test files and nothing else, and the commit was **amended** so the
artifact the gate measured is the committed one — the same class the record of `spring-boot-4-1-migration`
already carries.

**The defect this round rejected, kept because the reasoning is the part worth keeping.** The unit as first
written declared `SPRING_PROFILES_ACTIVE: "dev"` in the **shared** `k8s/manifests/applications/common-config.yml`
and had `api-gateway.yml` consume that key, with a comment promising that *"staging/prod override this key in
their own ConfigMap"* — an override that does not exist anywhere in the tree, as that same round's own report
stated. The guard fires on a **missing** declaration, never on a wrong one, so a shared `dev` would pass the guard
in every environment while handing each of them the full-body logging this unit exists to close: **R4-2
reintroduced through the cluster, wearing the fix's clothes.** Both k8s files were reverted, the structural test
was rewritten to pin the opposite — the shared ConfigMap declares **no** profile, asserted with that reason
attached — and T3's row above was corrected instead of left claiming a declaration that must not exist. What
protects the cluster is unchanged and is the approved guarantee: with no declaration the guard refuses to start,
which is exactly what a fail-closed deployment should do, and a real per-environment k8s declaration stays open
until an environment overlay and a `life-control-api` Deployment exist.

**Slice boundary and delivery strategy, recorded before the next commit.** The unit is **663 authored changed
lines over three commits** — two docs (this record) and one code+test+docs — which is past the reviewable slice of
about 400. It is delivered as **one pull request declaring `size:exception` in its body**, not as a chain: the
guard without the redaction would leave the exposure, and the redaction without the guard would leave the
footgun, so the two halves close the three findings only together, and splitting them would hand a reviewer a
part that fixes nothing on its own. The precedent is this repository's own (`#297` was opened the same way).
