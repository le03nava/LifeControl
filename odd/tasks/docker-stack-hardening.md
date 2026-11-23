**Status**: implemented — S1-S5 and S8 are in `main` (T1-T3, two review slices), and T4 (S6, S9) is
implemented by the change this entry closes: every materialized credential file is readable only by the
account that reads it, the table that decides the shape lives in `AGENTS.md` and is asserted by a suite,
and the mechanism for the three credentials with two readers is one numeric gid per credential carried by
`group_add`. What the record does **not** claim is S7, a live `deploy.sh dev start`/`dev health` over the
new shapes: that needs the shared dev stack, and L21 says exactly what was measured instead.

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
| T3 | S4, S5 | second slice, branch `chore/compose-hardening` (L12) | - | done |
| T4 | S6, S9 | third slice, branch `chore/secret-file-ownership`; one gid per shared credential plus `group_add`, `0400` where one account reads (L21) | 3 | done |

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
`main`), carried by two commits — `chore(scripts): fail closed on unset variables and failing pipes`
and `chore(docker): make java PID 1 in the gateway and shrink the java build contexts`. Checks:
`Static analysis` pass 50s,
`Test` pass 12m44s, `Check` (gateway) pass 1m41s, `Shell & Artifact Integrity` pass 40s, `Exactly one
type:* label` pass 51s, and `A linked, approved issue` **fails in 5s** because #897 carries no
`status:approved` — the contributor checklist's first box is unticked for the same reason, rather than
attested without evidence. That gate is the human's act; it was flagged before the first write and again
before the PR.

L11. The first version of L10 cited the two commit hashes of this branch, and `odd-citations.test.sh`
rejected them: a citation names content or the merge commit, never a branch's SHA, because a later
rewrite leaves it naming a commit that is not in `main` while still resolving as an object. The
commits are named by subject instead. The guard job is called `Exactly one type:* label` but runs
every suite in `.github/scripts/tests/`, so this read as a label failure in `gh pr checks` until the
suite itself named the record.

L18. Independent verification of T3, 2026-11-22: every claim confirmed, no merge-blocking finding. The
rendered `docker compose config` for the three file combinations matched the frozen table row by row;
`require_dir_owner` was driven behaviourally — an already-owned directory succeeds **without** calling
`chown`, a missing one is created, a `chown` that cannot succeed exits 1 naming both the directory and
the `sudo chown -R` command, and no warning-and-continue path is left in `setup-env.sh`; one `cap_add`
block was re-measured with a throwaway postgres reaching "database system is ready to accept connections"
as uid 999 under `--read-only` with the four capabilities and no `FOWNER`; 32 suites, 0 failed. The one
advisory — that the `AGENTS.md` row stated `web-app`'s verdict as fact while this record disclosed it as
source-derived — is fixed in that same row.

L19. Integration finding, 2026-11-22, from the first real `deploy.sh dev start` after the merge: Tempo came
up in a restart loop (`tempo(restarting)`, `permission denied` on a trace block) while every other service
was healthy, and `dev health` failed the run. The cause was this record's own procedure, not the compose
file: the ownership step ran against a **live** stack, so the old Tempo container — still root until it was
replaced — wrote one more root-owned block at `22:05:42`, after the `chown` and before the new container
started. `require_dir_owner` then reported `already owned` on the next run, because it compared the
directory's own owner and never its contents. Both halves are fixed in `fix/setup-env-chown-order`: the
helper now walks the tree (`find "$dir" ! -user <uid> -print -quit`, 0.01 s over 1,718 files), re-reads
after a `chown` instead of trusting its exit status, and its abort names the order that works —
`deploy.sh <env> stop`, the `chown`, `deploy.sh <env> up` — because a `chown` against a running stack
leaves fresh foreign-owned files behind. `compose-hardening.test.sh` gained the order assertion and a static
one on the tree check, which cannot be driven as an ordinary user: fabricating an entry owned by a second
uid needs root. Measured after the fix: `sudo chown -R 10001:10001` with the stack stopped, then
`dev health` green on all 15 services, and `docker stop lifecontrol-api-gateway` closed in **1 second** with
`ExitCode=143` (SIGTERM, not 137) — S2's live half, which no offline suite could establish.

