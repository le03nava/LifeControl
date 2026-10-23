# access-request-revoked-rule

**Status**: implemented — the three guards of the explicit access-request surface (`apply`, `resend`,
`deactivate`) read the single revocation rule `EmployeeStatuses.isRevoked`, the two 409 refusals are
named after that rule instead of after `Terminated`, and their tests are the branch's content.
Nothing is deferred.
**Created**: 2026-10-22 · **Risk**: high — item 2 (these guards decide who can be provisioned, and the
`deactivate` half widens a refusal into an admission)
**Issue**: #535 · **Branch**: `fix/access-request-revoked-rule`, chained on
`fix/employee-disable-revokes-access` @ `88acf75` (issue #533 / PR #534), which is where
`EmployeeStatuses.isRevoked` lands.

## Specs

**S1 — `apply` refuses a revoked employee.** `AccessProvisioningRequestService.apply` refuses an
employee for whom `EmployeeStatuses.isRevoked` is true — `Terminated` **or** the soft delete's
`enabled = false` — before any task row exists, by throwing the named 409 of S4. The refusal precedes
the gate, so nothing is recorded against somebody who has no access.

**S2 — `resend` refuses the same employee.** `AccessProvisioningRequestService.resend` refuses the
same predicate with the same named 409, before delegating to the account service: a fresh activation
link is a way back in, and the two requests must not disagree about who is out.

**S3 — `deactivate` requires a revoked employee.** `AccessProvisioningRequestService.deactivate`
refuses an employee for whom `isRevoked` is **false**, by throwing the named 409 of S4, and requests
the `DEACTIVATE` task for one for whom it is true. This is the inverse guard it already had, with the
rule corrected: T101's premise is "there is no access to remove", and under G41 a soft-deleted
employee satisfies it — that employee is precisely who a revocation is for, and the manual surface is
the fallback when the approval gate withheld the automatic enqueue.

**S4 — the refusals are named after the rule they enforce.** `TerminatedEmployeeRefusedException`
becomes `RevokedEmployeeRefusedException` and `TerminatedEmployeeRequiredException` becomes
`RevokedEmployeeRequiredException`, each keeping its shape: a thin alias of `ConflictException`, so the
existing handler answers **409** and the message reaches the wire intact, with no new handler. Their
messages name the rule — a revoked employee is `Terminated` or `enabled = false` — instead of
`Terminated` alone. Chosen at L2 over keeping the names and generalising only the messages, and over
a sibling exception per fact: one rule, one 409 per direction.

**S5 — the three regression directions keep their behavior.** A `Terminated` employee still gets its
named 409 on `apply` and on `resend`; an employee with access still gets its named 409 on
`deactivate`; an active employee still gets its `ACTIVATE` task, its resend and its `DEACTIVATE`
task. The renamed messages still contain `Terminated`, so the assertions that name it stay
meaningful.

**S6 — documentation states the rule once.** The javadoc of the three methods and of the two exception
classes reads `isRevoked` and says why; `AccessProvisioningController`'s `409` documentation on
`/deactivate` names the two facts; `life-control-api/AGENTS.md` keeps its employee exception and gains
no second copy of the rule.

**S7 — the record and the guard suite travel with the slice.** `odd/tasks/access-request-revoked-rule.md`
is in the pull request with its opening verdict token, and
`./.github/scripts/tests/odd-status-headers.test.sh` is green on the branch.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | — | Worktree `fix/access-request-revoked-rule` off the parent tip `88acf75` (`./.github/scripts/new-unit.sh`), this record, the Engram mirror and the `todo` projection | `764aa0f docs(odd): record access-request-revoked-rule` |
| T2 | S1, S2, S3, S5 | RED first: three `AccessProvisioningRequestServiceTest` cases for a soft-deleted employee (`enabled = false`, status `Active`) — refused by `apply`, refused by `resend`, admitted by `deactivate` | `7ed5f23 test(provisioning): pin the revoked rule in the explicit request surface` |
| T3 | S1, S2, S3 | GREEN: the three guards read `EmployeeStatuses.isRevoked` | `e5a3384 fix(provisioning): make the explicit request surface read one revocation rule` |
| T4 | S4 | The two exception classes renamed and their messages rewritten to the rule, with every call site and test moved | `79b1b28 refactor(provisioning): name the access refusals after the rule they enforce` (ordered before the RED so the failing test states a behavior, not a missing class) |
| T5 | S6 | Javadoc of the three methods and the two exceptions, the controller's `409` documentation, `life-control-api/AGENTS.md` | `docs(provisioning): state the revocation rule the guards enforce` |
| T6 | S7 | Gates: `spotlessCheck`/`spotbugsMain`, the focused suite, `./gradlew test`, the offline script suites | (no commit; evidence below) |
| T7 | — | Independent read-only verification of S1–S7 (Task Size item 2) | (no commit; evidence below) |

## Log

**L1 (user, verbatim)**: `dale con apply resend`

**L2 (decision, verbatim option)**: *"Los tres guards: apply, resend y deactivate"* — the symmetric
`deactivate` hole enters this slice instead of staying a documented asymmetry.

**L3 (decision, verbatim option)**: *"Renombrar a Revoked\* + mensajes de la regla"* — the two exception
classes are renamed, the wire text of both 409 bodies changes, and no sibling exception is added.

**L4 (evidence)**: measured on the parent tip `88acf75`, before any edit of this branch —
`AccessProvisioningRequestService.java:97` and `:199` read `EmployeeStatuses.isTerminated(employee)`
(refusals of `apply`/`resend`), `:126` reads `!EmployeeStatuses.isTerminated(employee)` (the
`deactivate` guard), while `EmployeeStatuses.isRevoked` — the rule the derivation, the membership
projection and the account half already read — is the predicate that decides "no access". Parent
record: `odd/tasks/employee-disable-revokes-access.md`, `## Follow-up (found, priced, not in this
slice)`, which priced only the two refusals.

**L5 (scope note)**: the parent record is frozen narrative and is **not** rewritten by this slice. Its
`## Follow-up` section says the two guards refuse only a `Terminated` employee; after this slice that
sentence is superseded, and this record is the evidence of the supersession. The parent's prose also
names the two old exception classes, which this slice renames — the same rule applies: the record that
supersedes names the new ones.

## Why the `deactivate` half is a widening, and why it is still the right call

`deactivate` refusing a soft-deleted employee is the defect, not the guard. The guard exists because a
revocation is only meaningful against a truth of no access: the intent removes the template's roles and
the five `company_*` claims, while the required set is re-derived from the current contract, so for an
employee who still has access the next fact re-grants exactly what was removed. Under G41 the premise
is `isRevoked`, not `isTerminated` — a soft-deleted employee has an empty required set, so the same
`DEACTIVATE` converges instead of oscillating.

What it costs: a manual `DEACTIVATE` can now be requested for an employee the soft delete already
revoked, where before it answered 409. That is intended (it is the fallback when the approval gate
withheld the automatic enqueue, or when the task failed), the outcome converges to the same projection,
and the re-enable path of the parent slice enqueues a `RECONCILE` that converges the other way.

## Wire-text note

The two 409 messages change. Measured before writing: no frontend or gateway code parses either
message — the only `.ts` references to a `Terminated` refusal are the contract dialog's own
locally-authored copy and its specs, which are `ContractService`'s message and not this surface's — so
the change is a human-facing copy correction, not a contract break. Both messages still contain
`Terminated`, so a client that greps for it keeps working.

## Doc truth found while editing the same declarations

The `@ApiResponse`/`@Operation` documentation of `/apply` and `/resend` still described the
"company without a usable email domain" refusal, which records T102/T104 deleted: measured on this
branch, `grep -rn 'emailDomain' src/main/java/com/lifecontrol/api/provisioning/` returns nothing. The
clauses are corrected in the same declarations this slice rewrites — leaving a false refusal documented
while editing the sentence that lists the refusals would ship a fresh lie.

## Review budget

The authored diff of this slice lands slightly over the advisory ~400 lines, and the reason is named
rather than hidden: the rename of two exception classes carries their javadoc, and splitting it into
its own pull request would deliver a surface whose names contradict the rule it enforces in the middle
of a chain. The mechanical half is separable at review time by commit — `refactor(provisioning)`, then
the RED test, then the three guards.

## Commands and evidence

| Date | Command | Result |
| --- | --- | --- |
| 2026-10-22 | `./gradlew test --tests '*AccessProvisioningRequestServiceTest' --tests '*AccessProvisioningControllerTest' --no-daemon` (rename only, guards untouched) | `BUILD SUCCESSFUL` in 36s — the rename changes no behavior |
| 2026-10-22 | same, with the three new cases (`7ed5f23`) | **RED**: `29 tests completed, 3 failed` — `apply`, `resend` and `deactivate` disagree with the rule |
| 2026-10-22 | `./gradlew test --tests '*AccessProvisioningRequestServiceTest' --tests '*AccessProvisioningControllerTest' --tests '*AccessProvisioningControllerSecurityTest' --no-daemon` (guards changed, `e5a3384`) | `BUILD SUCCESSFUL` in 23s, `GRADLE_EXIT=0` |
