# ODD feature: company-scope-local-fallback

**Status**: designed — **not implemented**, and **every decision in this record is closed** (D1 = A, D3, D6). The remaining work is W0–W3 below, and its prerequisites live elsewhere: `employee-store-assignments` (the fact) and `employee-access-provisioning` (the projection), both unwritten. This header claims no branch, push or PR state; see the evidence log.
**Created**: 2026-10-01 · **Risk**: **high** — it decides what an authorization input *is*. The wrong
answer turns a scope guard into a self-service tenant switcher (see "The escalation the name hides"),
and it lands on the single choke point that all 37 scoped call sites in the repository go through.
**Repository**: LifeControl, `life-control-api/**` (the change, if it happens, is backend-only). The
Angular app is a **consumer**, not a subject: its profile screen is the writer of the row this record
is about, and that is part of the problem.
**Migration**: this branch is off `main @ 274c67f`, where **`V19` is free** — but
`hr-org-structure` is using `V19` and reserving `V20` on `feat/hr-org-structure` right now. If this
record needs a migration it must be numbered **after** that pair lands, or the two branches collide on
merge (Cross-record dependencies).
**Base**: `main` @ `274c67f` · **Branch**: `feat/company-scope-local-fallback` · **Worktree**:
`~/workspace/LifeControl-worktrees/feat-company-scope-local-fallback` (herdr `wN`), created with the
procedure in `.agents/skills/project-conventions/references/worktrees.md`.
**Requested by**: the user — "escribe el registro de company scope", 2026-10-01. It exists because
`hr-org-structure` declared this record as the thing that closes **its G1** (blocking its merge), and
because four already-merged records declared the same missing piece as a follow-up.

## Origin

The scoped authorization of this repository does not read the database. `CurrentUserContext` decides
whether a caller may touch a company, country, region, zone or store by reading ids out of the **JWT
claims** `company_id`, `company_country_id`, `company_region_id`, `company_zone_id` and
`company_store_id` (E1–E3). Those claims are **provisioned by nothing in this repository** (E13–E15),
and no per-user company membership exists in Keycloak either (E14).