L7. The record's own header had to open with a verdict before the first commit: the audit rejected a
header that only described the branch. `**Status**: deferred` with S6/S9 named as the deferred half is the
honest token while T4 waits on L4; the Tasks table carries the per-task state, which the audit reads
outside the header paragraph and accepts. No unguarded expansion was found that a normal invocation
reaches: `_common.sh:23-26` expand a bare `$1`, but no call site passes zero arguments.

L12. T3 started by measuring every image instead of carrying the recon's verdicts, because a wrong
verdict in either direction is a restart loop: a `cap_drop` or a `read_only` an image does not tolerate,
or a flag left off an image that tolerates it. Default users, from `docker image inspect -f
'{{.Config.User}}'` on the pinned tags: prometheus `nobody` (65534), tempo `10001:10001`, loki `10001`,
grafana `472` (the compose declares `1000:1000`, pre-existing and untouched), keycloak `1000`, redis and
postgres unset (root, then dropped by their entrypoints), redis-exporter `59000:59000`, both exporters
`nobody`, mailpit unset. Everything else came from throwaway `docker run --rm` containers with
`mktemp -d` bind directories, never from the shared dev stack, which was not started, stopped, rebuilt
or otherwise touched.

L13. Five recon verdicts were wrong or unproven, and are the ones this slice changed. (a) **keycloak
cannot be `read_only`**: `kc.sh start-dev` re-augments the server image and writes
`/opt/keycloak/lib/quarkus/transformed-bytecode.jar` — measured `java.nio.file.FileSystemException:
/opt/keycloak/lib/quarkus/transformed-bytecode.jar: Read-only file system` — and the recon's tmpfs on
`/opt/keycloak/data` and `/tmp` does not cover that path, while a tmpfs over it would hide the jars the
build step reads. The prod command `start` announces "Changes detected in configuration. Updating the
server image." and wrote nothing to `/opt/keycloak/lib` in 75 s, but it never reached a finished start
(no reachable database), so prod stays writable instead of being frozen on partial evidence. (b)
**grafana needs `/tmp` and not `/var/log/grafana`**: without `/tmp` every plugin dies on `open
/tmp/pluginN: read-only file system`; logs go to stdout, so `/var/log/grafana` was not declared. (c)
**tempo and loki do not need `/tmp`**: each started clean for 22 s with only its data directory
writable, zero permission errors; the `tmpfs` stays declared as cheap insurance and the record says so
rather than claiming a need that was not observed. (d) **mailpit was never measured and does need
`/tmp`**: `--read-only` alone dies at `[db] open /tmp/mailpit-1795218271355410715.db: read-only file
system`, and with a tmpfs on `/tmp` it serves SMTP and HTTP normally. (e) **web-app cannot be
`read_only`**, established from `life-control-app-angular/Dockerfile`, `nginx.conf` and `entrypoint.sh`
rather than by running it (its image is built here, and building it was out of bounds): the entrypoint
generates `assets/env.js` and `sed -i`s `index.html` inside the document root, and nginx writes
`/var/log/nginx/error.log` and `/var/cache/nginx`.

L14. The two `cap_add` sets are measured minimums, not copied from the recon. Postgres with `cap_drop:
ALL` and nothing added fails at `chmod: changing permissions of '/var/run/postgresql': Operation not
permitted` and `chown: changing ownership of '/var/lib/postgresql/18/docker': Operation not permitted`.
`CHOWN, SETUID, SETGID` alone fail earlier, on `mkdir: cannot create directory '/var/lib/postgresql/18':
Permission denied` — root without `DAC_OVERRIDE` cannot write into a directory owned by the operator — so
the set that reaches "database system is ready to accept connections" as uid 999 is `CHOWN,
DAC_OVERRIDE, SETUID, SETGID`, and `FOWNER` was measured unnecessary by leaving it out. Redis is the
case where a blanket `cap_drop` reverses the hardening: its entrypoint guards the privilege drop with
`has_cap setuid && has_cap setgid`, so with an empty set `redis-server` keeps running as **root**
(measured with `docker top`), while `CHOWN, DAC_OVERRIDE, FOWNER, SETUID, SETGID` make it drop to uid 999
and write `appendonly.aof` into `/data` exactly as before. Both measured sets also hold with
`security-opt no-new-privileges:true` added, which is the combination that ships.

