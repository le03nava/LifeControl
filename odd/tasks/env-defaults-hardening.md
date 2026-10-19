# ODD feature: env-defaults-hardening

**Repository**: LifeControl — `docker/` (`.env.template`, `docker-compose.override.yml`,
`scripts/validate-env.sh`, a new static test, a new verification script), one new startup-warning
component and one additive field in `life-control-api/**`, one banner in `life-control-app-angular/**`,
and two documentation edits (`AGENTS.md`, `odd/tasks/employee-access-provisioning.md`). No schema, no
migration, no gateway change, no existing contract removed or changed.
**Status**: the work of `T1`–`T5`, `T7` and `T8` is delivered as the chained pull requests **#458**,
**#459** and **#460**, and the evidence of each slice is recorded in the log below (`L9`–`L15`); `T6` and
`T9` are the two tasks still open, both behind the worker defects `G45`–`G47` that
`odd/tasks/employee-access-provisioning.md` now owns.
**Created**: 2026-10-19 · **Risk**: **item 5 (delivery/environment)** — `docker-compose.override.yml`,
`.env.template` and `validate-env.sh` are consumed by all three environments, and `validate-env.sh` is
the **prod start gate** (`deploy.sh:173-179` exits 1 when it fails), so a check added there can stop a
production start. **Item 3 is explicitly not in scope and is guarded**: `T4`'s API field is additive
and no existing field is removed or re-typed.
**Worktree**: `~/workspace/LifeControl-worktrees/fix-env-defaults-hardening`, branch
`fix/env-defaults-hardening`, base `main` @ `81d0205`.
**Requested by**: the user — the question about how to validate whether the activation email was sent
(`S1`), the ask for the root fix (`S2`), and the authorization (`S4`).

## Origin

The user created an employee, assigned a contract, and saw the Access section report an `ACTIVATE`
task as **Pendiente** — and asked how to validate whether the activation email had been sent. It had
not: `S5` measures why. Answering it exposed two defects that share one shape — **a default that is
applied silently**:

1. The access-provisioning worker ships `false` in every environment, and **nothing anywhere declares
   that** (`S5.3`). A `PENDING` task and a task nobody will ever pick up are indistinguishable.
2. An environment file is created **once** from the template and never told about keys added later, so
   a new knob silently runs on the compose default instead of a declared value (`S5.2`).

They are one feature because the same three artifacts carry both (`docker/`), because the second is
what made the first possible, and because the same decision — *the operator must be able to see which
default is in force* — resolves both.

## Specs

**S1** — *the user, 2026-10-19, verbatim*:

> «como puedo validar si cree un empleado y asigne un contrato si se mando el correo de activacion
> Historial Activación Pendiente 2026-10-19T06:17:09.042526»

Answered read-only with the diagnosis of `S5`. The timestamp in the question is the incident's own
row: `access_provisioning_tasks.requested_at = 2026-10-19 06:17:09.042526`.

**S2** — *the user, 2026-10-19, verbatim*: «como se correige de raiz?»

**S3** — *the user, 2026-10-19, verbatim*: «que opcion me recomiendas que sea mejor y mas mantenible a
futuro sin importar lo que se tenga que hacer»

The answer it produced **rejected the reconciliation step** the second turn had proposed and replaced
it with a **report plus a tested invariant**, for the reasons recorded in the log (`L4`). The frozen
plan is these six concerns:

| # | Concern | Where it lands |
| --- | --- | --- |
| A | The switch **declares its own consequence**, and an environment file that is missing template keys **says so** — a report, never a mutation and never a failure | `T1` |
| B | **dev's default stops lying**: the worker is on in dev, and off stays an explicit operator decision | `T2` |
| C | **A key that goes nowhere, and a default that disagrees with itself, become test failures** | `T3` |
| D | The worker's **off state is observable at runtime** — the API says it and the screen shows it | `T4`, `T5` |
| E | The provisioning flow gets **its own end-to-end verdict script**, the peer of `verify-invitation-flow.sh` | `T6` |
| F | **The record and the conventions carry the lesson**, so the next environment does not repeat it | `T7` |

**S4** — *the user, 2026-10-19, verbatim*: «ok arranca en un nuevo worktree y genera el odd»

