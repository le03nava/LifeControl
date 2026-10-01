# ODD feature: company-scope-local-fallback

**Status**: designed — **not decided and not implemented**. The mechanism choice is **D1**, and it is
the user's; W1–W3 below wait on it. This header claims no branch, push or PR state; see the evidence
log.
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

**Recommendation** (mine, to be decided by the user): **A as the destination, B1 only if the HR/employee
source is what feeds it**, and no fallback at all until an administrator can assert the membership.
If a bridge is needed before A lands, it must be B1 with `lc-admin`-only writes — never B2.

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

## Decisions (user-owned — **open**, this is D1's content)

| # | Decision | Options and consequence |
| --- | --- | --- |
| **D1** | **Which mechanism closes the gap** | **(A)** IdP-provisioned claims + membership · **(B1)** locally stored, admin-owned membership read as a fallback · **(B2)** locally stored preferences as they are — **rejected above** · **(A+B1)** phase A in, B1 as the bridge. The choice decides whether this repository keeps one authorization source (the token) or gains a second one (its own DB) |
| **D2** | If B1 is used: **is it a bridge or the destination?** | A bridge writes a thing we intend to delete, and every endpoint built on it inherits the deletion. As a destination it becomes the fourth source of truth `hr-org-structure`'s T12 already warned about in a different context (Keycloak, the template, a mirror, the token) |
| **D3** | **Who may write the membership** | The only safe answer is an operator with `lc-admin`, never the subject — and the profile screen must stop writing the company/country/region/zone ids. Does the profile keep writing the **store** (E20 shows two features depend on it today), or does that move too? |
| **D4** | **Multi-company users** | The stored row is single-valued (E5, E23) and the claims are lists (E12). If multi-company is real, B1 needs a table with a composite key and A needs one group membership per company; if it is not real, the single-valued column is a documented ceiling |
| **D5** | **Whether A's infra work is in scope here** | A needs a mapper and a membership operation. The mapper is environment configuration this repository cannot test; the membership is backend code (`IdentityProvider` has no `addUserToGroup`, E14). Splitting "the code" from "the environment" is a delivery decision |

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

## Work units

Contingent on D1. Sizing follows the repository's 400-line review budget, and each row is declared
**over or under** it explicitly.

| | Content | Sizing note |
| --- | --- | --- |
| **W0** | *(only under A)* The environment half: the protocol mappers, written down as the artifact the deployment needs, plus the group membership the mappers read | **Not testable from this repository**, and that is the reason it is its own row rather than a task inside W1 |
| **W1** | *(only under A)* The backend half of membership: an `addUserToGroup`/`removeUserFromGroup` capability on `IdentityProvider` and its Keycloak implementation, with tests against the existing identity abstraction | Bounded: one interface, one implementation, its tests |
| **W2** | The **decision implementation** for D1: either the mapper documentation and its verification procedure (A), or the membership table + migration + admin-only write path + validation (B1) | Under B1 this row carries a migration, an entity, a service, an admin endpoint and its tests: **declared over budget**, and the natural split is migration+model / write path / read path |
| **W3** | The fallback read at the choke point (T1–T4) plus the deny-path tests (T6) | **Declared over budget** if B1 is chosen, because every level needs its own pin |

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

- **Blocked on D1**, which is the user's: no work unit starts before it is answered. This is the whole
  content of the record — the exploration is done, the mechanism is not chosen.
- **Blocked on the HR branch for migration numbering** if B1 needs a migration: `V19`/`V20` are taken
  in flight, so B1's migration cannot be numbered until `hr-org-structure` lands or renumbers.
- **Deferred**: re-validating existing rows (G6), multi-company support unless D4 says it is real, and
  the k8s/docker realm divergence (G2), which is a deployment decision larger than this record.

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-10-01 | Design round opened against `main @ 274c67f` in the worktree `wN`, created with `herdr worktree create` per the repository's worktree procedure. E1–E12 read by the parent: the scope-resolution path, the claim shape, the `user_preferences` DDL and its two writers, and the absence of any read for authorization. E13–E24 mapped read-only by a scout run in this same worktree, including the Keycloak provisioning picture and the test pins. **The parent independently re-verified the three load-bearing facts** (E13/E14/E15 and the 37 call sites): `grep` over `docker/scripts/keycloak-setup.sh` shows no mapper and no group line, `grep` for `addUserToGroup`/`joinGroup` returns nothing while `createGroup` appears in five places, and the k8s manifests import `spring-microservices-security-realm` with zero `lc-*` roles and zero `company_*` claims — which the scout had not reported. `grep -c currentUserContext.verify` → **37 hits in 18 files**. **No source line was written**, and no decision was taken: D1–D5 are open. |
