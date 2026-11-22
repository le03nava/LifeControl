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

| ID | Spec | Route | Commit |
|----|------|-------|--------|
| T1 | S1, S4, S6 | inline — API `JwtDecoderConfig`: audience validator, missing `aud` rejected | `fix(security): require the audience claim in the API decoder` |
| T2 | S1, S4, S6 | inline — gateway `MultiIssuerSecurityConfig`: same validator | `fix(security): require the audience claim in the gateway decoder` |
| T3 | S2, S6 | inline — API authority from the expected client only | `fix(security): grant client authority only from the intended client` |
| T4 | S3, S6 | inline — `keycloak-setup.sh`: audience mapper on the clients that must mint `aud` | `fix(keycloak): mint the audience the decoders now require` |
| T5 | S3, S4 | inline — frontend-client token and the three existing rejection tests stay green | (inside T1/T2) |
| T6 | — | inline — `AGENTS.md` security boundary | `docs: record the audience requirement` |
| T7 | S5 | inline — `./gradlew test` + `spotlessCheck spotbugsMain` in both modules | (gate, no commit of its own) |

## Delivery

Two chained pull requests, because the authored diff is ~841 changed lines and the review budget is ~400:

| Slice | Commits | What it holds | Why the boundary is there |
|-------|---------|---------------|--------------------------|
| A | `0c88a6d1`, `95c8711b` | Realm mapper + API decoder | Self-consistent on its own: the realm starts minting the platform audience and the API starts requiring it. Merging A alone changes no behaviour for the others, because nothing else validates the claim yet |
| B | `be970ba0`, `77cd7dd8` | Gateway decoder + docs | The gateway is a second, independent copy of the same chain, and the docs describe both. B rides on A's mapper, which is why it cannot go first |

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
