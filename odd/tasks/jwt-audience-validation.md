# JWT audience validation

Both JWT decoders validate expiry, not-before and issuer, and neither validates `aud`, so any token
minted by any client of the realm is accepted by the API and by the gateway. This unit makes `aud`
mandatory in both services and narrows authority to the client that is meant to grant it.

**Status**: implemented — `aud` is mandatory in both decoders and client authority comes only from the
client that is meant to grant it. The realm-side mapper that makes real tokens carry the claim ships in
the same unit, because the code alone would reject every token that exists today.

## Specs

S1. `aud` is mandatory. A token whose `aud` does not contain the service's expected audience answers
401 on `/api/**`, and a token with a missing `aud` is rejected rather than accepted — the exact
wording of the requirement: *"with a missing `aud` treated as a rejection rather than as a pass"*.

S2. Authority comes from the intended client. A token minted by another client of the same realm whose
`resource_access` carries a colliding `lc-admin` must not reach an `lc-admin` endpoint.

S3. The public frontend client keeps working: *"A valid token from the public frontend client keeps
working on every existing endpoint, so the current UI is not broken by the change."*

S4. *"Expiry, not-before and issuer rejection each keep a test, and the new audience test fails if the
validator is removed."*

S5. *"`./gradlew test` passes in both `life-control-api` and `api-gateway`."*

S6. Expected outcome, verbatim: *"Both services accept a token only when its `aud` claim names them,
and API authority derives from the client that is meant to grant it."*

## Tasks

