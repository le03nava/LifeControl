# ODD feature: keycloak-adapter-error-contract

**Status**: **planned only — nothing implemented, and the closing shape is still open** (2026-10-16). This
file is the unit's tracking commit, the shape `employee-access-provisioning`'s W1a, W1b, W2, W3, W4, W4b,
W4b2a, W5a, W5b, W4b2b, W5c, W5d, W7 and W8 each took. No source line is written until the shape is closed.
**Risk**: **high — item 3**, *changing a contract others already consume*: the status a consumer sees when
the identity provider refuses the application changes from the accidental **500** to a deliberate one, on
`usersadmin`'s adapter, which five packages outside `usersadmin` consume.
**Repository**: LifeControl — `life-control-api/**` (Spring Boot, Java 21, RESTEasy/Keycloak Admin Client
26.0.12). No gateway change, no frontend change.
**Migration**: **none** — no schema, no property, no endpoint, no realm change.
**Created**: 2026-10-16 · **Requested by**: the user, 2026-10-16, verbatim in **S1**.

## Origin

**G11** of `employee-access-provisioning`, declared on 2026-10-06 by W6a's native review and deliberately
left unfixed there because the fix is not that record's surface. It was re-measured on 2026-10-16, while
answering what remained in that record, and the measurement is what made the unit worth opening: the hole
is **not one method** and it is not only a `400`.

## Specs

- **S1** — *the user, 2026-10-16, verbatim*: «abrir la unidad del fix de mapeo de G11 (el catch que
  convierte ese 403 en un 403/503 nombrado), que es lo único con consecuencia real que la medición de hoy
  destapó». The parenthetical names one of the two outcomes the closing shape decides, and names this
  unit's subject exactly: the **mapping**, not the coupling.
- **S2** — *to be written when the shape is closed* (**T1**). It will quote the chosen shape's exact statuses
  and messages, and those strings are the ones the tests will pin. Until then this record carries no
  requirement the user has not stated, by design.

## Measurements (2026-10-16, read from the tree — none assumed)

**The port surface is 29 public methods over four catch shapes**, in
`src/main/java/com/lifecontrol/api/usersadmin/identity/keycloak/KeycloakIdentityProvider.java`:

| Shape | catches | methods | count |
| --- | --- | --- | --- |
| **S1** | `NotFoundException` → domain 404 · `ProcessingException` | `deleteUser:76`, `updateUser:88`, `disableUser:99`, `enableUser:113`, `getRealmRole:158`, `updateRealmRole:170`, `listClientRoles:206`, `addChildRole:266`, `removeChildRole:282`, `assignRoleToUser:302`, `removeRoleFromUser:324`, `getUserRoles:345`, `getUserRoles(client):364`, `getUserAttributes:387`, `updateUserAttribute:400`, `deleteUserAttribute:420`, `sendActionsEmail:442`, `findUsersByEmail:476`, `findUserDisplayName:489` | 19 |
| **S2** | `ClientErrorException` → 409 domain 409, **anything else** → 503 · `ProcessingException` | `createUser:53`, `createRealmRole:142`, `createGroup:525` | 3 |
| **S3** | `NotFoundException` → 404 · `ClientErrorException` → 409 or 503 · `ProcessingException` | `deleteRealmRole:185`, `createClientRole:220`, `deleteClientRole:240` | 3 |
| **S4** | `ProcessingException` only | `listRealmRoles:131`, `searchUsers:460`, `companyGroupExists:547`, `findGroupIdByName:556` | 4 |

**What escapes today, grouped by the status the JAX-RS type carries:**

- **Any non-409 `ClientErrorException`** — `400` `BadRequestException`, `401` `NotAuthorizedException`,
  `403` `ForbiddenException`, and every other 4xx — is caught in S2/S3 (as 503) and **escapes in the 23
  methods of S1+S4**, which includes `getUserRoles(userId, clientId)`, the read `G40`'s path performs.
