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

## Delivery

One pull request, and the size is declared rather than hidden: **2251 lines, of which 618 are production and
1633 are tests** (a 548-line new suite, a 751-line new suite, and 315 lines of new cases in three existing
ones).

The review budget this repository uses is ~400 authored lines. A chain was the first plan, and it does not
reach it: the production behaviour is entangled across the same two scripts and their suites, so the only
splits available are ~640 / ~1450 / ~200 — the middle one is nearly four times the budget on its own. What
the budget protects is a reviewer's attention on the behaviour, and that is 618 lines here: both new
suites exist because S5 requires the freeze, not because the change is wide.

Read it in this order: `keycloak-setup.sh` (the two modes and the container resolution), `deploy.sh` (the
order, the wait, the health gate), then the suites; `AGENTS.md` and this record last.

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

L8. 2026-11-23 — independent verification found a blocker the unit and its author both missed, and it is a
different container concern from L6/L7: **neither realm script could address the keycloak container outside
dev.** `keycloak-setup.sh` computed `CONTAINER="${COMPOSE_PROJECT_NAME}-keycloak"` and every call used
`docker exec "$CONTAINER"`, and `deploy.sh`'s wait read the health from the `{{.Health}}` column of
`compose ps`. Measured in the tree: `docker/docker-compose.yml`'s `keycloak:` block (lines 161-232) declares
**no** `container_name`; `docker/docker-compose.override.yml:28` — the dev-only file — is the only place
that sets `container_name: ${COMPOSE_PROJECT_NAME:-lifecontrol}-keycloak`; and
`docker/docker-compose.prod.yml:87` gives keycloak `deploy.replicas: 2`, still with no `container_name`. So
in staging and prod compose names the container `<project>-keycloak-1` (and `-2` in prod) and every
`docker exec` targeted something that does not exist. The bug is pre-existing — `keycloak-setup.sh` was
already unusable there — but this unit surfaces it, because `deploy.sh staging|prod start` would now abort
at the realm step and `deploy.sh <env> health` would be permanently red. The health read had the same shape
of defect on its own: with two replicas the column prints two lines, and `tr -d '\r\n'` turned them into
`healthyhealthy`, which matches no state and waited out the whole bound on a healthy stack.
**Resolution, in both scripts:** the id is asked of compose (`docker compose <the file stack that
environment loads> --env-file "$ENV_FILE" ps -q keycloak`), which is the only thing that knows how a project
names what it runs; the health is then read **per id** with `docker inspect -f '{{.State.Health.Status}}'
<id>`, and any one healthy replica is enough. `keycloak-setup.sh` takes the first id because prod's two
replicas share one realm database, so provisioning through either provisions both. Three failures are now
told apart instead of collapsing into one timeout: "compose reports no `<service>` container in this
project" (a stack that was never started), "the container exists but is not running" (the `-a` probe), and
"it did not report healthy after N attempts". **The compose files were not touched** — whether prod should
run two keycloak replicas is a real question and not this unit's. **Frozen by**
`docker/scripts/tests/realm-container-identity.test.sh` (9 cases, 42 assertions, offline, stub-driven): the
three compose shapes asserted through the argv each script builds, every exec target equal to the id compose
answered (RED before the fix: `15 passed, 27 failed`, the first failure being "keycloak-setup.sh derives
CONTAINER from COMPOSE_PROJECT_NAME again"), two healthy replicas passing the wait, the second-replica case
that a single-project-wide health value would fail, "no container" and "exists but not running" each naming
their cause instead of a timeout, and static checks that neither script rebuilds the name or reads the health
column again. `keycloak-setup.test.sh` stayed at **143 assertions** (its shim learned the compose lookup and
records the id; nothing weakened, nothing deleted), `deploy-realm-gate.test.sh` stayed at **39** (its stub
learned `ps -q`/`ps -aq`/`docker inspect`; **one assertion needle followed the call** — "Keycloak answered
before the realm step" now names the `docker inspect` read instead of the removed `ps --format '{{.Health}}'`
column, same claim, same count), `db-roles.test.sh` 190, and the whole `docker/scripts/tests` glob is 35
suites / 2049 assertions / every rc=0. **Three stale citations repaired**, each to the range measured after
the edits: `validate-env.sh:309` and `tests/secrets-directory.test.sh:299` cited `keycloak-setup.sh:262-278`
for the SMTP guard, which resolves to the audience block — the guard is at `keycloak-setup.sh:554-574`; and
`tests/env-defaults.test.sh:28` cited `deploy.sh:173-179` for the prod gate, which is now
`deploy.sh:255-262`. The SMTP citation was **already stale before this unit** (the guard sat at 512-533 at
HEAD as well, so the earlier drift came from the audience block, not from here), and no suite checks
`docker/scripts/*.sh:<line>` citations, so none was invented. Measured live on the dev stack as uid 1000
(the script copied to /tmp with `SCRIPT_DIR` on the anchor's docker directory): `--verify-audience dev` →
exit 0, resolving `dbc1e8c2…` through compose and reporting "Protocol mapper
life-control-client/api-audience exists and matches the declared aud".

L9. Re-verification of the identity fix: F1 closure, the replica case, the pre-sleep diagnosis, the three
citations and the scope of the diff all MET, with 35 suites and 2049 assertions green. One finding stood:
the ordering assertion in `deploy-realm-gate.test.sh` case 1 had been narrowed to the bare needle
`inspect `, so an unrelated `docker inspect` anywhere in `start_services` satisfied it while Keycloak's
health was never read, and the suite stayed green. Restored to pin `inspect <the id compose answered>`,
which is the claim it always meant, plus an assertion naming the id. The suite is now 40 assertions and
the verifier's own false-positive control fails 18 of them, the ordering one among them.