Each task names the commit that holds it **by subject**, not by SHA: a SHA of this branch stops being
an ancestor after the next amend or rebase, and still resolves as an object, so it reads as evidence
when it is not (`0be840d4`, *cite the slice's commits by subject, not by branch SHA*).

| ID | Spec | Route | Commit |
|----|------|-------|--------|
| T1 | S1, S4, S6 | inline — API `JwtDecoderConfig`: audience validator, missing `aud` rejected | `fix(security): validate the audience and narrow client authority in the API` |
| T2 | S1, S4, S6 | inline — gateway `MultiIssuerSecurityConfig`, `jwtValidator()` extracted as the test seam | `fix(security): require the audience claim in the gateway decoder` |
| T3 | S2, S6 | inline — client roles from `keycloak.authority-client-ids`, never from `azp` | same commit as T1: one file, one behavior boundary |
| T4 | S3, S6 | inline — `keycloak-setup.sh`: `api-audience` mapper, outside the tenancy block | `fix(keycloak): mint the platform audience the decoders require` |
| T5 | S3, S4 | inline — frontend token keeps working; `exp`, `nbf` and issuer each keep a biting test | inside T1/T2 |
| T6 | — | inline — `AGENTS.md` root and `life-control-api/AGENTS.md` | `docs: record the audience requirement` |
| T7 | S5 | inline — `./gradlew test` + `spotlessCheck spotbugsMain`, both modules | gate, no commit of its own |
| T8 | S1, S2 | inline — verifier findings: pin the three copies of the literal, add the claim edges | `test(security): pin the audience literal and the claim edges` |

## Delivery

Two chained pull requests, because the authored diff is ~841 changed lines and the review budget is ~400:

| Slice | Commits (by subject) | What it holds | Why the boundary is there |
|-------|----------------------|---------------|--------------------------|
| A | `fix(keycloak): mint the platform audience…`, `fix(security): validate the audience and narrow client authority in the API` | Realm mapper + API decoder | Self-consistent on its own: the realm starts minting the platform audience and the API starts requiring it. Merging A alone changes no behaviour for the others, because nothing else validates the claim yet |
| B | `fix(security): require the audience claim in the gateway…`, `docs: record the audience requirement`, `test(security): pin the audience literal…` | Gateway decoder + docs + the pin | The gateway is a second, independent copy of the same chain, and the docs describe both. B rides on A's mapper, which is why it cannot go first |

## Log

L1. User request, verbatim: *"no, revisar los issues pendientes y ve con cual continuar"*, followed by
the choice *"#893 — validar la audiencia del JWT"*.

L2. Requirement source: GitHub issue #893, *"fix: neither JWT decoder validates the token audience"*.
Its Problem section names `JwtDecoderConfig.java:40-46` and `MultiIssuerSecurityConfig.java:33-38` as
the two decoders built from `JwtValidators.createDefault()` plus an issuer allow-list, and warns that
`aud` is *"the claim that exists precisely so a token minted for one resource is not accepted by
another"*.

L3. The issue states its own blast radius is not verifiable from code — *"The blast radius depends on
realm state — which clients exist and which carry a colliding role name — and that is not verifiable
from the code. The fix should therefore not depend on that answer."* — so the fix is written to be
correct regardless of which roles exist in the realm at the time.

L4. Evidence, RED observed before the fix (`JwtDecoderConfigTest`, API): `rejects a token that does not
name the API in aud` and `rejects a token whose aud claim is missing` both failed with
`Expecting value to be true but was false`; `ignores a colliding role carried by another client of the
same realm` failed with `Expecting [ROLE_lc-admin] not to contain [ROLE_lc-admin] but found
[ROLE_lc-admin]`. GREEN after: 38 tests in `com.lifecontrol.api.config.security`, 0 failures.

L5. Gates, observed: `./gradlew test spotlessCheck spotbugsMain` exits 0 in both `life-control-api`
(18m15s) and `api-gateway`. The offline script suites are green too — `docker/scripts/tests` 32 suites,
1837 assertions; `.github/scripts/tests` 18 suites, 530 assertions — after
`keycloak-setup.test.sh` (92 assertions) was updated for the new mapper: three of its cases broke
because a fully provisioned realm is no longer five mappers, and its case 3 now pins 5 tenancy + 1
audience + a total of 6 rather than a lowered total.

L6. Not run, and why: `life-control-app-angular` has no `node_modules` in this worktree, so its lint and
unit gates were not executed here. The Angular change is the `aud` field of the Playwright token fixture
only, and no browser test decodes a real JWT.

L7. Deliberate deviation from the issue's wording: the issue says a token minted by another client must
not reach an `lc-admin` endpoint. Two mechanisms deliver that and both are in: the audience check
refuses a token that does not name the platform, and the converter reads client roles only from
`keycloak.authority-client-ids` — so even a token that carried the audience inherits nothing from a
foreign client's `resource_access`. Realm roles stay realm-wide on purpose: they belong to the user,
not to the client that minted the token.

L8. Independent verification (Risk item 2), verdict by spec: S1 PASS, S2 PASS, S4 PASS, S6 PASS, and both
proven by mutation — deleting `new JwtAudienceValidator(…)` from either chain fails the two audience
cases, and restoring the `azp` lookup fails the two client-role cases. S3 UNPROVEN on its live half: the
literal agrees across the four copies (checked by `diff`), but no real Keycloak-minted token was
decoded, because no stack may be run from there. S5 UNPROVEN by the verifier — the 18-minute full suite
is the parent's observation (L5), not its own. Three findings it raised, all fixed in T8: the component
doc `life-control-api/AGENTS.md` still taught `resource_access.<azp>.roles` (a stale rule this unit
created); this record cited a slice-B commit that this amend had already orphaned; and nothing pinned
the four copies of the audience literal to each other. The pin is now `AudienceDefaultConsistencyTest`,
and the claim edges (`aud: []`, a single-string `aud`, a case-differing `aud`, an emptied authority list,
a malformed `resource_access`) are pinned in the two existing test classes rather than in a probe file.

L9. Deployment hazard, unchanged and worth reading twice: if this lands and `keycloak-setup.sh <env>` is
**not** re-run, the public client carries no audience mapper, Keycloak keeps minting
`aud: ["account"]`, and **every** token is rejected with `401 invalid_token` on `/api/**` and on every
gateway route. Nothing gates the deploy on it — `deploy.sh` never mentions `keycloak-setup.sh`, and
`health` does not read the realm — so the recovery is manual and the only warning is prose. It is
recorded rather than fixed here because wiring the realm provisioning into the deploy path is its own
decision, not a property of this unit.

L10. Delivery, 2026-11-22: slice A is PR #1026 (base `main`, head `fix/jwt-audience-validation-part-1`,
`Refs #893` plus the chain declaration) and slice B is PR #1027 (base the slice-A branch, `Closes #893`).
Both carry `type:bug` and `java`, and every check is green on both: `Lint & Build`, `Unit tests (1)` and
`(2)`, `Coverage`, `Test`, `Static analysis`, `Shell & Artifact Integrity`, `Check`, `A linked, approved
issue` and `Exactly one type:* label`. One gateway check failed first on
`Received status code 429 from server: Too Many Requests` resolving `spring-cloud-dependencies` from
Maven Central — a repository rate limit, not this diff — and passed on re-run. The realm half was
applied to dev the same day: `keycloak-setup.sh dev` created the `api-audience` mapper
(`4c1a1e16-e6ca-4d3b-85b7-00be8885719a`) and verified it, and a second run reported `exists and matches
the declared aud`, so the provisioning is idempotent in practice as well as in the suite.