- **A JAX-RS `404`** escapes in the 4 methods of S2+S4 — and where S2 *does* catch it, it answers **503**
  (`ClientErrorException` non-409 → `IdentityProviderConnectionException`), which is a lie: the resource is
  missing, not unreachable.
- **A JAX-RS 5xx** (`ServerErrorException`, which extends `WebApplicationException` and **not**
  `ClientErrorException`) **escapes in all 29 methods**, S2/S3 included.
- `ProcessingException` (transport) is caught in all 29, and that is the only shape that is complete.

**The hole reaches the HTTP boundary through the catch-all.** `exception/GlobalExceptionHandler.java` maps
exactly three domain types — `:147-149` → **404**, `:152-154` → **409**, `:157-160` → **503** with the fixed
message *Identity provider temporarily unavailable* — and nothing else; the catch-all is `:201-204`,
`Exception` → **500** *An unexpected error occurred*. So every escaped type above is answered as a 500 that
names nothing.

**A fourth permit would break almost nothing, and that is measured rather than assumed:**
`IdentityProviderException` is `sealed` with exactly three permits
(`IdentityProviderNotFoundException`, `IdentityProviderConflictException`,
`IdentityProviderConnectionException`), and **no** code discriminates on them — no `switch`, no subtype
pattern, no `instanceof` chain, no reflection over `permits` anywhere in `src/{main,test}`. The single pin
is `src/test/java/com/lifecontrol/api/usersadmin/identity/IdentityProviderExceptionTest.java:97-105`
(`getPermittedSubclasses()` + `containsExactlyInAnyOrder` of the three names), so the cost of a fourth
permit is that one assertion.

**The blast radius outside `usersadmin` is eight production consumers, and exactly one catches broadly:**
`profile/ProfileService.java:35`, `company/listener/KeycloakGroupEventListener.java:35` (which catches
`IdentityProviderException` **broadly** at `:48, :72, :94, :113, :133` and logs `warn` before swallowing),
`hr/service/PositionRoleService.java:65`, `provisioning/service/AccessProvisioningAccountService.java:52`
(which catches the **specific** `IdentityProviderConflictException` at `:240` to answer `LINKED`), and the
four provisioning reads (`AccessProvisioningQueryService:114`, `AccessProvisioningMembershipService:63`,
`AccessProvisioningRoleService:55`, `AccessProvisioningGateService:80`). Twelve test files consume the port
outside `usersadmin`; **none** catches the exception, they only stub and assert propagation.

**The idiom to follow already exists**: six sites map a provider 4xx onto a domain refusal —
`KeycloakIdentityProvider:65-68` (`createUser`) and `:147-150`, `:190-193`, `:228-233`, `:250-253`,
`:536-539`: `ClientErrorException` with status 409 → `IdentityProviderConflictException` → **409**. There is
**no** precedent for 400, 401 or 403.

**Nothing pins the current hole**, which is why closing it is a contract change and not a regression fix:
`grep` finds no test asserting a 500 for a provider `ClientErrorException`, and the catch-all's 500 is
pinned only against a plain `RuntimeException`
(`GlobalExceptionHandlerTest.java:104-108`) and a `DataAccessException` (`:285-289`).

**The framing discrepancy between the two records is stated rather than smoothed over**: `G11`'s own row
(`employee-access-provisioning.md:401`) is written around a **400** — an unregistered redirect URI or an
unknown action, from `sendActionsEmail` — while today's measurement on `G40`'s path is a **403** (a service
account lacking the realm-management role). Both are `ClientErrorException` subclasses and the same hole;
this record writes the hole down as it was measured, twice, with the site each measurement came from.

## Decisions (mine, technical — challenge them)

- **D1 — the subject is the adapter's error contract, not a method.** The measurement above is the argument:
  the hole is 23 of 29 methods for the 4xx family and 29 of 29 for the 5xx family. A per-method patch would
  leave the next method to rediscover it, so the unit fixes the **shape** and reuses it.
