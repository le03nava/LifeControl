# Deploy gates on the realm

`deploy.sh <env> start` never runs `keycloak-setup.sh`, and since the audience validation landed the realm
is what makes every token acceptable: without the `api-audience` mapper, Keycloak mints `aud: ["account"]`
and both decoders answer `401 invalid_token` on everything. `health` does not read the realm, so that deploy
reports green.

**Status**: implemented — the deploy path converges the realm's audience mapper before it reports healthy,
and `health` is red when the realm does not mint what the running decoders require.

## Specs

S1. *"`./docker/scripts/deploy.sh dev start` returns 0 on a realm that lacks the mapper, and the realm
carries it afterwards: reading the mapper back (`protocol-mappers/models`) or decoding an access token both
show the configured audience."*

S2. *"A deploy against a realm whose mapper is absent or drifted **never** reports success while the realm
is unconverged: it converges it, or it fails the command naming `keycloak-setup.sh` and the reason."*

S3. *"`./docker/scripts/deploy.sh <env> health` is red — not merely a warning — when the running decoders
require an audience the realm does not mint. Its current verdicts over containers, Prometheus and Tempo
are unchanged."*

S4. *"The deploy order is stated once and enforced by the script: whatever moves, `keycloak-setup.sh` runs
after Keycloak is up and before the health gate that would otherwise pass."*

S5. *"An offline suite in `docker/scripts/tests/` freezes both halves, in the style the other suites already
use: the deploy path calls the realm step, and the health gate fails on an unconverged realm. Deleting
either call fails that suite."*

S6. *"`for s in docker/scripts/tests/*.test.sh; do bash "$s"; done` exits 0 for every suite, including the
ones that assert `deploy.sh`'s current step order."*

## Design decisions

D-a. **Two narrow modes instead of running the whole provisioning script.** `keycloak-setup.sh` gains
`--ensure-audience` (create the mapper if absent, verify it if present — what the inline block already
does) and `--verify-audience` (read-only, exits 1 when absent or drifted). `deploy.sh start` calls the
first, `health` calls the second. The plain invocation keeps converging everything, so no existing caller
changes behaviour, and a deploy does not start converging SMTP or realm roles as a side effect — the
non-goal this design has to respect.

D-b. **The declaration stays in one place.** Both modes reuse `verify_audience_mapper` and the
`API_AUDIENCE`/`AUDIENCE_MAPPER` constants already in the script; a drift check that exists in two copies
is the drift it is meant to catch.

D-c. **Order inside `start_services`**: prod validation → postgres → `db-roles.sh` → all services →
**wait for the keycloak container to be healthy** → `--ensure-audience` → `show_status`. The wait is
required: `compose_run up -d` returns before Keycloak answers, and a converge against a container that is
still booting would fail the deploy for a race, not for a reason.

D-d. **`health` runs the read-only mode**, never the converging one: a health check that writes is a health
check that can mask the very state it is asked to report.

## Tasks

| ID | Spec | Route | Commit |
|----|------|-------|--------|
| T1 | S5 | worker — new offline suite `docker/scripts/tests/deploy-realm-gate.test.sh`, RED first | `test(docker): freeze the realm step in the deploy path` |
| T2 | S1, S2, S4 | worker — `keycloak-setup.sh` modes + `deploy.sh` order | `chore(docker): converge the realm audience in the deploy path` |
| T3 | S3 | worker — `health` red on an unconverged realm | same commit as T2 |
| T4 | S1-S4 | worker — docs: `AGENTS.md` Docker Scripts section | `docs(docker): state the realm step and the health verdict` |
| T5 | S6 | parent — full `docker/scripts/tests` glob, both modules untouched | gate |
| T6 | — | parent — independent verification (risk item 5) | gate |

## Log

L1. User request, verbatim: *"levanta un issue para eso"*, then *"apruebalas y comienza"*. The issue is
#1029, approved with `status:approved` before this unit started; #888 was approved at the same time and is
not part of this work.

L2. Requirement source: issue #1029, *"chore: a deploy that skips keycloak-setup leaves the whole stack
answering 401"*, whose five acceptance criteria are quoted verbatim as S1-S5 and whose sixth criterion (the
`docker/scripts/tests` glob) is S6.

L3. Evidence for the hazard, from the unit that created it: `odd/tasks/jwt-audience-validation.md` L9 and
the merge commits `3771c1e8` / `34c45d6d`. On dev the mapper was created on 2026-11-22 and a second run
reported `exists and matches the declared aud`, which is the measurement that makes converging from the
deploy path safe.

L4. Baseline read before designing: `deploy.sh` has 513 lines, `start_services` at `:178` (prod gate →
postgres → `db-roles.sh` → `compose_run up -d` → `show_status`), `health_check` at `:388` (image
revisions → wait for every service health → Prometheus/Tempo probes), and `keycloak-setup.sh` has no
check or dry-run mode today.

L5. 2026-11-23 — T1-T3 implemented. `docker/scripts/tests/deploy-realm-gate.test.sh` was written and run
first against the unchanged `deploy.sh` (14 passed, 25 failed: no realm step was invoked from either
command, which is the RED the suite exists for) and is green after the change (39 passed, 0 failed).
`keycloak-setup.sh` gained `--ensure-audience` and `--verify-audience`; the audience contract itself
moved above the container check and is now reached by `ensure_audience_mapper` (create-or-verify, what
the plain run always did) and `audience_mapper_is_declared` (read-only, 0 or 1) so there is still one
declaration and one drift check. `deploy.sh start` waits, bounded, for the `keycloak` container to report
healthy and then converges; `deploy.sh health` runs the read-only mode after its existing verdicts.
`keycloak-setup.test.sh` gained cases 19-24 for the two modes (98 → 129 assertions, no existing
assertion touched or relaxed) and the whole `docker/scripts/tests` glob is green.

L6. 2026-11-23 — the one consequence nobody has decided yet: both realm steps read
`docker/secrets/keycloak_admin_password`, which the `### Secretos` table pins at `0400` for the keycloak
container's uid, so a `deploy.sh start` or `health` run by an operator who is not root now fails at the
realm step with the credential guard's `sudo` instruction instead of reporting green. That is
fail-closed and it is how the script has always behaved, but it is a new requirement of the deploy path
and an operator has to know it.

L7. 2026-11-23 — L6 closed by decision, and the decision is that `deploy.sh <env> start` and
`deploy.sh <env> health` must NOT require root: both work for a non-root operator today, so making them
root-only would break the documented dev flow. The credential the two narrow modes need is already
mounted inside the container, so no new secret path was introduced — `configure_kcadm` now has one body
per source (`host` for the full run, untouched and still root-only; `container` for the narrow modes,
where the container's own shell expands `/run/secrets/keycloak_admin_password` so the secret never reaches
the host process, its argv, its environment or `docker inspect`). The full run's host reads moved behind
`require_host_secret` and into the `MODE = all` branch, so the narrow modes read neither
`keycloak_admin_client_secret` nor `smtp_password`. `keycloak-setup.test.sh` cases 25-26 pin it (129 →
143 assertions, none relaxed): with both host credential files `chmod 0000` the two modes still converge
and verify, the login is recorded as the in-container form with no password in the host argv, and the
plain run with the same files still refuses and still logs in from the host credential. Measured live on
the dev stack as uid 1000 (the script copied to /tmp with `SCRIPT_DIR` on the anchor's docker directory):
`--verify-audience dev` → exit 0, "Protocol mapper life-control-client/api-audience exists and matches
the declared aud"; `--ensure-audience dev` → exit 0, same verdict, no write. `deploy.sh` needed no change.
