# ODD feature: access-provisioning-worker-repairs

**Repository**: LifeControl — `life-control-api/**` only (the provisioning services, the Keycloak
adapter and their tests). One port method's classification, one invitation rule, one read. No schema,
no migration, no gateway, no Angular change, no existing contract removed.
**Status**: **merged** — the five chained pull requests **#465** (merge commit `76d9d7a`), **#466**
(`abd9343`), **#467** (`dbb8f42`), **#468** (`e232b17`) and **#469** (`7dbd3aa`, this record),
2026-10-19, so `main` carries all five commits and each slice's post-merge `push` run is green. The
fourth commit fixes three defects in the verdict script #460 shipped, which had never been run, and
that script's live run passes (`L8`). **No work left in this unit.**
**Created**: 2026-10-19 · **Branch**: `fix/access-provisioning-worker-repairs` · **Base**: `main` @
`7ebb21b` · **Worktree**: `~/workspace/LifeControl-worktrees/fix-access-provisioning-worker-repairs`
· **Risk**: **item 1 and item 3** — the unit decides whether a person's account gets provisioned at
all (roles and the five `company_*` claims are authorization inputs) and it changes the observable
behaviour of an existing flow (how many invitations an `ACTIVATE` sends).

## Specs

Quoted verbatim from issue #464's *Acceptance criteria*; the issue's *Expected outcome* is the
outcome these specs measure.

**S1** — "An `ACTIVATE` task for an employee with a current contract reaches `APPLIED`, and
`access_provisioning_applied_roles` records the roles the run touched — asserted in an integration
test that fails on the current tree with the `LazyInitializationException` above."

**S2** — "The linked Keycloak account ends with the position template's enabled roles (intersected
with `GrantablePositionRoles`) and the five `company_*` attributes equal to
`AccessProvisioningMembershipService.deriveMembership`, compared in both directions."

**S3** — "A failure **after** the account half does not send a second invitation: an `ACTIVATE` that
fails and is retried leaves **exactly one** actions email for that employee in Mailpit."

**S4** — "An employee whose `employees.email` matches exactly one existing Keycloak account is
**linked**: `employees.keycloak_user_id` becomes that account's id and the task reaches `APPLIED`.
Zero matches still creates, and zero-or-more-than-one still refuses via `AccountLinkRefusedException`
— record T5/T27 is preserved, not weakened."

**S5** — "`Terminated` still revokes (disable, remove the template's roles, remove the five
attributes) and a rehire still re-enables — the two directions `T99`/`T101` fixed must keep working."

**S6** — "The end-to-end verdict script that shipped with #460 passes:
`./docker/scripts/verify-provisioning-flow.sh dev` exits 0."