- **D2 — a provider 5xx becomes retryable, not unnamed.** `ServerErrorException` is transient by nature and
  today answers 500 with a message that names nothing; it belongs in the existing retryable bucket
  (`IdentityProviderConnectionException` → 503) in every method. This is additive and uncontroversial, and
  it rides with whichever shape is chosen.
- **D3 — a JAX-RS 404 is a domain 404 everywhere.** That fixes S2's current **503 for a missing resource**
  and closes the four methods where it escapes. Also additive.
- **D4 — the 409 → `IdentityProviderConflictException` branch stays exactly as it is.** It is this
  codebase's own precedent and the rest of the mapping should look like it.
- **D5 — what a *refused credential or permission* becomes is the user's decision, not mine**, because it is
  a contract decision with an operator-visible instruction attached: the three shapes below say what the
  operator is told to do, and they differ in exactly that.
- **D6 — nothing else moves.** No migration, no property, no endpoint, no realm change, no new port method,
  no change to what the port returns on success.
- **D7 — the evidence is test-first per shape.** Every shape pins its own status and message at the adapter
  (unit tests over a stubbed Keycloak client, the shape `KeycloakIdentityProviderTest` already uses for the
  409 branch at `:151-157`, `:227-231`) and, where a handler is added, at the boundary
  (`GlobalExceptionHandlerTest`). No test may assert on internals instead of the observable status.

## The closing shapes (one of them is the decision)

Every shape closes D2 and D3. They differ only in what a **refused credential/permission** and a **request
error** answer, and that is the whole of the disagreement.

- **Shape A — the same bucket, the hole closed (minimum change).** Extend S1 and S4 to catch
  `ClientErrorException`: 404 → domain 404, everything else (400/401/403/…) → the existing
  `IdentityProviderConnectionException` → **503, *Identity provider temporarily unavailable***. Touches one
  file, adds no permit and no handler, leaves `IdentityProviderExceptionTest:97-105` untouched.
  **Cost, and it is the whole cost:** a rotated service-account secret or a missing role is a condition no
  retry can fix, and the operator is told to retry a transient-sounding failure. The refusal is named and
  the name is wrong; `G11`'s own complaint — *a 500 rather than a named refusal* — is answered with the
  wrong name.
- **Shape B — the refusal gets its own name (port change).** One **fourth permit**,
  `IdentityProviderRefusedException` (or an equally narrow name), holding the provider's *refusals of our
  credentials or our request* — 400, 401, 403 — answered by a new handler as **502** with a message that
  says the provider refused this application; other 4xx keep the existing bucket, and D2/D3 ride along.
  **Cost:** the sealed hierarchy grows (one pin updated, `IdentityProviderExceptionTest:97-105`), one new
  row appears in the global error contract, and — measured, so it is not a surprise — the one broad catcher
  (`KeycloakGroupEventListener:48, :72, :94, :113, :133`) will swallow refusals too, which is why **T4**
  makes that listener's behaviour an explicit decision instead of a side effect. **Benefit:** the operator
  gets a status that says *this will not fix itself*, which is the actionable case, and it is
  distinguishable from a real outage.
- **Shape C — the HTTP boundary absorbs it (cheapest, widest).** No adapter change: one
  `@ExceptionHandler(jakarta.ws.rs.WebApplicationException.class)` in `GlobalExceptionHandler` mapping
  4xx → 502 and 5xx → 503. **Cost:** a provider-specific JAX-RS type enters the domain error contract — the
  exact coupling the sealed hierarchy exists to prevent — and the boundary cannot express per-outcome
  semantics the adapter already distinguishes (the 409 branch would still need its domain type, so the
  mapping would exist in two places and could drift), and any future non-provider JAX-RS type inherits the
  mapping.

**My recommendation is B**, because it is the only shape that answers S1 with the *correct* name and keeps
one translation surface for the provider→domain mapping; A is the honest minimum if the contract change is
unwanted, and C is the cheapest close if the boundary is the accepted home for it. **The shape is not
closed until the user closes it**, the way T41, T49, T56, T66, T70, T76, T81, T82, T91, T98, T99 and T102
were closed on the sibling records.