L15. Evidence, T3: `docker/scripts/tests/compose-hardening.test.sh` added (15 assertions). It freezes the
per-service `cap_drop`, `cap_add`, `security_opt`, `read_only`, `tmpfs` and `user` record for the three
file combinations, read from `docker compose config --format json`, in both directions — a service whose
record moves and a rendered service with no row both fail — because S4's literal "for every image that
tolerates it" is not falsifiable and a table of what each image was measured to tolerate is. RED
measured first on the pre-change tree, via `git archive HEAD docker | tar -x -C "$(mktemp -d)"` with the
new suite copied in: **1 passed, 52 failed**, naming each service's previous record (no `cap_drop`, no
`read_only`, `prometheus`/`tempo` at `0:0`) and every missing `require_dir_owner` call. GREEN after the
compose edits and the `setup-env.sh` helper: **15 passed, 0 failed**. The suite also drives the new
ownership helper out of the script into a throwaway tree: already-correct owner is a success without
`chown`, a missing directory is created, and a `chown` it cannot apply exits non-zero naming both the
directory and the command to run.

L16. `setup-env.sh` gained `require_dir_owner`, and every bind-mounted data directory it owns now goes
through it: `loki` and `tempo` at `10001:10001`, `prometheus` at `65534:65534`, `grafana` at
`1000:1000`. A failed `chown` is `exit 1` with the directory and the exact `sudo chown -R` line, which is
the half `AGENTS.md` recorded as conditional until this change; the "already owned by the target" case is
a success so a non-root operator can re-run the script after applying the hint. **What an operator has to
run once on an existing checkout** (those directories are the operator's today, not the images'): `sudo
chown -R 65534:65534 docker/volumes-<env>/prometheus/data` and `sudo chown -R 10001:10001
docker/volumes-<env>/tempo/data`, plus `sudo chown -R 10001:10001 …/loki/data` if it is not already 10001.
In prod the two observability services use named volumes, which Docker initializes with the image's
ownership. Without those commands `setup-env.sh` aborts naming them, which is the chosen failure mode and
not a silent restart loop.

L17. Checks run for this slice, all on a worktree whose dev stack was left alone: `docker compose config`
for the three combinations (base; base+override; base+prod) rendered the frozen record for all 14/15/14
services; `docker/scripts/tests/` **32 suites, 0 failed** (31 before, the new suite is the 32nd);
`.github/scripts/tests/odd-status-headers.test.sh` and `odd-citations.test.sh` pass over this record and
all 194 of them; shellcheck with the CI pin (`docker run --rm -v "$PWD:/mnt" -w /mnt
koalaman/shellcheck:v0.11.0 --severity=warning -x`) is clean over `setup-env.sh` and the new suite. No
image version, port or mount moved: the change is how the existing containers run. Two verdicts are
recorded rather than fixed because they are edits to images, not to compose: `keycloak`'s dev-mode
augmentation and `web-app`'s generated `env.js` inside the document root.

L20. T4's chosen rule — owner = the one uid that reads the file, mode `0400` — does not survive the
measurement, so T4 stays open on a new decision instead of shipping a mode that breaks containers. The
readers were read from the compose mounts (base, override and prod), the five entrypoint wrappers and
every host-side caller, then measured on the running dev stack as `/proc/<container init pid>/status`
family Uid — the only shape that also answers for the distroless exporter images, which have no shell —
and in throwaway containers for the two cases a running stack cannot show.

