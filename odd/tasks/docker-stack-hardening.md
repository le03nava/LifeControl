**Status**: deferred — this record covers the container and script hardening of issue #897 across two
review slices. The scripting and image half (S1-S3, S8) is the first slice; container hardening (S4,
S5) and the secrets-mode criteria (S6, S9) are deferred, the latter to the mechanism decision in L4.

## Specs

Quoted verbatim from issue #897 ("chore: harden the compose stack and the four scripts that cannot
fail safely").

S1. All four scripts declare `set -euo pipefail`, and shellcheck `--severity=warning` over
`.github/scripts/` and `.githooks/` still passes.

S2. `docker stop` on the gateway finishes inside its stop grace period and the JVM receives SIGTERM
(exit status is not 137).

S3. Both Java modules ship a `.dockerignore`, and the build context no longer contains `build/`.

S4. `docker compose config` for base, override and prod reports `cap_drop: [ALL]`,
`security_opt: [no-new-privileges:true]` and, for every image that tolerates it, `read_only: true`
with a `tmpfs` for `/tmp`.

S5. Prometheus and Tempo no longer declare `user: "0:0"`; their data directories are created with a
numeric uid during setup.

S6. A materialized secret file is no longer readable by a second local account, and containers still
read it.

S7. `./docker/scripts/deploy.sh dev start` and `dev health` pass.

Problem statements the specs above do not fully carry, quoted verbatim:

S8. `api-gateway/Dockerfile:65` is `ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]` with no
`exec`, so SIGTERM lands on the shell and every deploy waits out the stop timeout before SIGKILL,
dropping in-flight requests. `life-control-api/Dockerfile:56` already does it with `exec java …`. And
`:48` hides a failed package install behind `|| true`.

S9. `Materialized secret files are world-readable.` `setup-env.sh:163-174` writes them `0444`, so any
local account can read the database owner credential that ADR-0005 says must never reach the
connection pool.

## Tasks

| ID | Spec | Route | Commit | State |
| --- | --- | --- | --- | --- |
| T1 | S1 | inline | 1 | done |
| T2 | S2, S3, S8 | inline | 2 | done |
| T3 | S4, S5 | second slice, second branch off `origin/main` (L9) | - | pending |
| T4 | S6, S9 | deferred to the secrets-mode decision (L4) | - | deferred |

## Log

L1. User: "puedes revisar el issue 897". Review found the diagnosis correct and measured, and three
acceptance criteria either not implementable as written or not verifying what they promise (L3).

L2. User: "ok revisala y arranca". Authorizes implementation starting from T1, after reconciling the
two prior memory records on this subject: obs 2035 (a previous adversarial review of a hardening diff
over these same four scripts) and obs 2041 (`sdd/docker-config-homologation`, the entrypoint-wrapper
secrets model).

L3. Review findings folded into the plan. (a) S1's shellcheck surface is wrong: the four scripts live
in `docker/scripts/`, which the `Shell & Artifact Integrity` job lints, not the guard over
`.github/scripts/` and `.githooks/`; as written the criterion passes without linting a single changed
file. The test asserts the behavior of each script's own option line instead. (b) S2's "dropping
in-flight requests" is not fixed by `exec`: `api-gateway` declares no `server.shutdown` and no
`spring.lifecycle.timeout-per-shutdown-phase`, so Spring Boot's default `immediate` still cuts
in-flight requests. `exec` alone satisfies "exit status is not 137"; the drain claim does not, and
`stop_grace_period` is orchestration, which the issue's Non-goals excludes. Scope of T2 is therefore
`exec` + the hidden failure, and the drain is left recorded, not claimed. (c) S4's "for every image
that tolerates it" is not falsifiable; T3 carries a per-service table instead of a blanket flag.
(d) `api-gateway/Dockerfile:48` installs `curl`, and the same file's `HEALTHCHECK` uses `wget`: the
dependency is dead weight, so T2 removes the line instead of un-hiding its failure.

L4. Open decision, blocks T4. S6 says a materialized secret must stop being readable by a second
local account while containers still read it. Four consumers read the same file as four different
uids (postgres 999, keycloak and grafana 1000, redis 999, loki 10001) through a read-only bind mount,
and two suites already require the `0444` literal: `docker/scripts/tests/secrets-directory.test.sh:200`
and `validate-env-strict-pins.test.sh:90`, alongside `AGENTS.md:565` and `README.md:220`/`README.md:226`.
The mechanism has to be reconciled with the entrypoint-wrapper model already decided in obs 2041
(wrappers `cat` the file and `exec` the image entrypoint; they run as the image user, so the file
still has to be readable by that uid). Not started until the human picks uid ownership per service,
a shared group with `0440`, or a change of mount mechanism.

