# ODD feature: access-provisioning-worker-repairs

**Repository**: LifeControl — `life-control-api/**` only (the provisioning services, the Keycloak
adapter and their tests). One port method's classification, one invitation rule, one read. No schema,
no migration, no gateway, no Angular change, no existing contract removed.
**Status**: **planned** — nothing implemented yet. Issue **#464** is the specification: it owns the
three defects (**G45**, **G46**, **G47**) that the first live run of the worker with
`WORKER_ENABLED=true` measured. This record is the unit that closes them.
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
| T1 | **G47** — `KeycloakIdentityProvider.createUser` classifies the response's `409` as `IdentityProviderConflictException` instead of a retryable connection failure, so `createOrLink`'s catch actually links the single exact match and still refuses zero/many. RED first: a test that fails on the current tree with `"Unexpected response creating user: 409"`. | S4 | inline | `fix(provisioning): classify the create-user 409 as a conflict` |
| T2 | **G46** — `AccessProvisioningAccountService.activate` sends the invitation only when the account half did not answer `ALREADY_LINKED`, so a retried `ACTIVATE` sends exactly one mail and the explicit resend endpoint keeps its own job. RED first: a test that fails on the current tree by counting two sends for one retry. | S3 | inline | `fix(provisioning): send the invitation once per activation` |
| T3 | **G45** — the worker's employee read initializes `company` and `status`, the two lazy associations the gate, the role convergence and the membership derivation read, so the pass never touches a detached proxy. RED first: a PostgreSQL integration test that runs one `ACTIVATE` to `APPLIED` and fails on the current tree with the recorded `LazyInitializationException`. | S1, S2 | inline | `fix(provisioning): initialize the employee the worker applies` |
| T4 | Gates over the final bytes (`spotlessCheck`, the provisioning suites, S7's two package filters), a live run of the verdict script (S6), then the record's close: G45–G47 statuses in `odd/tasks/employee-access-provisioning.md` plus this record's delivery row. | S5, S6, S7 | inline | `docs(odd): record the worker repairs and their delivery` |

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