## Work units

| | Content | Sizing note |
| --- | --- | --- |
| **W1** | **The mapping itself**: D2, D3 and the chosen shape applied as one reusable translation across the four catch shapes, with the adapter's unit tests per outcome | **No new port method, no migration, no endpoint.** Its evidence is the adapter's own tests over a stubbed client — the shape the 409 branch is already pinned by |
| **W2** | **The boundary** (only under B or C): the handler, its status and message, and the listener decision of T4 | Reviewed against the global error contract, which is shared by every domain |
| **W3** | **The records**: `employee-access-provisioning`'s `G11` row gains the pointer that the fix landed here and what the operator now sees, and this record's evidence log closes | Docs only, and it is not optional: a gap that moved to its own unit has to say where it went |

Dependency order: **W1 → W2 → W3**. The shape (**T1**) precedes all three.

## Tasks

| # | Task | Links | Route | Commit |
| --- | --- | --- | --- | --- |
| **T1** | **Close the shape** — A, B or C, from the three above, with the exact statuses and messages for S2 | S1, S2 | the user | — |
| **T2** | The adapter's uniform mapping: 404 → domain 404, 5xx → retryable 503, and the chosen outcome for refusals/request errors, applied to all 29 methods | S2, D2, D3, D4 | test-first, inline | — |
| **T3** | The adapter's tests: one per outcome, over the stubbed client, asserting the status and the message | S2, D7 | test-first | — |
| **T4** | Decide `KeycloakGroupEventListener`'s broad catch: swallow a refusal with a `warn` (today's shape), or propagate it. Only reachable under B | D5, measurements | inline | — |
| **T5** | The handler and the permit pin (B), or the boundary handler (C) | S2, D5 | test-first | — |
| **T6** | Independent verification of the contract change, because Risk is item 3 | D1 | `gentle-ai-verify` | — |
| **T7** | The records: the `G11` pointer and this record's evidence log | W3 | inline | — |

## Gaps

| # | Gap | Note |
| --- | --- | --- |
| **X1** | **The mapping is pinned by unit tests over a stubbed client, so it pins what the adapter *does with* a status, not that RESTEasy raises that type for that status** | Measured today on one path only: a resource 403 really does arrive as `jakarta.ws.rs.ForbiddenException` (`G40`'s probe), and a 409 as `ClientErrorException` (the existing test fixture). The remaining status→type correspondence is the dependency's contract, not this record's measurement, and no test here will prove it — the same limit `F6` recorded for the pessimistic lock on the sibling record |
| **X2** | **The `WebApplicationException` family is wider than the statuses this unit names** | `RedirectionException` and any non-mapped `WebApplicationException` remain outside the buckets until a shape decides them; declared so a future "the 500 is back" report resolves in one grep |

## Log

- **L1** — *the user, 2026-10-16, verbatim*: «si» — closing the offer I made from the day's measurement, which
  was: «abrir la unidad del fix de mapeo de G11 (el catch que convierte ese 403 en un 403/503 nombrado), que
  es lo único con consecuencia real que la medición de hoy destapó». The offer itself came from the closed
  pair (A) push + PR of the docs repair and (B) this unit, whose origin measurement is recorded in
  `employee-access-provisioning`'s evidence log under 2026-10-16.
- **L2** — *2026-10-16, this pass*: **the unit was planned against the tree and nothing was implemented.**
  The 29-method escape matrix, the sealed-hierarchy check, the blast radius, the boundary's three handlers
  and the absent pins are the **Measurements** above, each with its `path:line`, and they are the reason the
  record is cut the way it is: the hole is 23 methods for 4xx and 29 for 5xx, so the unit's subject is the
  contract rather than a site. **What this pass deliberately did not do**: write a line of source, touch
  `usersadmin`, or close the shape — S2 stays unwritten until **T1**, because the statuses and messages it
  will carry are exactly what the user has to choose. **No source line changed.**