L5. Reconciliation with obs 2035: its main learning, `((ERRORS++))` aborting under `set -e` when the
counter is 0, no longer exists anywhere in `docker/scripts/`. Nothing to carry into T1 from it.

L6. Evidence, T1: `docker/scripts/tests/script-strictness.test.sh` added (39 assertions). RED measured first —
20 failures, five per script: the option line plus an unset variable reaching the line as `VALUE=[]` and a
failing pipeline surviving. GREEN after the four one-line edits: 39 passed, 0 failed. The deletion-guard
cases were already green before the change, because they run under their own explicit `set -euo pipefail`:
`volumes_root_env_dir_name` aborts with `ENV` unset and still answers `volumes-dev` with it declared.
`validate-env.sh dev` runs to completion under `-u` and reports its usual 7 errors on a tree where the
secrets were never materialized, so the flag change aborts nothing that used to run. shellcheck
`--severity=warning -x` with the CI pin (`koalaman/shellcheck:v0.11.0`, the local binary is absent) is
clean over the four scripts and the new suite. Whole-tree runs: `docker/scripts/tests/` 30 suites, 0 failed;
`.github/scripts/tests/` 18 suites, 0 failed — the first attempt failed the ODD header audit because this
record had no `**Status**:` paragraph, which L7 records.

L8. Evidence, T2: `dockerfile-hardening.test.sh` added (20 assertions). RED measured first — 5
failures: the gateway entrypoint matched no `exec java`, its `sh -c java` form was still there, one
package install was hidden behind `|| true`, and neither module shipped a `.dockerignore`. GREEN after
the three edits. The `.dockerignore` trap is proven twice, not argued: a throwaway `FROM scratch` tree
with `build/libs/app-1.0.jar`, `build/classes/` and `.gradle/caches/` builds with the shipped ignore
file and fails with `"/build/libs/app-1.0.jar": not found` under a blanket `/build/` control. So S3's
literal "the build context no longer contains `build/`" is not satisfiable while `${JAR_FILE}` lives
inside that tree; the context shrink is. `curl` was dead weight and not a lost capability: the
gateway's HEALTHCHECK probes with `wget`, and the only other `curl` mentions in the tree are README
examples run from the host. Runtime half of S2 is deliberately not claimed: the `exec` is asserted
statically, and the "exit status is not 137" measurement needs the image rebuilt, which belongs to T3's
integration run. Whole-tree runs: `docker/scripts/tests/` 31 suites, 0 failed; `.github/scripts/tests/`
18 suites, 0 failed; shellcheck `--severity=warning -x` clean on the new suite.

L9. Slice boundary, measured after T2: 455 authored changed lines across two commits, past the ~400
line review budget. The first slice is T1 and T2 — script strict mode, the gateway `exec`, the dead
`curl`, the two `.dockerignore` files. T3 (compose hardening, numeric uids, the `chown` that has to
abort) is a second branch off `origin/main` and a second PR: it shares no file with this one, and it
is the slice that has to run `docker compose config` for the three file combinations and recreate
the dev stack, which the shared dev environment feels. Nothing in this slice depends on it.

L10. Delivery evidence for this slice, 2026-11-20: PR #1023 (`chore/docker-stack-hardening` →
`main`), commits `d64a943b` (scripts) and `ce0066af` (images). Checks: `Static analysis` pass 50s,
`Test` pass 12m44s, `Check` (gateway) pass 1m41s, `Shell & Artifact Integrity` pass 40s, `Exactly one
type:* label` pass 51s, and `A linked, approved issue` **fails in 5s** because #897 carries no
`status:approved` — the contributor checklist's first box is unticked for the same reason, rather than
attested without evidence. That gate is the human's act; it was flagged before the first write and again
before the PR.

L7. The record's own header had to open with a verdict before the first commit: the audit rejected a
header that only described the branch. `**Status**: deferred` with S6/S9 named as the deferred half is the
honest token while T4 waits on L4; the Tasks table carries the per-task state, which the audit reads
outside the header paragraph and accepts. No unguarded expansion was found that a normal invocation
reaches: `_common.sh:23-26` expand a bare `$1`, but no call site passes zero arguments.