This is the authorization for the plan of `S3`, and the named first deliverable is this record on its
own worktree, created **before** the first source line. The reconciliation of the local `.env.dev`
(`T8`) is **not** covered by this sentence: it mutates an operator's environment and fire
provisioning, so it is asked separately.

**S5** — *the measured root cause, 2026-10-19, re-measured on `main` @ `81d0205` (not inferred)*:

**S5.1 — the incident.** An `ACTIVATE` task sat `PENDING` with `attempts = 0`, so the worker had never
claimed it. The email is sent only inside `AccessProvisioningAccountService.activate()`, reached from
the worker's `ACTIVATE` arm; there is no endpoint that triggers it. Evidence: the task row above, the
three `employees` rows all with `keycloak_user_id IS NULL`, and Mailpit's `/api/v1/messages` holding
**exactly one** message in its whole history — the one the `verify-invitation-flow.sh` run of
2026-10-17 left, recipient `w6b-verify-…@lifecontrol.local`.

**S5.2 — the switch was never declared.** `docker/scripts/setup-env.sh:17` creates `$ENV_FILE` **only
when it does not exist** (`if [ ! -f "$ENV_FILE" ]`) and never reconciles it afterwards. Measured:
`.env.template` declares **88** keys and `docker/.env.dev` carries **72**, so **16** are missing, and
they are not stray knobs — they are two whole features plus one binding:

```
SMTP_HOST, SMTP_PORT, SMTP_FROM, SMTP_STARTTLS, SMTP_AUTH          (the invitation channel)
WORKER_ENABLED, WORKER_INTERVAL_SECONDS, WORKER_MAX_ATTEMPTS,
WORKER_BATCH_SIZE, WORKER_BASE_DELAY_SECONDS,
WORKER_MAX_DELAY_SECONDS, WORKER_STALENESS_THRESHOLD_SECONDS        (the provisioning worker)
WEB_APP_URL, MAILPIT_PORT, MAILPIT_IMAGE, REDIS_IMAGE
```

A missing key is not an error anywhere, because compose supplies a default: on `main`, **80 of the 88**
template keys already have a `${KEY:-…}` default in a `docker-compose*.yml`. `WORKER_ENABLED` is
therefore `false` through `docker-compose.yml:364` (`WORKER_ENABLED=${WORKER_ENABLED:-false}`), which
agrees with `life-control-api/src/main/resources/application.properties:141`
(`app.provisioning.worker.enabled=${WORKER_ENABLED:false}`). Both defaults are correct and consistent;
**the defect is that neither is visible to the operator**, and that a dev stack whose data is disposable
and whose mail lands in Mailpit is the one environment where leaving it off has no upside.

**S5.3 — the off state is invisible.** Measured, each one separately: `validate-env.sh` mentions
`WORKER` nowhere; `deploy.sh dev health` answers `UP`; and
`ProvisioningWorkerSchedulerConfig` is gated by
`@ConditionalOnProperty(prefix = "app.provisioning.worker", name = "enabled", havingValue = "true")`,
so with the switch off **the class is not loaded at all** and a warning cannot live inside it. The
Access screen renders `Pendiente` and nothing distinguishes "will advance" from "never will".

**S5.4 — the precedent the repo already has, twice.** The same repository already solved this shape:
`docker/scripts/tests/image-pins.test.sh` pins the agreement between the template and the compose
defaults rather than trusting it, and `docker/scripts/keycloak-setup.sh:214` reads the same kind of
silent default (`SMTP_HOST="$(get_env SMTP_HOST mailpit)"`) and then **fails closed outside dev with a
named error** (`:222-225`). The invitation channel got both halves; the worker got neither. This
feature is therefore **consistency with the repository's own conventions**, not a new mechanism.

