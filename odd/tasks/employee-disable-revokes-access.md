# ODD feature: employee-disable-revokes-access (G41 — the soft delete becomes a revocation)

**Repository**: LifeControl — `life-control-api/` (derivation, projections, write paths) and
`life-control-app-angular/` (the disable confirmation copy)
**Status**: implemented — `enabled = false` now derives no roles, no `company_*` claims and no enabled
account, the disable enqueues a `DEACTIVATE` and the re-enable reconciles, so "out of the registry with
live access" is no longer reachable
**Branch**: `fix/employee-disable-revokes-access`, off `origin/main` @ `61577d3`, worktree
`~/workspace/LifeControl-worktrees/fix-employee-disable-revokes-access`
**Origin**: `odd/tasks/employee-access-provisioning.md`, row **G41** — closed on 2026-10-15 as the
*explicit* shape (a confirm copy that said it does not revoke), reopened on 2026-10-22 by the user's
decision to make it a real revocation

## Objective

Close G41 at its cause instead of at its wording: a soft-deleted employee must stop holding access, and
the state "disabled, still holding roles, claims and an enabled account" must stop being reachable.

## Specs

**S1 — a soft-deleted employee derives no required roles.** `AccessProvisioningRoleService.requiredRolesFor`
short-circuits to an empty set when the employee is revoked, through the same single rule that already
short-circuits `Terminated`, so the read path (the Access section), the gate's diff and the worker's
convergence all stop seeing required roles. Verbatim from the decision:
«`AccessProvisioningRoleService.requiredRolesFor` corta cuando `employee.enabled == false` (además de
`Terminated`): la derivación, la Access section y la convergencia dejan de ver roles requeridos».

**S2 — the disable enqueues the revocation, on the transition.** `EmployeeService.deleteEmployee` and
`EmployeeService.setEmployeeEnabled(companyId, id, false)` enqueue a `DEACTIVATE` intent, reusing the
existing machinery (account disable + role convergence + the five `company_*` claims). Verbatim:
«`EmployeeService.deleteEmployee` y `setEmployeeEnabled(false)` encolan `DEACTIVATE` **en la transición**
(reusa `onEmploymentTerminated`; la maquinaria de cuenta+roles+claims ya existe)». Re-disabling an
already-disabled employee, which `deleteEmployee` already treats as an idempotent no-op, enqueues nothing.

**S3 — the re-enable reconciles what the disable revoked.** `EmployeeService.setEmployeeEnabled(companyId,
id, true)` enqueues the convergence intent on the transition, so a re-enabled employee gets back exactly
the roles of the contract that covers today. Verbatim: «`setEmployeeEnabled(true)` encola `ACTIVATE`/`RECONCILE`
(`kindFor`) para devolver lo que corresponda».

**S4 — the confirmation copy stops contradicting the behavior.** The disable confirmation in
`employee-list` no longer states that the roles and the account stay active, because they no longer do;
it states that disabling revokes access and names the way back. Verbatim: «El copy del confirm deja de
mentir y se ajusta (hoy dice "no revoca su acceso")».

**S5 — both orderings are closed.** «**Cierra las dos ordenaciones**: disable→contrato nuevo da required
vacío igual». Opening a contract for a disabled employee can no longer re-grant roles, because S1 makes the
required set empty whatever the contract says, and S7 stops the intent from superseding the revocation.

**S6 — no convergence may re-enable a disabled account** (derived from S1/S5):
`AccessProvisioningAccountService.ensureAccountEnabled` treats a revoked employee as revoked and no-ops,
exactly as it already did for `Terminated`. Without this, any `RECONCILE` reaching a disabled employee
would reopen the door S1 just closed.

**S7 — no fact supersedes an open revocation** (derived from S2): the producers refuse to enqueue for a
revoked employee where they already refused for a terminated one — `onContractActivated`,
`onMembershipChanged`, `onAccessRestored` — because `enqueue` supersedes an open task of a different kind,
and a `RECONCILE` superseding the open `DEACTIVATE` would leave the revocation unapplied.

**S8 — an unlinked employee produces no revocation and no re-enable intent** (derived from S2/S3): there is
no account to revoke or restore, and `DEACTIVATE` on an unlinked account throws `AccountNotLinkedException`
by contract, so enqueueing one would be a task that can only ever end `FAILED`. The re-enable path
therefore never uses `ACTIVATE` — a re-enable is not the moment to create an account and send an invitation
nobody asked for, the same rule `onMembershipChanged` already follows (S2 there).

**S9 — the claim projection removes the five `company_*` claims too** (derived from S1/S2, and required by
the revocation's own rule O4: *disable the account, remove the template's roles, remove the claim
attributes*). `AccessProvisioningMembershipService.derivedScope` answers the empty scope for a revoked
employee, so a convergence deletes all five keys instead of writing the scope back after the roles were
removed.

**Non-goals** (decided, not forgotten): no guardrail on `ContractService.createContract` for a disabled
employee (the rejected "guardrail" shape); no change to the manual `POST …/access/deactivate` surface; no
migration and no backfill — employees already disabled become revoked from the next fact that reconciles
them, and nothing is rewritten behind the operator's back.

## Tasks