Four records have already hit that wall and declared it as pending work rather than fixing it:
`store-claim-hardening` ("an `lc-sales` principal without a manual Keycloak mapper is denied on all
four guarded endpoints … **Declared**, not fixed", `:185`), `purchase-order-goods-receipt` ("the claim
path is the real precondition, and it is provisioning, not code", `:557`), `product-variant-admin-ui`
("the Keycloak protocol mappers … declared follow-up, blocking for any real environment", `:174`) and
`sales-location-aware-stock` (`:560`). A fifth, `odd-status-reconciliation`, recorded it as "real
pending work" (D5, `:103`).

Meanwhile the repository **does** store exactly those five ids per user: `user_preferences` has one
row per Keycloak user, with all five columns nullable, written by the user's own profile screen
(E5–E9). That is the whole reason this record is named the way it is — the local data exists, it looks
like the claim path, and nothing reads it for authorization (E10).

## The escalation the name hides

**Reading `user_preferences` as the fallback source for the claim path is not safe, and the reason is
not a detail.** It is the central finding of this design round:

1. `user_preferences` is written **by the subject of the authorization decision, about itself**:
   `PUT /api/profile` → `ProfileService.updateProfile` copies whichever of the five ids are non-null
   straight onto the row (E8). The caller is the user; the row is the user's own.
2. **Nothing validates them**: `ProfileUpdateRequest` carries `@Size`/`@Email` on the three string
   fields and **no constraint at all** on the five `UUID`s (E9), and the service checks neither
   existence, nor ownership, nor any relation to the roles the caller holds — the FK is the only
   boundary (E8).
3. The guard these ids would feed is the one that exists **precisely to stop cross-tenant access**.
   `verifyCompanyStoreAccess` is what makes a store-scoped role a *scoped* role; the role itself says
   "you may work with stores", the claim path says "these ones" (E1, and `store-claim-hardening`'s own
   Problem section: without the guard "any principal holding `lc-sales` could set the sellable stock
   and the list and cost prices of a store of another company").

So a fallback that trusts the stored ids means: **any holder of a scoped role can widen their own
tenant by editing their profile**, because the tenant is a field they type and the only proof required
is that the UUID exists. The endpoint's `@PreAuthorize` keeps holding — the caller still needs
`lc-sales` — but `lc-sales` is the *what*, and the claim path was the *where*. Losing the *where* is
losing the guard.

This is why **B2 below is rejected**, and why the record's name is narrower than its content: the
name is the identifier four other records already reference, not a conclusion about the mechanism.

Two further structural facts point the same way:

- **The stored shape cannot express what the claims express.** Each of the five columns is a single
  scalar `UUID` (E5), while the claim path accepts a list (E3, E12). A user with `lc-company-country`
  for three countries is expressible in a token and **not** expressible in `user_preferences`.
- **Nothing in the repository populates the local row with an administrator's intent.** The only
  writers are the user's own profile screen and `UsersAdminService.createUser`, which saves an empty
  row (E7). There is no place where "this user belongs to this company" is asserted by anyone with
  authority to assert it.

## Three mechanisms, one decision

| | Mechanism | What it needs | Assessment |
| --- | --- | --- | --- |
| **A** | **IdP-provisioned claims**: a protocol mapper per claim, plus the membership those mappers read | A mapper on the client (or on group attributes), and per-user group membership or user attributes in Keycloak. The **groups already exist with the right attributes** (E14) — what does not exist is a mapper and any membership operation | The only mechanism that keeps the authorization input outside the user's own reach. It is also the one the four merged records assumed. **Cost: it is infrastructure** — outside this repository's test loop, per-environment, and today it is not even expressible from the backend, because `IdentityProvider` has no group-membership operation (E14) |
| **B1** | **Locally stored membership, admin-owned**: the ids come from a table an operator with `lc-admin` writes (never the subject), read as the fallback when the claim is absent | A new membership table (or an admin-only write path on the existing row), a validation pass, and a `CurrentUserContext` that can read it | Safe **if and only if** the write path is not the profile screen. This is where the repository's own future source of "who belongs where" already points: HR's `employee-store-assignments`/**G8** and its employee record are exactly that assertion, made by an operator |
| **B2** | **Locally stored preferences as they are today** | Nothing — the data is already there | **Rejected.** Self-asserted, unvalidated, single-valued, and it is the exact input the guard exists to distrust. Documented here so nobody "solves" this with a ten-line change |

**Recommendation — adopted: D1 was closed as A.** A as the destination, B1 only if the HR/employee
source is what feeds it, and no fallback at all until an administrator can assert the membership.
If a bridge is needed before A lands, it must be B1 with `lc-admin`-only writes — never B2.

## The membership flow (decided by the maintainer, 2026-10-01)

The flow below is **D3's content**, specified by the maintainer while breaking the decision down. It
is the answer to "who writes the membership": the **organisational fact leads, and the access flow
projects it**. The subject never writes anything.

| # | Act | Who acts | Where it lives today |
| --- | --- | --- | --- |
| 1 | Hire: the personnel record and the contract (position, level, salary, dates) | HR (`lc-employee`) | `employees` + `employee_contracts` — designed as `V20` in `employee-registry`, **nothing implemented** |
| 2 | **The contract is activated**: the **store** and **the position** are assigned | HR | **Nowhere**: the store assignment is `employee-store-assignments`' (a record nobody has written yet) |
| 3 | Access is derived: roles from the position↔roles relation, attributes from the membership | the access flow | `position_roles` — **W1b of `hr-org-structure`, not built** — plus attributes/groups in Keycloak |
| 4 | The user is **created or linked**, with those roles and attributes | the access flow (`lc-employee-access`) | Keycloak. The existing create path is broken (it discards the generated password, marks it non-temporary, has no SMTP and no configured application client id — `employee-registry` F13) |
| 5 | The token finally carries `company_id` … `company_store_id`, and the store-scoped roles that were granted months ago stop answering 403 | Keycloak, through the mapper | **The mapper exists nowhere** (E13–E15) |

Two facts the flow pins down, and one question it does not answer:

- **The store is not part of the contract.** A transfer must not close a legal contract (`employee-registry`
  T13 closes the previous one the day before), so the assignment is **its own dated row created in the
  same act**: same moment, different truth. That row is what feeds the claim, which is why
  `employee-store-assignments` becomes load-bearing for this record rather than a neighbour.
- **A person holds one position.** The maintainer considered "the position **or positions**" and
  rejected the plural; it is recorded as **D6** in `employee-registry`. Its consequence is stronger
  than a preference: with the exclusion constraint forbidding overlapping contracts of one employee
  (T12 there) and a new contract closing the previous one (T13 there), "two positions at once" is not
  expressible **without a child table** — so the role template a person receives is the union of
  exactly one position.
- **What was still open** — the trigger and the privilege collision — **was answered on 2026-10-01**, and it is **D6** below. The pipeline it forces is specified next.

## The asynchronous boundary (D6a's answer)

The flow above ends in a write to **another system**, so the question is not "should it be asynchronous" — it
has to be, or a Keycloak outage becomes a half-done hire — but **where the intent survives a failure**.

**The trigger.** The transaction that asserts the fact (contract activation, a store-assignment change, a
termination) writes the intent as an **outbox row, in the same transaction**. Not "publish an event and hope":
the repository's existing instance of that pattern is the counter-example (E26) — `KeycloakGroupEventListener`
fires on `AFTER_COMMIT`, catches `IdentityProviderException`, logs a warning and **never retries**, so a
Keycloak outage at company creation leaves a company without its group, silently and permanently. With the
intent in the same transaction, a crash between the commit and the Keycloak write loses nothing.

**The payload is a reference, never an instruction.** The row says *"the contract of employee X changed"*,
not *"give X `lc-sales` on store 3"*. That is a security rule, not a style preference: with an instruction,
whoever writes the message decides the grant, and the privilege collision D8 was about comes back — the topic
is just a new door for it. With a reference, the worker reads the **current** truth (contract + assignment +
`position_roles`) and **derives** the grant, so the enqueuer decides nothing.

**The worker reconciles; it does not replay.** For each intent: read the current truth → compute the **diff**
against what Keycloak already has → apply it idempotently → record the outcome. The natural key is
`employees.keycloak_user_id`, unique in V20: it is how "this person already has an account" becomes **link**
instead of **create** (the 409 that `employee-registry` T13 already anticipates). Reconcile-not-replay brings
three properties for free: duplicates and out-of-order processing are harmless, retries are safe, and the
**revocation** path is the same pipe — an employee who becomes `Terminated` produces an intent that the same
worker resolves by disabling the account and removing the membership, which is how `employee-registry`'s G7
("nobody revokes anything") closes without a second flow.

**The states are visible.** `pending` → `applied` | `failed` (reason, attempts), shown in the employee's
Access section next to the requester and the approver. Without that, the operator assumes it worked — which
is exactly what happens today with the group listener.

**The transport.** An **outbox table plus a `@Scheduled` poller** is the recommendation: durable, retryable,
inside the monolith's transaction, and with **no new infrastructure** — an important consideration because the
repository has **no broker at all**, no scheduler and no retry machinery (E25). Kafka's real advantage
(partition-key ordering) is not needed here, because reconciliation makes ordering irrelevant. A broker
becomes justified when a **second consumer** exists (access + audit + notifications), when something **outside
the JVM** needs the signal, or at real volume — none of which is true today. And the outbox keeps that door
open: the day a broker is wanted, a relay publishes the same rows and **no producer changes**.

Two things the async boundary adds on the approval side, and they are easy to forget: the **diff is mandatory**
(the gate reviews it, and the auto-apply path logs it), and a request that waits **must be re-derived when it
is applied** — approving a snapshot from before a contract change means approving something that is no longer
true.

## Verified exploration evidence

Read-only exploration ran on 2026-10-01 against `main @ 274c67f`, in this worktree. E1–E12 were read
by the parent directly; E13–E24 were mapped by a read-only scout run in the same worktree, and every
one of them is an anchor rather than a summary.

| # | Fact | Anchor |
| --- | --- | --- |
| E1 | `verifyCompanyAccess` is `isAdmin()` **or** a `company_id` claim check — it consults neither the role registry nor the database. `isAdmin()` accepts `life-control-admin` (legacy realm role) **or** `lc-admin` | `common/auth/CurrentUserContext.java:114-119`, `:202-207` |
| E2 | The four deeper checks resolve the caller's **broadest granted scope** first: `broadestGrantedScope` returns the first `ScopeLevel` in `[COMPANY..maxScope]` whose `roleNames()` any authority matches, and if none matches it throws `"Insufficient role for <label> access"` **before any claim is read** | `CurrentUserContext.java:325-341` |
| E3 | Per-level claim, and whether the id is required when absent: `company_id` **required**, `company_country_id` **required**, `company_region_id` optional, `company_zone_id` optional, `company_store_id` optional. A required level with an empty claim set denies with `"Access denied to <label>: <id>"`; an optional level denies only on a non-null mismatch | `common/security/ScopeLevel.java:26,29,32,35,58`; `CurrentUserContext.java:349-357` |
| E4 | The single read point for every claim: `idsFor(level)` memoizes `extractUuidSetFromClaim(level.claim())`, which reads the `Jwt` principal straight out of `SecurityContextHolder` and accepts a list, an array or a comma-separated string. Malformed, missing and blank all collapse to an **empty set** | `CurrentUserContext.java:372-374`, `:408-457` |
| E5 | `user_preferences` is one row per user with five **scalar** nullable ids and nothing else: `keycloak_user_id VARCHAR(36) NOT NULL UNIQUE`, then `company_country_id`, `company_id`, `company_region_id`, `company_zone_id`, `company_store_id`, each `UUID REFERENCES …` and nullable | `db/migration/V1__baseline_schema.sql:364-376` |
| E6 | The Java side is a plain entity + `findByKeycloakUserId(String)` and nothing else — one query method in the whole repository | `usersadmin/model/UserPreferences.java:9-33`; `usersadmin/repository/UserPreferencesRepository.java:10-12` |
| E7 | The row is created lazily with all ids null, in two places: the first `GET /api/profile`, and `UsersAdminService.createUser` right after the Keycloak user is created | `profile/ProfileService.java:51-54`; `usersadmin/service/UsersAdminService.java:70-71` |
| E8 | The write path copies only the non-null ids onto the row — `if (request.companyId() != null) preferences.setCompanyId(...)`, once per level — and performs **no** existence, ownership or role check | `profile/ProfileService.java:98-118` |
| E9 | `ProfileUpdateRequest` constrains the three strings and **nothing** on the five `UUID`s: no `@NotNull`, no existence, no cross-check | `profile/dto/ProfileUpdateRequest.java:7-13` |
| E10 | **No authorization path reads `user_preferences`.** Its only readers and writers are `ProfileService` (read + write) and `UsersAdminService.createUser` (write); no other class imports the type | grep `UserPreferences` → 4 files, all named above |
| E11 | The token is the **only** source the scope checks have: `CurrentUserContext` injects no repository, is `@Scope("request")`, and resolves ids from the security context lazily on first access | `CurrentUserContext.java:22-23`, `:388-399` |
| E12 | The claim path accepts a **list**: `extractUuidSetFromClaim` handles a list, an array or a comma-separated string, and the tests pin "multiple comma-separated UUIDs" and "deduplicate repeated UUIDs" per level. The stored row cannot hold a list (E5) | `CurrentUserContext.java:424-428`; `common/auth/CurrentUserContextTest.java:119-143` and its siblings per level |
| E13 | `docker/scripts/keycloak-setup.sh` provisions the realm, the clients and the `lc-*` **roles** — and **no protocol mapper and no group**: the entire file's only `group`/`mapper`/`claim` matches are the service-account role names `query-groups` and `manage-users` | `docker/scripts/keycloak-setup.sh:152-166`, `:179-186` |
| E14 | Keycloak **groups carrying the claim attributes already exist**: the backend creates `lc-company-<id>` (attributes `company_id`), `lc-company-country-<id>`, and the region/zone/store siblings through `createGroup(name, attributes, parentId)`. But there is **no membership operation anywhere** — no `addUserToGroup`, no `joinGroup` — so nobody is ever put in those groups, and **no mapper turns their attributes into token claims** | `company/listener/KeycloakGroupEventListener.java:45,62,86,108,127`; `usersadmin/identity/IdentityProvider.java:104`; `usersadmin/identity/keycloak/KeycloakIdentityProvider.java:432-448`; grep `addUserToGroup`/`joinGroup` → none |
| E15 | The k8s manifests import a **different, unrelated realm**: `spring-microservices-security-realm`, from a tutorial, with standard mappers only (`locale`, `client_id`, `groups`, `resource_access.${client_id}.roles`, `realm_access.roles`), **zero** `lc-*` roles and **zero** `company_*` claims. The two copies are byte-identical | `k8s/manifests/infrastructure/keycloak.yml:98` (realm), `:20` (`--import-realm`), `:667-1005` (mappers); `k8s/manifests/infra/keycloak.yml` (identical) |
| E16 | **37 call sites** of the five `verify*Access` methods across **18 distinct classes in 7 packages**: 5 `verifyCompanyAccess` (`company.service`), 2 country, 6 region, 6 zone, and **18 `verifyCompanyStoreAccess`** spread over `salesorder`, `product`, `purchaseorder`, `scheduling` (5 classes), `store` (4), `goodsreceipt` and `inventory` | grep `currentUserContext.verify` over `src/main/java` → 37 hits, 18 files |
| E17 | The denial message shape is the client's only signal: `"Access denied to <label>: <id>"` for a claim miss and `"Insufficient role for <label-with-dashes> access"` for no role in range. The company level **cannot** produce the second one (E1 has no such path) | `CurrentUserContext.java:359-368` |
| E18 | Authorities come from `realm_access.roles` **and** `resource_access.<azp>.roles`, each prefixed `ROLE_`; the claims themselves are never copied anywhere else | `config/security/JwtDecoderConfig.java:57-95` |
| E19 | `SecurityConfig` sets URL rules only (`/api/users-admin/**` → realm `admin`, `/api/**` → `authenticated()`), and **no filter or interceptor mutates the `Authentication` or its claims**: the only two `OncePerRequestFilter`s wrap the request body and rate-limit users-admin | `config/security/SecurityConfig.java:34-64`; `config/filter/ContentCachingFilter.java`; `config/ratelimit/RateLimitFilter.java` |
| E20 | The commit is real, not hypothetical: the Angular profile form carries the cascade selection and always sends `companyStoreId` (null when unselected), and two features read the result back as the **active store** (`variant-store-context.service.ts`, `scheduling-store-context.service.ts`, both reading `profile.companyStoreId`) | `features/user/profile/user-profile.component.ts:144,410,435-443`; `features/user/profile/data/profile.models.ts:6-34`; `features/products/data/variant-store-context.service.ts:24-27,46-49`; `features/scheduling/data/scheduling-store-context.service.ts:24-27,46-49` |
| E21 | Tests pin the fail-closed behaviour in **both** directions, and they must not be weakened: "claim absent ⇒ empty set" is pinned per level by the `missingClaim`/`blankClaim` getters (10 cases), and "a required claim absent ⇒ denied" by three more (`countryUserThrowsForNullClaim`, `salesUserWithoutRequiredClaimIsDenied`, `receivingUserWithoutRequiredClaimIsDenied`) | `CurrentUserContextTest.java:175,185,267,277,359,369,451,461,1216,1226`; `:772-786`, `:1531-1559`, `:1596-1616` |
| E22 | Integration tests authenticate non-admins in both shapes: the claim-bearing helper sets all five claims (`scopedSalesJwt`), and admin-only classes use `jwt().authorities(...)` with **no** claims at all, which only works because `isAdmin()` short-circuits. `store-claim-hardening` measured **12** `SalesOrderIntegrationTest` cases failing precisely because a non-admin JWT arrived without the claim path | `salesorder/controller/SalesOrderIntegrationTest.java:2370-2386`; `scheduling/SchedulingSlotIntegrationTest.java:379-385` (+3 siblings); `store/StoreAreaIntegrationTest.java:169`; `odd/tasks/store-claim-hardening.md` |
| E23 | The stored row is per-user, so "which company is this user in" is answered for **one** company only, and `keycloak_user_id` holds the JWT `sub` (a 36-char UUID) — the same value `getUserId()` returns | `V1:366`; `CurrentUserContext.java:299-306`; `ProfileService.java:51,98` |
| E24 | Nothing resembling a fallback exists today: no "claim absent ⇒ X" concept anywhere outside `CurrentUserContext`'s own javadoc, and no repository injected into it | grep `local fallback\|claim is absent\|fall back to` → only unrelated UI copy and `CurrentUserContext.java:405` |
| E25 | **The repository has no asynchronous infrastructure to lean on**: **no** broker dependency of any kind (no Kafka, Rabbit, AMQP, JMS), **no** `@Scheduled`/`@EnableScheduling`, **no** `@Async`, and no retry, outbox or pending-state concept anywhere in `src/main/java`. The entire async vocabulary is Spring's `@TransactionalEventListener` after commit | `life-control-api/build.gradle`, `build.gradle`, `settings.gradle` (grepped for the broker names — zero hits); grep for `@Scheduled`/`@EnableScheduling`/`@Async`/`@TransactionalEventListener` → only the six listener methods below; grep for `retry`/`outbox`/`reconcile`/`pending` → only unrelated domain prose |
| E26 | **The repository's one instance of "fire an event after commit" swallows its failure**: `KeycloakGroupEventListener` has five `@TransactionalEventListener(AFTER_COMMIT)` handlers that create the `lc-company-*` groups, and each catches `IdentityProviderException` with a `logger.warn` — no retry, no persisted state, no repair, and the class Javadoc says so: "Group creation failures are logged but never propagated" | `company/listener/KeycloakGroupEventListener.java:41,44-52,57,81,103,122`; the group attribute it writes is `Map.of("company_id", List.of(event.getId().toString()))`, which is exactly the value a mapper would read. This is **G9** |
| E27 | The activity-log listener is the one that runs with `fallbackExecution = true`, so the audit trail does not depend on a surrounding transaction | `activity/listener/ActivityLogEventListener.java:31` |

## Decisions (user-owned — **all closed**; kept as the record of what was decided and by whom)

| # | Decision | Options and consequence |
| --- | --- | --- |
| **D1** | **Which mechanism closes the gap — CLOSED by the maintainer on 2026-10-01: A**, IdP-provisioned claims with the membership **projected** into Keycloak from the contract activation | **B1 is dropped as redundant**, not merely as unsafe: the flow D3 decided already writes to Keycloak (create or link the account, assign the template's roles), so the membership is materialised there anyway and a local table would be a **second source of a fact the IdP already holds** — the same fourth-source problem `hr-org-structure`'s T12 warned about in another context. **B2 stays rejected** (the escalation, above). The decision also fixes the shape of the projection: the assignment is the **truth**, Keycloak holds a **materialisation**, and the diff is how divergence is detected |
| **D2** | If B1 is used: **is it a bridge or the destination?** — **CLOSED, not applicable**: with D1 = A there is no local authorisation source to bridge | Kept in the table on purpose. It is the question to reopen if B1 ever returns as a real requirement, and the answer it recorded — a bridge writes a thing we intend to delete, and every endpoint built on it inherits the deletion — is what makes it unattractive even then |
| **D3** | **Who may write the membership** — **CLOSED by the maintainer on 2026-10-01: the organisational fact leads and the access flow projects it.** The writer is **HR, acting on the contract**: activating it assigns the **store** and **the position**, and the position↔roles relation produces a Keycloak user with its roles and its attributes (the flow is spelled out above) | Rejected alternatives: the platform admin assigning companies per user with no involvement of the org chart, and abandoning the org chart as an authorisation input. Both create two truths that diverge. **The subject never writes it** (E8, E9) and the profile screen stops writing the company/country/region/zone ids — whether it keeps writing the **store** was closed the same day: it becomes a **derived default constrained to the assigned stores**, never a free-form assertion (`employee-store-assignments`' **D2/T11**, whose E20 is the reason it needed a rule at all). Two sub-questions survived inside this decision and are now closed as **D6**. **A note on D1**: this flow makes creating or linking the Keycloak user mandatory anyway (step 4), so whoever provisions already writes to Keycloak — which is exactly where mechanism A reads from. B1 existed as the alternative for granting access **without** touching Keycloak; under this flow it would be a second source of a fact that is already being written to the IdP, so the flow points at **A** and D1 should be confirmed with that in mind |
| **D4** | **Multi-company users** | The stored row is single-valued (E5, E23) and the claims are lists (E12). If multi-company is real, B1 needs a table with a composite key and A needs one group membership per company; if it is not real, the single-valued column is a documented ceiling |
| **D5** | **Whether A's infra work is in scope here** | A needs a mapper and a membership operation. The mapper is environment configuration this repository cannot test; the membership is backend code (`IdentityProvider` has no `addUserToGroup`, E14). Splitting "the code" from "the environment" is a delivery decision |
| **D6** | **The trigger and the privilege collision — CLOSED by the maintainer on 2026-10-01.** **(a)** The trigger is the transaction that asserts the organisational fact: it writes the intent (an **outbox row**) alongside the fact, and a **worker** executes it by reconciling Keycloak to the current truth. **(b)** The gate is a **policy over the computed diff**, defaulting to **auto-apply for the roles the position template declares**, with approval reserved for the cases that carry the risk (a person's first access, and the sensitive roles). The pipeline is specified in "The asynchronous boundary" | The asynchronous boundary is what answers (b): it turns the HR act from *granting* into *requesting*, so `lc-employee` no longer provokes access with its own hands — the **system** derives the grant from the org chart. Two honest caveats are recorded with it: the separation is preserved only **in spirit** while the same person may hold both roles (a gate that the same human walks through twice is audit theatre, not control), and auto-apply has a precondition — **`keycloak_user_id` must not be operator-typable** (`employee-registry` T17), or a holder of `lc-employee` can point a new record at their own account and have the system grant them the roles of a position they chose |

## Decisions (mine, technical — challenge them if you disagree)

| # | Decision | Why |
| --- | --- | --- |
| T1 | Whatever the mechanism, it enters through **one choke point**: `idsFor(level)` (E4) | It is the single read point for all five claims and 37 call sites, so a fallback implemented there cannot be forgotten on a path — and a fallback implemented anywhere else will be |
| T2 | **Precedence is fail-closed and one-directional: the claim wins whenever it is non-empty, and a fallback may never widen a narrower claim** | The claim is provisioned by whoever administers the IdP; the fallback is local. If a preference could override a present claim, a user with a legitimate narrow grant would widen it — the escalation again, through the other door |
| T3 | A fallback may only be consulted when the claim set for that level is **empty**, never to *add* an id to a non-empty set | Same reason as T2. It also keeps the failure mode readable: absent means "fall back", mismatch means "denied" |
| T4 | The fallback must be **fail-closed on error**: a membership lookup that fails, times out or returns something malformed yields the empty set, exactly as a malformed claim does today (E4) | A database wobble must not become a 500 on every scoped endpoint, and it must never become an allow |
| T5 | If B1 is chosen, the membership lives in **its own table keyed by user and scope, with a composite key that admits several rows per user** — not in `user_preferences`' five scalar columns | E5/E12/E23: the existing shape is structurally single-tenant, so reusing it would freeze a ceiling in place and put an authorization input in the row the subject owns |
| T6 | The **existing tests must not be weakened**, and the fallback needs its own: the 10 "claim absent ⇒ empty set" getters and the 3 "required claim absent ⇒ denied" cases (E21) stay as they are, and the new behaviour is pinned by a separate, explicitly named test per level | They encode the intended fail-closed semantics. Rewriting them to pass with a fallback would delete the record of what the system does when there is nothing to fall back to |
| T7 | No cache in front of the membership read, and no memoization beyond the existing per-request `claimIds` map | G12 of `hr-org-structure` is a live example in this repository of what caching an authorization input does when the cache outlives the decision |
| T8 | `verifyCompanyAccess`'s **claim-only, role-agnostic** behaviour (E1) is not changed by this record | It was measured during the HR slice (`hr-org-structure`'s E19/T15/G11) and tightening it denies principals that pass today, including the legacy `life-control-country` caller. It is a separate decision with its own blast radius |
| T9 | **The membership emits a complete chain, top-level and multivalued.** The claim values are derived from the assigned **store** — `company_id`, `company_country_id`, `company_region_id`, `company_zone_id`, `company_store_id` — never only the store, and they carry the **exports/names `extractUuidSetFromClaim` reads, at the root of the token** | E3: `company_id` and `company_country_id` are **required** levels, so an assignment that emits only the store leaves the caller denied at the company level and the 403 survives the whole flow. E12: the parser accepts lists, so multiple stores are a list. E4: top-level, because the guard calls `getClaim("company_id")` and anything nested under `resource_access.<azp>` is invisible. This is the spec the provisioning code needs, and the likeliest bug if it is assumed |
| T10 | **Attributes are not claims.** Writing `company_id` as a Keycloak user attribute — or joining a `lc-company-<id>` group, whose attribute already exists — puts **nothing** in the token on its own: a **protocol mapper** is what turns the attribute into the claim the guard reads | E13–E15: this repository provisions no mapper for any `company_*` claim, in either the docker script or the k8s realm export (which ships mappers for standard attributes only). "Generate the user with its attributes" is necessary and **not sufficient**; the mapper is the piece nobody has written, and it is W0 |
| T11 | **The intent is an outbox row written in the same transaction as the fact, and the worker reconciles instead of replaying** (detail in "The asynchronous boundary") | E25/E26: the repository's only async mechanism is `@TransactionalEventListener` after commit, its one instance swallows the failure and nothing retries, and there is no scheduler, no retry state and no broker. "Enqueue an event" therefore means "lose it quietly" today. The outbox is the smallest shape that fixes that without buying infrastructure, and it keeps a future broker as a relay rather than a redesign |
| T12 | **The payload is a reference, never an instruction** | With an instruction, whoever writes the message decides the grant — the same privilege collision, one queue further away. With a reference, the worker derives the grant from the current contract, the assignment and the template, and the enqueuer decides nothing |
| T13 | **The gate is a policy over a computed diff, not a separate flow.** Default: auto-apply for the roles the position template declares; approval reserved for a person's first access and for the sensitive roles. The diff is always computed and logged, and a request that waits is **re-derived at apply time** | The flow already needs the diff (it is the revocability plan of `hr-org-structure` T12), so the gate is a policy switch rather than a redesign — which makes the decision reversible with information rather than a bet taken today. Re-deriving protects the approver from approving a snapshot that a later contract change made false |

## Work units

**Under D1 = A this record is mostly a contract plus an environment procedure, not a code slice.** That
is worth stating plainly, because the record's name promises a fallback the decision removed: what remains
is the mapper, the membership operation, and the projection — and the last one belongs to another record.
Sizing follows the repository's 400-line review budget, declared explicitly per row.

| | Content | Sizing note |
| --- | --- | --- |
| **W0** | The environment half: the **protocol mappers** (claim names, top-level, multivalued — T9) and the membership they read, written down as the artifact a deployment needs plus the procedure that verifies it end to end (a token that actually carries the five ids) | **Not testable from this repository**, which is why it is its own row. It also carries the unmade sub-choice: **user attributes** (proven here by the `locale` mapper) versus **group attributes** (the `lc-company-*` groups exist with the right attributes, but the standard membership mapper emits group **names**, not attributes — never verified in the target Keycloak version) |
| **W1** | The backend half of membership: `addUserToGroup` / `removeUserFromGroup` on `IdentityProvider` and its Keycloak implementation, with tests against the existing identity abstraction | **Under budget**: one interface, one implementation, its tests |
| **W2** | The **projection**: the outbox and the worker that reconcile Keycloak to the current truth ("The asynchronous boundary") | **Implemented by `employee-access-provisioning`**, which is unwritten — this record defines the contract and that record builds it. **Declared over budget there**, not here |
| **W3** | **Retire the profile's tenancy writes** and prove nothing regressed: the profile screen stops writing company/country/region/zone (D3), the tests that pin "claim absent ⇒ denied" stay green (T6), and the open **store** question moves to `employee-store-assignments`, where it becomes a derived default instead of a free-form preference | Small in lines, but it changes a merged surface and its Angular consumer (E20), so it needs its own review pass |

## Gaps

| # | Gap | Note |
| --- | --- | --- |
| G1 | **This record cannot be implemented as a "small change"**: under A it is infrastructure this repository cannot test, under B1 it is a new authorization source with a write path and a migration | There is no cheap version. Saying so is the point of the record |
| G2 | The k8s deployment and the docker deployment provision **two different Keycloak realms**, and the k8s one has neither the `lc-*` roles nor any company claim | So "the claims are provisioned somewhere" is false even in the strict sense: in k8s nobody holds the roles either (E15). Which deployment is authoritative is undeclared |
| G3 | Keycloak groups with the right attributes are **created and never joined** | The infrastructure for A partially exists and is dead code until a membership operation exists (E14). Declaring it prevents "we already have the groups" from reading as "the work is done" |
| G4 | A user who never opens the profile screen has a row with all ids null | So the fallback's coverage under B1 would be exactly the set of users who visited a screen, not the set of users who belong somewhere (E7) |
| G5 | The profile screen currently writes **all five** ids, including the four that decide tenancy | Under D3 that becomes a privilege the subject must lose; the Angular form and its models change with it, so this is not backend-only after all (E20) |
| G6 | `user_preferences`' five FK columns accept **any existing** id, and nothing has ever validated them | Pre-existing data may already point anywhere, so B1 cannot adopt the existing rows without deciding what to do with them |
| G7 | The legacy realm roles (`life-control-admin`, `life-control-country`) take part in `isAdmin()` and in the tests | Any change here must keep them working, and there is no inventory of who holds them (E1, E21) |
| G8 | No record in the repository states which of the four merged records' "declared follow-up" this one is meant to close | They each declared the mapper; this record is the first attempt to decide the mechanism, and it should say plainly that it supersedes nothing until D1 is answered |
| G9 | **A pre-existing silent divergence: the Keycloak group for a company may not exist and nothing detects it.** `KeycloakGroupEventListener` fires on `AFTER_COMMIT`, and each of its five handlers catches `IdentityProviderException` with a `logger.warn` and no retry, no state and no repair path | So the `lc-company-*` groups — the very artifact this record's mechanism A would join users to — can be missing for companies created while Keycloak was unavailable, and no test, job or screen would notice. It is the same class of defect as **G12** of `hr-org-structure`: a non-transactional side effect that outlives the decision, discovered while designing this record rather than by a failure. **Not this slice's to fix**, and the outbox design (T11) is also its repair: once intents are durable rows, group creation stops being fire-and-forget |

## Cross-record dependencies

| Record | Relation |
| --- | --- |
| `hr-org-structure` (on `feat/hr-org-structure`, **not yet merged**) | **Blocked on this record for merge** (its G1): its two write roles need the `company_id` claim, which nothing emits. Note its own finding: `verifyCompanyAccess` is claim-only and role-agnostic, so what it needs is the *claim*, not a role registration |
| `employee-registry` (same branch, not yet merged) | Declares the same G1. Its `employees`/`employee_contracts` are the future source of who belongs to a company — which is why B1's write path and HR's `employee-store-assignments` are the same design question seen from two sides |
| `employee-store-assignments` (not written yet) | The eventual assertion of "this person works in this store". Under B1 this record should **not** invent a parallel membership table without answering why it is not that one |
| `store-claim-hardening` (merged, `7958dd052`) | The record that measured the symptom: 12 integration tests failing on an `lc-sales` JWT without claims, and the WARNING that declared this work |
| `purchase-order-goods-receipt`, `product-variant-admin-ui`, `sales-location-aware-stock`, `odd-status-reconciliation` (all merged) | Each declares the missing mapper as pending work, from its own domain |
| Migration numbering | This branch is off `274c67f`, where `V19` is free. `feat/hr-org-structure` claims `V19` **and** `V20`. Two branches adding `V19` will collide on merge (Flyway checksums, and `life-control-api/AGENTS.md:874` forbids editing an applied migration). Whichever lands second must renumber, and the cleanest order is HR first |

## Non-goals

- **Not** changing `verifyCompanyAccess` into a role-aware check (T8). Its claim-only shape is a
  separate, measured decision with its own blast radius.
- **Not** implementing the Keycloak mappers from this repository (they are environment configuration,
  and W0 exists only to write down what the environment needs).
- **Not** re-validating or cleaning existing `user_preferences` rows (G6) unless B1 is chosen.
- **Not** touching the Angular profile screen beyond what D3 requires (G5).
- **Not** a general "user ↔ company" model: that is HR's `employee-store-assignments`, and this record
  must not silently become it.

## Task log

- [ ] W0 — the environment half of A (mappers + the membership they read) — **only if D1 = A**
- [ ] W1 — the membership capability on `IdentityProvider` — **only if D1 = A**
- [ ] W2 — the chosen mechanism: mapper documentation (A) or table + write path + validation (B1)
- [ ] W3 — the read at the choke point and the deny-path pins

## Deferred and blocking

- **Nothing blocks the decisions**: D1, D2, D3 and D6 are closed. What remains is **elsewhere**:
  `employee-store-assignments` must exist before the projection has a fact to project, and
  `employee-access-provisioning` before the projection itself exists.
- **Blocked on the HR branch for migration numbering**: `V19`/`V20` are taken in flight. Under A this
  record needs **no migration at all**, so the decision removes the collision rather than scheduling it.
- **Blocked on the environment for W0**: the mapper cannot be verified from this repository's test loop;
  the procedure that verifies it has to run against a real realm with a real token.
- **Deferred**: the `lc-company-*` group repair (**G9**), which the outbox would absorb but which is not
  this slice's to change; the k8s/docker realm divergence (**G2**), a deployment decision larger than this
  record; and re-validating existing `user_preferences` rows (**G6**), unnecessary now that they are not
  an authorisation input.

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-10-01 | **D3 closed by the maintainer** while breaking the decision down: the membership is the organisational fact asserted on contract activation, and the access flow projects it (the flow table above). The plural for positions was considered and **rejected** — recorded as **D6** in `employee-registry` — which left V20 as designed and made the store assignment the load-bearing piece of the flow. Two sub-questions survive and are recorded as this record's **D6**: the trigger (explicit action or date-derived) with its failure modes, and the collision with `hr-org-structure` D8. **No source line was written**; the mechanism (D1) is still open |
| 2026-10-01 | **D1 closed by the maintainer: mechanism A**, on the strength of D3's flow rather than on a preference — the flow already creates or links the Keycloak user, so the membership is written to the IdP in any case and B1 would only add a second source of the same fact. **D2 closed as not applicable** in the same pass, kept in the table as the question to reopen if a local source ever becomes a real requirement. The work units were rewritten to match: under A this record is an environment procedure, an identity-membership operation, and a projection whose implementation belongs to `employee-access-provisioning`. **No source line was written**, and under this decision the record needs no migration at all |
| 2026-10-01 | **D6 closed by the maintainer (a and b together)**, after the parent established what the repository actually offers for the asynchronous half: no broker, no scheduler, no retry state, and one `AFTER_COMMIT` listener that swallows its failure (E25–E27, read by the parent — nobody had looked at the messaging layer before). **(a)** the intent is an outbox row in the same transaction as the fact, and the worker reconciles the current truth instead of replaying a payload; no broker now, with the outbox as the later relay. **(b)** the gate is a policy over the computed diff, defaulting to auto-apply for the template's roles with approval for first access and the sensitive roles — which preserves D8 in spirit and literally only where the two roles sit with different people. The security precondition of auto-apply was named in the same pass: **`keycloak_user_id` must not be operator-typable**, or a holder of `lc-employee` grants themselves the roles of a position they chose — recorded as **T17** of `employee-registry`. The pass also produced **G9**, a pre-existing silent divergence (E26). |
| 2026-10-01 | Design round opened against `main @ 274c67f` in the worktree `wN`, created with `herdr worktree create` per the repository's worktree procedure. E1–E12 read by the parent: the scope-resolution path, the claim shape, the `user_preferences` DDL and its two writers, and the absence of any read for authorization. E13–E24 mapped read-only by a scout run in this same worktree, including the Keycloak provisioning picture and the test pins. **The parent independently re-verified the three load-bearing facts** (E13/E14/E15 and the 37 call sites): `grep` over `docker/scripts/keycloak-setup.sh` shows no mapper and no group line, `grep` for `addUserToGroup`/`joinGroup` returns nothing while `createGroup` appears in five places, and the k8s manifests import `spring-microservices-security-realm` with zero `lc-*` roles and zero `company_*` claims — which the scout had not reported. `grep -c currentUserContext.verify` → **37 hits in 18 files**. **No source line was written**, and no decision was taken: D1–D5 are open. |