**S5.5 — the correction, and what it removes from the plan.** The second turn asserted that
`CIRCUIT_BREAKER_WINDOW_SIZE` and `GF_AUTH_ANONYMOUS_ORG_ROLE` are dead template keys that nothing
reads. **That assertion was wrong and is withdrawn.** Measured: `CIRCUIT_BREAKER_WINDOW_SIZE` is
consumed by the gateway (`api-gateway/src/main/resources/application.properties:60`,
`…slidingWindowSize=${CIRCUIT_BREAKER_WINDOW_SIZE:10}`) and documented in `api-gateway/README.md:219-227`;
`GF_AUTH_ANONYMOUS_ORG_ROLE` is consumed by `k8s/manifests/infra/grafana.yml:26,29,62` and its
`infrastructure/` twin. Neither is deleted by this feature, and `T3`'s invariant is worded as a
**declared consumer per key** rather than as a deletion, precisely because "no compose reference"
turned out not to mean "unused".

**S6** — *the implementation constraints, each of them measured*:

| # | Constraint | Why it is not negotiable |
| --- | --- | --- |
| C1 | The new `validate-env.sh` output is a **warning**. It must not raise `ERRORS`, and must not fail the script | `deploy.sh:173-179` runs `validate-env.sh prod` as the **production start gate** and exits 1 when it fails. An error here would stop prod starts on a condition that prod is not expected to satisfy — the whole point of a default is that not declaring it is legal |
| C2 | The report is **read-only**: it names the missing keys and the default each falls back to; it never writes to `$ENV_FILE` | `L4`'s rejection of the reconciliation step: writing to a gitignored file on every run changes the repo's behaviour with no commit to audit, and destroys the distinction between an operator's declared value and a template default |
| C3 | The dev default lands in `docker-compose.override.yml` (dev-only), **never** in the base compose or in the template | Measured: that file's own header states it is loaded for dev only (`docker-compose.override.yml:4`), it is where `SMTP_HOST=mailpit`-style dev defaults already live, and staging/prod must keep `false` because the worker mutates another system |
| C4 | `T4`'s API change is **additive** (one new field on the overview) and the startup warning lives in a component **outside** the `@ConditionalOnProperty` | `S5.3`: the gated class is not loaded when disabled. An additive field keeps the change off item 3 |
| C5 | `T3`'s invariant **asserts consumers, it does not delete keys** | `S5.5` |
| C6 | The end-to-end script of `T6` creates its own disposable employee and cleans up after itself, like its peer | `verify-invitation-flow.sh` is the precedent, and it must never depend on an operator's real data |

## Tasks