**S7** — "`cd life-control-api && ./gradlew spotlessCheck test --tests
'com.lifecontrol.api.provisioning.*' --tests 'com.lifecontrol.api.config.provisioning.*'` passes."

## Tasks

| ID | Task | Specs | Route | Commit |
|----|------|-------|-------|--------|
| T1 | **G47** — `KeycloakIdentityProvider.createUser` classifies the response's `409` as `IdentityProviderConflictException` instead of a retryable connection failure, so `createOrLink`'s catch actually links the single exact match and still refuses zero/many. RED first: a test that failed on the pre-fix tree with `"Unexpected response creating user: 409"`. | S4 | inline | `dac6665` |
| T2 | **G46** — `AccessProvisioningAccountService.activate` sends the invitation only when the account half did not answer `ALREADY_LINKED`, so a retried `ACTIVATE` sends exactly one mail and the explicit resend endpoint keeps its own job. RED first: a test that failed on the pre-fix tree by counting two sends for one retry. | S3 | inline | `bef0882` |
| T3 | **G45** — the worker's employee read initializes `company` and `status`, the two lazy associations the gate, the role convergence and the membership derivation read, so the pass never touches a detached proxy. RED first: a PostgreSQL integration test that failed on the pre-fix tree with the recorded `LazyInitializationException`. | S1, S2 | inline | `0c820ae` |
| T4 | Gates over the final bytes (`spotlessCheck`, the provisioning suites, the full 3539-test suite), the record's close, and the **live run of the verdict script** (S6) — which forced a fifth commit, because the script #460 shipped could not run at all. | S5, S6, S7 | inline | `bb200f2` |

## Log

**L1 — the user's original request, verbatim**: "si continua con 464"

**L2 — the request before it, verbatim**: "que esta pendiente?"

**L3 — measured state when this unit started**: `main` @ `0cb916c` behind `origin/main` @
`7ebb21b` by 22 commits; the anchor fast-forwarded to `7ebb21b` before the worktree was cut. No open
PRs, one open issue (#464, `bug` + `status:approved`).

**L4 — the three defects, as the code shows them** (each re-read against the merged tree, not taken
from the issue's prose):

- **G47**: `KeycloakIdentityProvider.createUser` uses the `Response`-returning
  `users().create(user)`. A `409` therefore never becomes a `WebApplicationException`, so the
  `catch`'s `toDomainFailure` never runs and the method throws
  `IdentityProviderConnectionException("Unexpected response creating user: 409")` — retryable, not a
  conflict. `AccessProvisioningAccountService.createOrLink` catches
  `IdentityProviderConflictException`, so it never runs `resolveExistingAccount` and T5/T27's link
  rule is unreachable on this path.
- **G46**: `AccessProvisioningAccountService.activate` calls `ensureAccount` and then
  `sendActionsEmail` unconditionally. `ensureAccount` answers `ALREADY_LINKED` on every retry, so
  the account half is idempotent by T4 while the mail is not.
- **G45**: `AccessProvisioningWorker.process` loads the employee with the inherited unlocked
  `findById` and owns no transaction (T44/T57 refuse an outer one), so the instance is detached.
  Every capability assumes an initialized association: `AccessProvisioningRoleService.requiredRoles`
  → `EmployeeStatuses.isTerminated` reads `employee.getStatus()` and `requireOwnedPosition` reads
  `employee.getCompany()`; `AccessProvisioningMembershipService.derivedScope` reads the same two;
  `AccessProvisioningGateService.reenterGateIfReviewedSetChanged` → `deriveReviewedDiff` →
  `requiredRoles` runs before anything is applied. A new transaction on the capability does not
  reattach a detached instance, which is why the annotation on those methods does not save the pass.

**L5 — decisions taken while planning** (each with the reason, none invented):

- **G45 is fixed at the read, not with a transaction.** An `@EntityGraph`-style finder that
  initializes `company` and `status` is the minimal change that respects T44/T57: the pass keeps
  owning no transaction and no lock, and the capability methods keep their own boundaries. Making
  the worker or the handler `@Transactional` would merge three bounded writes into one long one,
  which T44 refuses for the record's own reasons.
- **G46 is fixed at the outcome, not with new state.** `activate` sends only when the account half
  answered `CREATED` or `LINKED` (a link established in this run has never been invited). Adding a
  sent-marker column would be a schema change for a fact the account row already carries, and the
  explicit resend endpoint (T69) already covers "send it again on request".
- **G47 is fixed in the adapter, not in `createOrLink`.** The port's own contract is "a 409 is a
  conflict"; widening the service's `catch` would leave the adapter still calling a conflict
  retryable and would hide the same defect on every future caller.

**L6 — the RED/GREEN evidence of each fix, as observed**:

- `T1` RED: `KeycloakIdentityProviderTest > createUser > should map a returned 409 response to
  IdentityProviderConflictException` failed with `but was: IdentityProviderConnectionException:
  Unexpected response creating user: 409`. The suite's pre-existing `shouldMap409ToConflictException`
  mocked `create` as *throwing*, a shape the response-returning overload never produces, which is why
  the gap survived a green suite. GREEN after the branch: the same class, 41 tests, 0 failures.
- `T2` RED: `sendsOneInvitationAcrossARetry` and `alreadyLinkedDoesNotInvite` failed while the other
  62 tests of the two classes passed. GREEN after the change.
- `T3` RED (PostgreSQL, Testcontainers): all three tests failed, the decisive one with
  `the pass must apply, not fail on a detached association: Could not initialize proxy
  [com.lifecontrol.api.status.model.Status#810c5ce6-…] - no session` and status `PENDING`, i.e. the
  recorded G45 message reproduced by a test for the first time. GREEN after the finder: 3 tests, 0
  failures.
- The first full-suite run after `T3` failed **12** tests, all of them Mockito suites stubbing the
  inherited `findById` (`AccessProvisioningWorkerTest` ×11, `AccessProvisioningWorkerDeactivateCompositionTest`
  ×1). Repointed to the new finder; the second of those failures was not a stale stub but the second
  suite pinning G46's old behaviour, corrected there and reported as such.

**L7 — the gates over the final bytes**: `spotlessCheck` clean; `./gradlew test` **3539 tests, 0
failures, 0 errors, 0 skipped**; shellcheck (`koalaman/shellcheck:stable --severity=warning`) clean on
the repaired script.

**L8 — the live run (S6), measured**, after rebuilding only the dev API from this worktree's jar
(`bootJar` → image `lifecontrol-dev-lifecontrol-api` → `up -d --no-deps`, project `lifecontrol-dev`,
healthy in 50s): `./docker/scripts/verify-provisioning-flow.sh dev` printed **"Access-provisioning flow
verified end to end for dev"** — employee + contract write (the real producer enqueued the ACTIVATE),
the worker claimed it, **the task reached `APPLIED`**, the invitation arrived in Mailpit with its
Keycloak action-token link, the account was linked with `requiredActions` exactly
`VERIFY_EMAIL + UPDATE_PASSWORD`, and **all five `company_*` attributes agreed with the derivation**
(company, country, region, zone and store all held). The dev position template declares no roles, so
that run proves "grants none"; the roles half of S2 is the PostgreSQL integration test's job.

**L9 — the three defects the live run found in the verdict script itself** (each is fixed in
`bb200f2`, and none is a defect of the product): `kc_admin` was called seven lines before its
definition; five `kc_admin … | json_*` sites fed the helpers on stdin while they read `$1`; and the
disposable operator was written without `firstName`/`lastName`, which this realm's user profile
requires, so the password grant answered `invalid_grant` / "Account is not fully set up". The script had
never been executed end to end before this unit.

**L10 — state the next reader needs**: the dev stack currently runs the **branch** build of
`lifecontrol-api` (rebuilt from this worktree to make S6 measurable). `docker/.env.dev` and
`docker/secrets/` were copied into this worktree because both are gitignored and the compose run needs
them; neither is tracked. `main` in the anchor was fast-forwarded to `origin/main` @ `7ebb21b` before
the worktree was cut.

**L11 — delivery, 2026-10-19**. Published as five chained pull requests off `main` @ `7ebb21b`, each
merged with a merge commit: **#465** (`fix/provisioning-409-classification` → `main`, `type:bug`,
`76d9d7a`, 19:31Z) carrying `9da8781` + `dac6665`; **#466** (`fix/provisioning-invitation-once`,
`type:bug`, `abd9343`, 19:42Z) carrying `bef0882`; **#467** (`fix/provisioning-worker-employee-read`,
`type:bug`, `dbb8f42`, 19:48Z) carrying `0c820ae`; **#468** (`fix/verify-provisioning-script`,
`type:bug`, `e232b17`, 19:50Z) carrying `bb200f2`; and **#469**
(`docs/access-provisioning-worker-repairs`, `type:docs`, `7dbd3aa`, 20:31Z) carrying `ab34bad`, this
record. Every pull request carried `Closes #464`, and the issue was reopened before each subsequent
merge because the issue gate requires an **open**, approved issue at the run's `head_sha`. Post-merge
`push` runs: `API CI` green on `76d9d7a`, `abd9343` and `dbb8f42`, `Docker Build Integrity` green on
`e232b17`. Issue #464 is closed.

**L12 — header repair, 2026-10-19**. The `**Status**:` paragraph above said *"delivered on the branch,
not merged"* and *"What remains is the delivery"* while all five pull requests were already merged: it
was written before `#469` was opened, and `#469` is the pull request that merged it, so the claim was
falsified by a merge that never touched the file that made it. Repaired in issue **#470** to durable
state plus evidence — merge commits and post-merge runs — as
`.agents/skills/project-conventions/references/feature-records.md` requires, and recorded here so the
repair is not repeated next cycle. The superseded sentence is kept in this log rather than erased, so
the drift stays visible.
