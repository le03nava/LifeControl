# dev-edge-false-404

Fixes the edge that reported a committed write as `404`, and the verification procedure that could not
resolve the company it needs. Unit of work for `fix/dev-edge-false-404`, based on `origin/main` 69240b3c.

**Status**: implemented

## Specs

S1. **The gateway fallback and the dev time limiter.**
"Subir TIMEOUT_DURATION de dev (o alinearlo con prod) y hacer que el fallback responda 503 para cualquier
método, con tests en api-gateway. Es el defecto que hace que una escritura exitosa se reporte como fallida."

S2. **The verification procedure's default mode.**
"Corregir el orden del script (escribir company_id antes de listar, o documentar que exige
PROVISIONING_VERIFY_TOKEN) y su suite en docker/scripts/tests/."

S3. **The reference data the end-to-end run needs.**
"Crear posición y seniority level en dev para poder correr el flujo de punta a punta. Escribe más datos de
prueba."

## Tasks

| ID | S# | Route | Commit |
|---|---|---|---|
| T1 | S1 | inline | `fix(gateway): answer 503 from the fallback for every method` |
| T2 | S2 | `gentle-ai-worker` | `fix(docker): require the company the verify flow operates on` |
| T3 | S3 | inline, operational (no commit) | — |
| T4 | S1-S3 | inline, operational (rebuild + run) | — |

## Log

L1. User, verbatim: "puedes reconstruir y desplegar las imagenes con los ultimos cambios del proyecto"

L2. User, verbatim: "si correlo" — authorization to run `./docker/scripts/verify-provisioning-flow.sh dev`.

L3. Deploy of `dev` at `84820a7`: three application images `match`, all 15 containers healthy, Flyway up to
V76. Evidence: `deploy.sh dev start` exit 0; `deploy.sh dev version` exit 0.

L4. First verify run stopped at `GET /api/companies answered HTTP 503`. Cause, from trace
`5cbabcef9316bbc2e9154c23e5fb54dc`: the gateway's `companyServiceCircuitBreaker` TimeLimiter fired at
2004 ms while the API was still resolving the first JWT (JWKS fetch, cold). The API answered 200. Not a
code regression; the dev limiter is the shortest of the three environments.

L5. Second verify run: `GET /api/companies` answered 200 with zero companies. Measured as
`lifecontrol_bootstrap` (superuser): the dev datadir was created 2026-11-01 11:20 and holds no business
data (`companies=0`, `positions=0`, `seniority_levels=0`, `departments=0`, `countries=3`), while the
Keycloak realm still holds its 8 users. No RLS involvement: the count is zero at superuser level.

L6. User decision, verbatim: selected "Creo una empresa de prueba por API y reintento".

L7. Seeding one company exposed the real defect. `POST /api/companies` through the gateway returned **404**
while the API committed **201 CREATED**: gateway forwarded at 19:24:33.621, TimeLimiter fired at 2004 ms
(19:24:35.628), API answered 201 at 19:24:36.697. The row exists
(`e0b1a3f9-124d-40e4-af0f-edcf03ad2f08 | VERIFYFLOW | Verify Flow Co | verifyflow.test | MXN`). The
security filter chain took only 124 ms that time, so this is not a cold start: a warm company creation
(~2.9 s) exceeds the dev limiter. Because `Routes.fallbackRoute()` matches `.GET` only, the fallback
forward of a non-GET request lands on the static resource handler and answers 404 instead of 503.

L8. Third verify run still saw zero companies. Cause: `CompanyService.claimedCompaniesPage` returns an empty
page for a token with no `company_id` claim, by design ("`isAdmin()` is not consulted here at all: the role
guards the endpoint, it does not widen the tenant"). Proven both ways with a throwaway operator: no claim →
`totalElements: 0`; same operator with `attributes.company_id` → `totalElements: 1`. The script creates its
operator without attributes and writes `company_id` only after resolving a company, so its default mode
cannot work. Only `PROVISIONING_VERIFY_TOKEN` (a token that already carries the claim) can.

L9. User decision, verbatim: selected all three continuations — "Arreglar los 2 defectos del gateway +
tests", "Arreglar el modo por defecto del verify", "Sembrar los datos de referencia y terminar el e2e".

L10. Base note: the unit branches from `origin/main` 69240b3c (#780), which is newer than the `84820a7` the
dev stack was deployed from; `main` advanced between the deploy and the unit creation.

L11. **The limiter fix was aimed at the wrong file, and inspecting caught it.** After changing
`application-dev.properties` to 5s, `docker exec lifecontrol-api-gateway printenv TIMEOUT_DURATION` still
answered **2s**: `docker/docker-compose.yml:442` passes `TIMEOUT_DURATION=${TIMEOUT_DURATION:-3s}` from the
materialised `docker/.env.<env>`, and a process environment variable outranks a profile properties file.
The tracked source of the value is `docker/.env.template` (`2s` before this unit) and the prod override is
literal in `docker/docker-compose.prod.yml` (`5s`). `TimeLimiterParityTests` was rewritten to read those
two tracked files and to fail when `application-dev.properties` and the template disagree — the assertion
whose absence let the first version pass while the container ran 2s. RED observed for it: with the
properties temporarily set to 4s against a 5s template,
`TimeLimiterParityTests.theTwoDevDeclarationsAgree` failed (`2 tests completed, 1 failed`); GREEN after
restoring 5s, and the whole `:api-gateway:test` suite passes.

L12. `docker/.env.template` and both `.env.dev` copies are outside what this agent may edit (the safety
policy blocks env files). The user made the edit on request: `TIMEOUT_DURATION=5s` in
`docker/.env.template` and in the anchor's `docker/.env.dev`. Verified in the running container after
`deploy.sh dev up`: `printenv TIMEOUT_DURATION` → `5s`, all services healthy.

L13. T3, reference data seeded through the API with a claim-bearing throwaway `lc-admin` operator, all
**201**: department `c02226a8-125b-46aa-8af7-47c9deaf187c` (`ADMIN`), position
`ef572bdb-4b6c-49d2-879e-556e714c174a` (`ADMIN`), seniority level `98f94700-5dde-4e65-8458-52044be278dc`
(`JR`). A position requires a `departmentId` and `departments` was empty too, so the department came first.

L14. T4, end to end, against the deployed stack with the fixes:
`PROVISIONING_VERIFY_COMPANY_ID=e0b1a3f9-… ./docker/scripts/verify-provisioning-flow.sh dev` → **exit 0**,
"Access-provisioning flow verified end to end for dev". Evidence from the run: operator token re-obtained
with `company_id`; company read by id (`email domain verifyflow.test`); employee created; worker enabled
(`workerEnabled=true`); contract created and the producer enqueued `ACTIVATE` in the same transaction; task
reached **APPLIED**; employee linked to its Keycloak account; invitation in Mailpit with the link base
serving Keycloak (`http://localhost:8181`); `requiredActions` exactly `VERIFY_EMAIL + UPDATE_PASSWORD`; the
account's roles match the position template (none); all five `company_*` attributes agree with the
derivation. Cleanup ran: contract and assignment closed, employee soft-deleted, both Keycloak accounts
deleted.

L15. Residue left in dev on purpose: the company `VERIFYFLOW`
(`e0b1a3f9-124d-40e4-af0f-edcf03ad2f08`, domain `verifyflow.test`), the department, position and seniority
level above. They are what makes the flow runnable again without re-seeding. Nothing else survives: every
throwaway operator and probe account was deleted.