| file | readers (uid) and evidence | one non-root owner? |
| --- | --- | --- |
| `grafana_admin_password` | grafana 1000 (`grafana-entrypoint.sh:36` `cat`, compose `user: "1000:1000"`, base:505) | yes |
| `keycloak_admin_password` | keycloak 1000 (`keycloak-entrypoint.sh:28`, image user 1000); **host** operator (`keycloak-setup.sh:200`) | no |
| `keycloak_admin_client_secret` | lifecontrol-api 1001 (`lifecontrol-app-entrypoint.sh:77`); **host** operator (`keycloak-setup.sh:201`) | no |
| `smtp_password` (no template) | grafana 1000 (`grafana-entrypoint.sh:45`); **host** operator (`keycloak-setup.sh:239`) | no |
| `redis_password` (no template) | redis root (`redis-entrypoint.sh`, and the healthcheck's `cat`, both before/above the drop); lifecontrol-api 1001 (base:824) | yes (1001) |
| `lifecontrol_app_password` | lifecontrol-api 1001; lifecontrol-postgres **999** on a fresh volume (`lifecontrol-roles.sql:126` inside a `docker-entrypoint-initdb.d` script), root through `db-roles.sh`'s `compose exec` | no |
| `lifecontrol_postgres_password` | lifecontrol-api 1001 (Flyway, `lifecontrol-app-entrypoint.sh:76`); postgres-exporter-lifecontrol **65534** (`DATA_SOURCE_PASS_FILE`, base:350); lifecontrol-postgres root (wrapper before `gosu`) / 999 (init script) | no |
| `keycloak_postgres_password` | keycloak 1000 (`keycloak-entrypoint.sh:27`); postgres-exporter-keycloak **65534** (base:386, prod:202); keycloak-postgres root (wrapper) / 999 (init script) | no |
| `fiscal_csd_certificado`, `fiscal_csd_llave`, `fiscal_csd_password`, `fiscal_sf_password` (no template) | lifecontrol-api 1001 (`lifecontrol-app-entrypoint.sh:94-97`) | yes (1001) |

The two measurements the table rests on, both on throwaway containers and `mktemp -d` fixtures:
`postgres-exporter` as `--user 65534:65534` fails a `0400` file owned by 1000 with `failed loading data
source pass file /mnt/c.dat: open /mnt/c.dat: permission denied`, and reads it (starts listening on 9187,
killed by the client timeout while it retries the absent database) when it owns the file — so the two
`DATA_SOURCE_PASS_FILE` exports are real readers at 65534, not documentation. A `postgres:18.6` whose
`docker-entrypoint-initdb.d` script writes `id -u` writes **999**, and the same script cannot read a
`0400` file owned by 1000 (`denied uid=999`) — so an owner of 1001 on `lifecontrol_app_password` would
fail the first start of a fresh production volume, not the second.

Three rows of the table T4 was given are therefore wrong in a way that matters: `smtp_password` **is**
mounted into a container (grafana) and read there at 1000, so it is the host-versus-container case the
task said to stop on; `lifecontrol_postgres_password` has the API as well as the exporter (1001 **and**
65534); `keycloak_postgres_password` has keycloak as well as its exporter (1000 **and** 65534). Two facts
the table did not carry at all: `keycloak-setup.sh` reads three of these files **on the host** as
whichever account runs it — and it is documented as a plain `./docker/scripts/keycloak-setup.sh <env>` —
and the roles SQL reads two of them from inside the database at 999 on a fresh volume. `redis_password`
keeps 1001, but it has no `.template`: `setup-env.sh` never materializes it, though `L4` and
`redis-entrypoint.sh`'s own error message both assume it does, and the four fiscal credentials are in the
same shape. `validate-env.sh` currently demands `0444` of every entry, hand-created ones included.

The decision this leaves is not a detail of the mode: `0400` with one owner is implementable for
`grafana_admin_password` — the one template whose single reader is 1000 — plus the five hand-created
files whose sole non-root reader is already the API's 1001 (`redis_password` and the four fiscal
credentials), which `setup-env.sh` does not materialize at all today.
Closing S6 for the rest needs a mechanism that was ruled out of T4's brief — a shared group with `0440`
and a compose `group_add`, or per-reader copies of the credential, or POSIX ACLs on the host inode (the
only shape that grants several uids on one file with no compose change) — or a narrowing of the spec that
accepts `0444` where several readers exist, which is status quo and does not meet S6 there. No file was
edited and no suite re-pinned while that is undecided: the two suites that pin `0444`
(`secrets-directory.test.sh`, `validate-env-strict-pins.test.sh`) and the docs are untouched, so the tree
still describes what it applies.