| ID | Spec | Task | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1, S3-A, S5.2, S5.3, S6-C1, S6-C2 | The switch declares its own consequence and a stale env file says so: `.env.template`'s worker block names what `WORKER_ENABLED=false` means, and `validate-env.sh` **reports** (warning only, never `ERRORS`) every template key the env file lacks, naming the compose default each one falls back to. Covered by `T3`'s assertions | delegated | `fa7e2ed`, `8a799a9` |
| T2 | S3-B, S5.2, S6-C3 | `docker-compose.override.yml`: `WORKER_ENABLED=${WORKER_ENABLED:-true}` — the worker is on in dev by default and off stays explicit everywhere else | delegated | `fa7e2ed`, `8a799a9` |
| T3 | S3-C, S5.4, S5.5, S6-C5 | New `docker/scripts/tests/env-defaults.test.sh`: every `.env.template` key has a compose default **or** an allowlist entry naming its reader (`SMTP_*` → `keycloak-setup.sh`'s `get_env`; `ENVIRONMENT` → `validate-env.sh`'s `REQUIRED_VARS`; `CIRCUIT_BREAKER_*` → the gateway's Spring properties; `GF_AUTH_ANONYMOUS_ORG_ROLE` → the k8s manifests), and no compose interpolation names a key the template does not declare | delegated | `fa7e2ed`, `8a799a9` |
| T4 | S3-D, S5.3, S6-C4 | `workerEnabled` on `AccessProvisioningOverview` plus a startup `WARN` from a component outside the `@ConditionalOnProperty`, so a disabled worker announces itself at boot | inline | `2022492`, `6119d4c` |
| T5 | S3-D, S5.3 | The Access section shows the disabled worker: when `workerEnabled` is false, the open request says it will not be applied on its own | inline | `2022492` |
| T6 | S3-E | New `docker/scripts/verify-provisioning-flow.sh`, the peer of `verify-invitation-flow.sh`: disposable employee + contract → the task reaches `APPLIED` → the mail is in Mailpit with its link → the Keycloak account carries its `requiredActions`, the template's roles and the five claims; cleans up on exit and exits non-zero on any failure | inline | `—` |
| T7 | S3-F, S5.2 | Documentation: the `AGENTS.md` line that documents the no-rewrite behaviour **names its consequence** and points at the report; `odd/tasks/employee-access-provisioning.md` gains this defect as its own gap with its resolution | inline | `—` |
| T8 | S4, S5.1 | Local evidence, no commit: reconcile `.env.dev` explicitly (never `S6`-C2 by script), then the end-to-end run — employee + contract, the task applying, the mail in Mailpit, the account in Keycloak with its roles and claims | inline | `L8`–`L9` |
| T9 | S3, risk item 5 | Independent verification of the three candidates (the named high-risk item) and the native review the returned plan asks for | inline | `L10`–`L11` |

Slice plan, for the review budget: **PR1** = `T1`+`T2`+`T3` (`fix(docker)`); **PR2** = `T4`+`T5`
(`feat(provisioning)`); **PR3** = `T6`+`T7` (`chore`). Each carries exactly one `type:*` label, and
`T8`/`T9` are the evidence and the gate of all three.

**The first slice landed over the ~400-line budget and takes a declared size exception** (`L9`): `PR1`
is **two commits** — `fa7e2ed` and `8a799a9` — totalling **873 insertions and 3 deletions**, of which
**696 lines are its three test suites**. The boundary is real and it is not a convenience: the suites
**are** the artifacts that pin the code they ship with, and splitting them from it would separate the
assertions from what they assert — the same argument `employee-registry`'s `D9` used for `W1b`, and
the precedent for declaring it rather than hiding it is that record's own size exception. The second
commit is `L10`'s: one message correction and the two suites an independent verification produced.
**The `PR1` boundary is closed at `8a799a9`**; the next slice starts after it. **The `PR2` boundary is
closed at `6119d4c`** — `2022492` plus the coverage test its own review produced — **272 insertions and
5 deletions across 11 files**, of which 160 lines are tests. It is the smallest of the three because the
change is additive by construction (`S6-C4`): one appended field, one unconditional listener and one
notice. The next boundary is recorded before the corresponding commit.

Stated so it is not lost: this feature does **not** make `verify-invitation-flow.sh` part of CI, does
not add a CI workflow, and does not touch the four existent workflows' `paths:` filters — a records-and
-scripts-only change runs no component workflow at all, which is why `T9` is a manual gate.

## Log

**L1** *(the user, verbatim)*: «como puedo validar si cree un empleado y asigne un contrato si se mando
el correo de activacion Historial Activación Pendiente 2026-10-19T06:17:09.042526». Answered read-only
with `S5.1`–`S5.3`.

**L2** *(the user, verbatim)*: «como se correige de raiz?». Answered with the chain of five links
(symptom → switch off → key absent → env file never reconciled → nobody told), and the honest note that
the no-rewrite behaviour is a **correct** decision whose counterpart — telling the operator — is missing.

**L3** *(the user, verbatim)*: «que opcion me recomiendas que sea mejor y mas mantenible a futuro sin
importar lo que se tenga que hacer». Answered with the recommendation and the **rejection** of the
second turn's reconciliation step.

**L4** *(decision, orchestrator, 2026-10-19 — the rejection of the reconciliation step, kept because it
is the reasoning that produced `S6`-C2)*: a mutating reconciliation inside `setup-env.sh` is the least
maintainable of the options, for four reasons: it changes the repository's behaviour on every run of a
script in all three environments while no commit touches the file it mutates; it destroys the
distinction between an operator's declared value and a template default, irreversibly and without a
diff against the template; it forces an unanswerable decision about a key that is present but empty;
and it is unnecessary, because `S5.2` shows the missing key is already benign — the defect is the
default's invisibility, not its application. The replacement is a **report** (`T1`) plus a **tested
invariant** (`T3`), which is `S5.4`'s own pattern applied to the third case.

**L5** *(the user, verbatim)*: «ok arranca en un nuevo worktree y genera el odd». The worktree was
created with the repository's convention (`herdr worktree create --cwd … --branch
fix/env-defaults-hardening --base main --path ~/workspace/LifeControl-worktrees/fix-env-defaults-hardening
--no-focus`), and the anchor was verified clean on `main` before it. Topology verified: the anchor holds
`.git` as a **directory**, the worktree as a **text file**, both at `81d0205`, and this record is the
first write of the branch.

**L6** *(correction, orchestrator, 2026-10-19 — measured, and it removes a task)*: `S5.5`. The second
turn's claim that `CIRCUIT_BREAKER_WINDOW_SIZE` and `GF_AUTH_ANONYMOUS_ORG_ROLE` are dead keys was
**false**, and the plan's "delete the two dead keys" task is **withdrawn, not deferred**. The claim came
from scanning only `docker/**` for a compose reference; scanning the repository found
`api-gateway/src/main/resources/application.properties:60` and `api-gateway/README.md:219-227` for the
first, and `k8s/manifests/infra/grafana.yml:26,29,62` for the second. Consequence for `T3`: its
invariant is written as an allowlist of **named readers**, because "no compose reference" does not mean
"unused" — which is exactly what this correction measures.

**L7** *(constraint, measured while writing the record)*: `validate-env.sh` is not advice. `deploy.sh`
runs it in prod as the start gate and exits 1 on failure (`deploy.sh:173-179`), so `T1`'s report is a
warning and must not increment `ERRORS`. This is the one way this feature could break a production
deploy, and it is the reason risk item 5 is named in the header.

**L8** *(the first slice, landed 2026-10-19 — `T1`+`T2`+`T3` as `fa7e2ed`)*. Implemented by a bounded
`gentle-ai-worker` delegation and verified by the parent rather than accepted on report: the diffs of
all six paths of the commit were read, the whole suite was re-run (`env-defaults` 244/0 plus the six
pre-existing suites at 6/18/42/68/5/8, all rc=0 — **391 assertions, zero failures**), and `C1` was
re-proved by hand against a hermetic env file missing keys (`validate-env.sh dev` exits **0** and
prints the report). `shellcheck` is **not installed** on this machine, so its `--severity=warning` pass
could not run; `bash -n` on the three shell files passes.

**L9** *(the slice boundary, and the parent's own finding — measured, 2026-10-19)*. The delegation's
own handoff declared a residual gap and **the parent measured it as real**, which is why the first
slice carries two rounds: the first satisfied `T2` only for a dev env file that *lacks* the key, and
**a fresh `setup-env.sh dev` still produced a worker-off stack**, because the template declares
`WORKER_ENABLED=false` at column 0 and an explicitly declared value beats the override's `:-true`. The
four-case probe, run by the parent in a scratch copy with `docker compose config --format json`:

| case | before | after |
| --- | --- | --- |
| dev, env file without the key (the incident's shape) | `true` | `true` |
| dev, env file created fresh from the template | **`false`** | **`true`** |
| dev, env file declaring `false` | `false` | `false` (a declaration still wins) |
| staging / prod | `false` | `false` |

The closure is a dev-only `sed` in `setup-env.sh`'s **creation branch**, the idiom that script already
uses for `VOLUMES_ROOT` and prod's postgres port, and it is deliberately **one key with one reason** —
a per-environment rewrite table would be the silent-rewrite defect `S6`-C2 rejects, re-entering through
the other door. The two dev expressions of the switch (the fresh declaration and the override default)
are now pinned against each other by the suite, so changing one alone fails a test.

**L10** *(the independent verification of the first candidate, and what it changed — 2026-10-19)*. A
`gentle-ai-verify` pass over the **frozen candidate** `fa7e2ed` (not the working tree) confirmed **all
twelve** claimed items — `S1`, `S3-A/B/C`, `S5.2`, `S5.3`, `S5.4`, `S5.5`, `S6-C1/C2/C3/C5` — with 0
deletions in `git diff --numstat 81d0205..fa7e2ed`, a mutation on a scratch copy proving the suite
fails when the invariant breaks (`243 passed, 2 failed`, rc=1) and passes again when restored, and an
exit-code parity run of `validate-env.sh prod` between base and candidate that differed in no
non-report line. It found **no blocker** and two advisories, both of which were acted on rather than
filed:

1. **The report asserted a consumer it never measured** — a real defect of the same class this feature
   exists to remove, fixed in `8a799a9`. Its own `env-defaults` assertion pinned the old wording and
   failed, which is that suite doing exactly what it was built for.
2. `L8` said "five modified files"; the commit touches six paths. Corrected in that entry.

It also handed over two regression suites, and **their delivery is the reason the parent ran them
instead of committing them on report**: the first arrived with `DOCKER_DIR="$(cd "$SCRIPT_DIR/.." &&
 pwd)"`, which resolves to `docker/scripts` in a file that lives in `docker/scripts/tests/` and made
all seven of its assertions fail with `0 passed, 7 failed`. Fixed to `../..` and re-run to `7 passed`,
it now drives the **real `docker compose config` merge** (`true, true, false, false` across the four
cases) rather than the YAML text the invariant suite reads. The second suite is hermetic and covers the
adversarial inputs the shipped suite did not: absent env file, absent template, empty-valued key, CRLF,
non-identifier names, prefix keys, `COMPOSE_FILES` unset, and that the env file is byte-identical
afterwards. Both are in `8a799a9`, and the parent re-ran the whole set: **409 assertions across nine
suites, zero failures**.

**L11** *(the second slice, landed 2026-10-19 — `T4`+`T5` as `2022492`+`6119d4c`)*. Verified by the
parent, not accepted on report: the focused Java classes and the 48 Angular tests of the touched files
were run **in this worktree** (which needed a `node_modules` symlink to the anchor — a gitignored local
convenience, not a dependency change), then the **full** suites: `./gradlew spotlessCheck test` over the
provisioning packages (`BUILD SUCCESSFUL`) and the whole Angular suite (**183 files, 3655 tests, 0
failures**).

Three things this slice produced that the plan did not predict, each recorded rather than smoothed over:

1. **A premise in the parent's own delegation was false and the delegation measured it.** The handoff
   claimed `ProvisioningWorkerProperties` is not a bean while the worker is disabled, and that
   `ProvisioningWorkerSchedulerConfig` is the only class registering it. Measured: the unconditioned
   `ProvisioningWorkerConfig:31-33` also declares `@EnableConfigurationProperties`, so the properties
   bean exists either way and only the **scheduler** is gated. The constraint held for the right reason
   — a warning inside a class that is not loaded when disabled cannot run — and the logger is outside
   the condition, but the reasoning in the handoff was wrong and is corrected here. `S6-C4` and `S5.3`
   as written in this record are unaffected: neither ever asserted the bean was absent.
2. **The slice's first pass declared a coverage gap and the parent closed it instead of adopting it.**
   `AccessProvisioningControllerTest` mocks the query service, so its `jsonPath("$.workerEnabled")`
   proves only that the field serializes, and the test that owns the property-to-field mapping was not
   in the delegation's edit surfaces. `6119d4c` pins it in both directions against the mocked
   properties, so reporting the switch wrongly now fails a test rather than a screen.
3. **The Angular notice is not gated on `canWrite()`** and **an absent field renders nothing.** The
   first is a judgement the plan left open: a read-only caller is exactly the person who needs to know
   that a request will not advance. The second is `S6-C4`'s fail-safe applied to the UI — a backend
   older than the additive field answers no `workerEnabled`, and the screen must not turn its own
   ignorance into the claim that the worker is off.

**L15** *(delivery, 2026-10-19)*. Published as issue **#457** (`bug` + `status:approved`) and three chained
pull requests: **#458** (`fix/env-defaults-worker-default` → `main`, `type:bug`), **#459**
(`feat/provisioning-worker-visibility` → slice 1, `type:feature`) and **#460**
(`chore/provisioning-flow-verdict` → slice 2, `type:chore`). Two gate rules shaped the delivery and both were
found by running the repository's own checks rather than by reading them: the issue gate requires a
**closing** keyword on the slice that targets the default branch
(`.github/scripts/check-issue-reference.sh:225`), so the closing reference lives on **#458** and the later
slices only `Refs`; and `odd-status-headers.test.sh` — a suite that landed on `main` after this branch was
cut — rejects a `Status` paragraph that uses declining vocabulary (`implemented`, `makes no claim about …
push`) without delivery evidence, which is why the header now names the pull requests instead.