| Id | Spec | Task | Commit |
|----|------|------|--------|
| T1 | S1, S6, S9 | `EmployeeStatuses.isRevoked` — one rule, three projections — plus the `AGENTS.md` note | `d6b7bf8` |
| T2 | S2, S3, S7, S8 | the two write paths enqueue on the transition; the producers refuse a revoked employee | `70026d6` |
| T3 | S4 | the confirmation copy and its spec, both directions | `b8f0fd7` |
| T4 | S5 | this record and the evidence | this commit |

## Delivery and slice boundary

Three commits on `fix/employee-disable-revokes-access`, ordered by dependency: the derivation rule first
(so the projections cannot disagree), the facts that produce it second, the copy last. **528 authored
changed lines** in total, which is past the ~400-line review budget: the excess is test code that cannot be
separated from the rule it pins (≈330 test lines against ≈175 production lines), so the slice was recorded
rather than split into a chained PR. The backend commits are the **high-risk** ones (item 2,
permissions/guards) and each is its own commit so a native review can take them one at a time. Nothing was
pushed and no PR was opened: that is the human's decision.

## Evidence

| Check | Result |
|---|---|
| RED, T1 — `AccessProvisioningRoleServiceTest`, `AccessProvisioningAccountServiceTest` | 4 failed / 50, one per new rule |
| GREEN, T1 | `BUILD SUCCESSFUL` |
| RED, T2 — `AccessProvisioningProducerTest`, `EmployeeServiceTest` | 5 failed / 80, one per new producer rule |
| GREEN, T2 | `BUILD SUCCESSFUL` |
| RED, S9 — `AccessProvisioningMembershipServiceTest` | 2 failed / 18 |
| GREEN, S9 | `BUILD SUCCESSFUL` |
| RED, T3 — `employee-list.spec.ts` against the **pre-change** component | 2 failed / 50 |
| GREEN, T3 | 1 file / 50 tests passed |
| Backend suite, whole module | `./gradlew test --no-daemon` → `BUILD SUCCESSFUL` in 4m28s, 940 result files, 0 failures |
| Angular lint | `All files pass linting.` |
| Angular suite | 185 files / 3735 tests passed |
| Angular build | `Application bundle generation complete.` |
| Committed Angular bytes re-verified after the `pre-commit` hook rewrote them | 50/50 in the same spec; `git diff` of the hook's rewrite reviewed |

**Differential check**: no existing assertion was rewritten except the two `employee-list` specs that pin
the copy this change replaces — those *are* the change, and they were updated in both directions (the new
sentence matches the revocation, and no longer matches the denial). Every other pinned behavior of the
code this change touches — the termination transition enqueuing exactly one `DEACTIVATE`, `Terminated`
enqueuing nothing on a membership change, `ensureAccountEnabled` enabling a non-revoked account, the active
employee's required set and claim scope — passes unchanged in the full suites above.

## Follow-up (found, priced, not in this slice)

`AccessProvisioningRequestService.apply` and `resend` refuse a `Terminated` employee but not a
soft-deleted one, so an operator can still ask for an `ACTIVATE` on a revoked employee: for a linked
account that means a task, an empty required set and a disabled account left alone (harmless), but for an
**unlinked** one `activate` creates the account and sends the invitation. Closing it means extending two
guards to `isRevoked` and either generalising `TerminatedEmployeeRefusedException`'s message (it names
`Terminated` in its wire text) or adding a sibling exception — a small, separate slice with its own
message-contract decision, not a silent widening of this one.

## Log

**L1** (2026-10-22, user, verbatim): «ok vamos con g41»

**L2** (2026-10-22, user's decision among the three shapes offered, verbatim): «Revocación real
(recomendada)» — `enabled=false pasa a ser revocación de verdad: la derivación lo trata como sin roles y
el disable encola DEACTIVATE; el re-enable reconcilia.`

**L3** (2026-10-22, measured before any write, and the reason the guardrail shape was not enough): the
revocation machinery already existed (`POST …/access/deactivate` → a `DEACTIVATE` task that disables the
account, removes the roles and deletes the five `company_*` claims). The gap was that nothing on the
employee write path ever asked for it (`EmployeeService.deleteEmployee:271` and `setEmployeeEnabled` wrote
the flag and returned), the derivations never read the flag (`AccessProvisioningRoleService:172`,
`AccessProvisioningMembershipService.derivedScope:160`), and `ContractService.createContract:167` refused a
contract for a `Terminated` employee but not for a disabled one — so "disable, then open a contract"
re-granted roles. That third fact is why a guardrail on the disable button would have closed only one of
the two orderings.

**L4** (2026-10-22, precision recorded rather than silently applied): S3 names `kindFor`, whose `ACTIVATE`
arm creates an account and sends an invitation. S8 narrows that to the linked case: a re-enable of an
employee with no account enqueues nothing, because there is nothing to restore and an invitation nobody
asked for is not the fact a re-enable produces. The narrowing is fail-safe and matches the rule
`onMembershipChanged` already documents (S2 there).

**L5** (2026-10-22, measured while implementing, and added as S9): `derivedScope` short-circuited on
`Terminated` only, so the `DEACTIVATE` convergence would have removed the roles and then written the real
claim scope back. The claim projection had to join the same rule or the revocation would have been half a
revocation.

**L6** (2026-10-22, environment note): the fresh worktree had no `node_modules`, so the first Angular run
could not start the builder. `npm ci` fixed it, and the RED was still observed properly by reverting the
component to its pre-change bytes, running the spec, and restoring the edit — the evidence row above
records that run.