L21. Implementation of T4, after L20 measured that the single-owner rule fits one templated credential out
of six. The maintainer's decision, applied verbatim:

  (a) **M1 — one numeric gid per credential with two non-root readers**, declared with `group_add` on
  exactly those services: `9311` on `lifecontrol-api` and `lifecontrol-postgres` for
  `lifecontrol_app_password` (0440 root:9311); `9312` on `lifecontrol-api` and
  `postgres-exporter-lifecontrol` for `lifecontrol_postgres_password` (0440 root:9312); `9313` on
  `keycloak` and `postgres-exporter-keycloak` for `keycloak_postgres_password` (0440 root:9313). Chosen
  over POSIX ACLs because there is one copy of each value (no rotation drift), the reader declaration sits
  in the compose next to `cap_drop`/`read_only`/`user:` where the next person looks, nothing is created on
  the host, and the permission is visible without parsing `getfacl`.
  (b) **H2 — the contract covers every file `validate-env.sh` sees**, templated or hand-created: nine
  credentials are `0400` owned by the one uid that reads them (`1000` for the grafana and keycloak
  wrappers, `1001` for the api wrapper), the three above are `0440 root:<gid>`, and `redis_password` and
  the four fiscal credentials get the same treatment when they exist. No password is generated anywhere.
  (c) **`keycloak-setup.sh` runs as root**, which is what removes the three host-reader conflicts of L20:
  root reads every file, so `keycloak_admin_password`, `keycloak_admin_client_secret` and `smtp_password`
  are single-reader and become `0400`. The reason travels with the rule — a credential the operator's
  account can read is a credential every process that account runs can read.

Readers re-verified before implementing (requirement: correct the table, never delete a reader). Two rows
of the handed table changed and one class of reader was missing entirely: `redis_password` has no
`.template` at all, so `setup-env.sh` never materialized it (the shape L4 and `redis-entrypoint.sh`'s own
FATAL message assumed) — it is hand-created, like the four fiscal credentials; and `keycloak_admin_client_secret`
is read on the host by **three** scripts, not one: `keycloak-setup.sh:201`, `verify-invitation-flow.sh:65`
and `verify-provisioning-flow.sh:166`, so all three are documented as `sudo` in `AGENTS.md` (the two
verify scripts' own headers say only "materialized", which is left to the transaction controller because
those two files were outside the edit surfaces of this task). Nothing else moved: the exporters still read
their `DATA_SOURCE_PASS_FILE` at 65534 and the roles SQL still reads two of these files at 999 on a fresh
volume, both re-measured in L20 and both served by the group rather than by a second owner.

Evidence, RED first where an old assertion had to change. The new suite
(`docker/scripts/tests/secrets-ownership.test.sh`) on the **pre-change** tree, with only the suite copied
in: **24 passed, 42 failed** — every table row missing from both scripts and from `AGENTS.md`, the gate
still accepting a `0444` file with any owner, the applier helpers absent, and no `group_add` rendered.
GREEN after: **72 passed, 0 failed, 0 skipped**. The two suites that pinned the retired `0444` literal were
re-run against the pre-change code with their new assertions: `secrets-directory.test.sh` **8 failed**
(`must be 0444` still emitted, three pass-cases refused) and `validate-env-strict-pins.test.sh` **7 failed**
(every pass-case refused because the seeded credential had no declared shape) — GREEN after:
**38 passed, 0 failed** and **26 passed, 0 failed**. Whole set: `docker/scripts/tests/` **33 suites, 0
failed** (32 before; the new suite is the 33rd, and it is executable — a suite the glob finds but the shell
cannot run is a `126`, which is how the first loop run failed it). `.github/scripts/tests/` unchanged and
green: `odd-status-headers.test.sh` 78 passed, `odd-citations.test.sh` 29 passed. shellcheck with the CI
pin (`docker run --rm -v "$PWD:/mnt" -w /mnt koalaman/shellcheck:v0.11.0 --severity=warning -x`) is clean
over `setup-env.sh`, `validate-env.sh`, `keycloak-setup.sh` and the three suites, after it caught a real
defect: `SHIM_BLOCK` was never exported, so the doubled `chown` in the new suite never saw it and the
"cannot apply" case passed by accident through the real binary. The script's own readability refusal got
its case in the suite that already drives it (`keycloak-setup.test.sh`, case 16, six assertions): RED on
the pre-change script, where the 0000 credential still aborted but the output never said `Cannot read`
nor named the `sudo` line — **2 failed** of those six, on a suite that ends **81 passed, 0 failed** after
the change. The case skips itself on a root run, because root can read a 0000 file and the guard cannot be
driven without pretending.

What was measured, and what was not. The criterion itself is measured with real containers, not argued:
`0400` owned by uid 1000 — uid 1000 reads it, `1234:1234` is denied; `0440 root:9312` — `1001:9312` reads
it, `1001:1001` is **denied**, so the group is what grants access, and `1234:1234` is denied; and the real
`postgres-exporter` image with `DATA_SOURCE_PASS_FILE` fails with `failed loading data source pass file` as
uid 65534 without gid 9312 and reads it inside the group. Those cases SKIP loudly when no daemon or image
is present, and they did not skip here. `setup-env.sh` was driven end to end in two throwaway copies of the
whole `docker/` tree, with `VOLUMES_ROOT` removed from the copied environment file so the script reaches the
credentials step (it otherwise aborts at Loki's directory owner, which needs root — a pre-existing
property, unchanged): the base commit materialized six files at `0444` and exited **0**; this change
materialized the same six, applied the modes it could, reported each owner/group it could not, printed
**one** block — `deploy.sh dev stop`, six `sudo chown … && sudo chmod …` lines, `deploy.sh dev up` — and
exited **1**. What is therefore *not* measured: a real `0400`/`0440` file being read by a restarted dev
stack (S7). The running stack was not restarted, rebuilt or touched, and this worktree has no materialized
credentials of its own, so the "containers still read it" half is established by the throwaway containers
above and by the `group_add` rendering of `docker compose config` for the three file combinations, not by
the shared stack. On an existing checkout the files are still the operator's, so the first `setup-env.sh`
prints that block and stops; that one privileged round trip is the documented cost of the change.

The applier's branches, and which of them an unprivileged run can reach: *already correct* is driven with
no `chown` attempted at all; *mismatch it can apply* is driven for real, with the one owner this run is
allowed to set (its own uid), and the file ends at the declared mode with nothing pending; *mismatch it
cannot apply* is driven through a doubled `chown`/`chmod` that logs and fails, which is what collects two
files and prints the block. What no unprivileged run can drive is the root-only shape — `chown 0:9311` and
its siblings — so the block is asserted as text and its application is the operator's one sudo step.

The rotation path was outside this task's edit surfaces when the worker finished, and the parent closed it in
the same branch: `cleanup.sh` no longer ends with `chmod 0444`. A rotated credential is narrowed to `0600` —
never wider than its creator — and the command's closing warning names `setup-env.sh <env>` before
`deploy.sh <env> restart`, because the legal shape of every credential is applied in one place only. Until
that runs, the file is `0600` and `validate-env.sh` refuses the deploy, which is the correct failure mode. The
two host-side verification scripts (`verify-invitation-flow.sh`, `verify-provisioning-flow.sh`) had the same
kind of staleness and were fixed the same way: their prerequisites now say the credential is read with `sudo`,
because it belongs to the API's uid.
