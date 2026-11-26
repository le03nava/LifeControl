# k8s-hardening — non-root, bounded and observable, with the reason measured

Issue #1035, Unit 3 and last of the k8s chain (#891 landed the single tree, #1034 the namespace and
the network boundary). Branch `chore/k8s-hardening`, worktree
`~/workspace/LifeControl-worktrees/chore-k8s-hardening`, base `origin/main` at `c6ac6595`.

**Status**: implemented — the eight workloads declare the identity their own image supports (measured,
not read), `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, bounded CPU and memory, a
readiness probe, an explicit `imagePullPolicy`, and a `readOnlyRootFilesystem` that is `true` on the
five images whose read-only run was proven to work with named tmpfs paths and `false` on the three that
carry their measured reason at the container. The three `hostPath` workloads get a `prepare-data` init
container because uid against a root-owned directory is `errno 13`. S7 — pods reaching `Ready` on a
cluster — has no cluster here (`kubectl`, `kind` and `kustomize` are absent), so it is an operator step
named in the log and not claimed; separately, the gateway's published image is arm64-only, which is a
pre-existing defect outside this record.

## Specs

The user's own words, opening the unit:

- **S1** — «si dale»

That continues the offer made immediately before it («si querés cerrar la cadena, decime y arranco
#1035»), so it authorizes issue #1035 as corrected on 2026-11-25 before the label was applied: the
issue said 15 Deployments and named the duplicated directories #891 deleted. The acceptance criteria
are quoted verbatim below.

- **S2** — «Every one of the **8** containers declares `securityContext` with `runAsNonRoot: true`,
  `allowPrivilegeEscalation: false` and `capabilities.drop: ["ALL"]`, plus a pod-level
  `runAsUser`/`runAsGroup` that the image actually supports.»
- **S3** — «Every container declares `resources.requests` and `resources.limits` for both `cpu` and
  `memory`, and every value is non-zero.»
- **S4** — «Every Deployment declares a `readinessProbe`. `livenessProbe` is declared where the
  workload can answer one without restarting itself under load; a workload that omits it says so in a
  comment at the probe site rather than silently.»
- **S5** — «Every container declares `imagePullPolicy` explicitly.»
- **S6** — «`readOnlyRootFilesystem: true` where the image tolerates it, with a `tmpfs` for every path
  it writes outside its volumes — the same pairing `AGENTS.md` records for the compose services. A
  workload that cannot tolerate it keeps the root filesystem writable and carries its **measured
  reason at the container**, not in a commit message.»
- **S7** — «**The playground still works**: after `kubectl apply` on a fresh `kind` cluster, every pod
  reaches `Ready` within 10 minutes with no `CrashLoopBackOff`, and the flows asserted by the
  namespace unit (Grafana→Prometheus/Tempo/Loki, Prometheus→gateway, gateway→Keycloak,
  Keycloak→MySQL) still succeed. A hardening change that turns the playground into a restart loop is
  a failure, not a trade-off.»
- **S8** — «`.github/scripts/tests/k8s-manifests.test.sh` gains assertions for each construct in the
  table above, per Deployment, so the count cannot silently return to zero.»

## Measured before the first edit

Every value below is a probe, not a reading of documentation. Method: `docker image inspect` for the
declared user, `docker run --rm --entrypoint id` for the real one, then the image run twice — baseline
and `--read-only` — with **identical program arguments**, so a failure caused by the filesystem is
distinguishable from one caused by a missing dependency (prometheus without a config, keycloak without
a database and mysql without a datadir all fail for reasons unrelated to the filesystem, and counting
those as "does not tolerate a read-only rootfs" would be a false measurement).

| workload | image | uid:gid | mechanism | read-only rootfs |
|---|---|---|---|---|
| api-gateway | saiupadhyayula007/new-api-gateway:latest | `1002:1000` | declared in the image | **unmeasurable** — see below |
| grafana | grafana/grafana:10.1.0 | `472:0` | image user | tolerates with `/var/lib/grafana` writable (`GF_PATHS_DATA='/var/lib/grafana' is not writable`, then it starts) |
| keycloak | quay.io/keycloak/keycloak:24.0.1 | `1000:0` | image user | **does not tolerate**: `java.nio.file.FileSystemException: /opt/keycloak/lib/quarkus/transformed-bytecode.jar: Read-only file system` |
| keycloak-mysql | mysql:8.3.0 | `0:0` → `999` | entrypoint starts as root, `gosu` to `mysql` | **does not tolerate**: `Unable to create temporary file inside "/tmp"; errno: 30` |
| loki | grafana/loki:3.7.8 | `10001` | distroless, no shell (`exec: "id": not found`) | **does not tolerate**: `failed to create object client: mkdir /loki/chunks: read-only file system` |
| mysql | mysql:8.3.0 | `0:0` → `999` | same image, same entrypoint | **does not tolerate**: same `/tmp` errno 30 |
| prometheus | prom/prometheus:v2.46.0 | `65534:65534` | image user (`nobody`) | does not tolerate a read-only data path: `Error opening query log file /prometheus/queries.active: read-only file system` |
| tempo | grafana/tempo:2.2.2 | `0:0` | **runs as root**, and the image has no `tempo` user (only `nobody:65534`) | **does not tolerate**: `failed to create store mkdir /tmp/tempo: read-only file system` |

Two measurements that decide the design rather than a flag:

1. **`mysql:8.3.0` as uid 999 against a root-owned directory fails**: `mysqld: Can't create/write to
   file '/var/lib/mysql/is_writable' (OS errno 13 - Permission denied)` → `--initialize specified but
   the data directory exists and is not writable`. The same directory made writable initializes
   (`InnoDB initialization has ended`). All three PVs of this tree are `hostPath` on the node
   (`/data/mysql`, `/data/keycloak/mysql`, `/tmp/tempo`), which is root-owned, and `hostPath` is the
   one volume type the kubelet does **not** hand to `fsGroup`. So `runAsUser: 999` is not a rule that
   can be declared alone: the directory has to be handed over first.
2. **`saiupadhyayula007/new-api-gateway:latest` is an arm64 image**: on this amd64 host it exits with
   `exec /cnb/process/web: exec format error`, with and without `--read-only`. Its uid is only
   readable from `Config.User` (`1002:1000`), never observed running. This is **pre-existing and not
   caused by this unit** — the playground cannot start that workload on an amd64 `kind` node at all —
   but it is why S7 cannot be met here and why the gateway is the one container whose
   `readOnlyRootFilesystem` stays off with a reason instead of a measurement.

## Decisions taken before the first edit

- **The three `hostPath`-backed workloads get an init container that hands the directory over**
  (`prepare-data`, running as uid 0 with `runAsNonRoot: false` at container level only, `chown` to the
  workload's uid, every capability dropped except `CHOWN`). Without it, S2's `runAsNonRoot` and S7's
  "playground still works" cannot both be true: measured, uid 999 against a root-owned datadir is
  `errno 13`. The alternative — leaving them root — fails S2; the alternative of `fsGroup` does not
  apply to `hostPath`.
- **`mysql` and `keycloak-mysql` also get a `tmpfs` on `/tmp`**, because the read-only run failed
  there and nowhere else.
- **Keycloak, tempo and the gateway keep a writable root filesystem**, each carrying its own measured
  reason at the container. Tempo's is structural, not incidental: its data path *is* `/tmp/tempo`, so a
  `tmpfs` on `/tmp` would shadow the volume it writes to.
- **Grafana, prometheus and loki are read-only** with a `tmpfs` on exactly the path each named:
  `/var/lib/grafana`, `/prometheus`, `/loki`.
- **`livenessProbe` is not declared anywhere**, and S4 permits that only with a comment at the probe
  site. No workload here can answer one under load: `keycloak` on `start-dev` with an embedded
  database, `mysql` while initializing, and `tempo`/`loki` under a query burst would all be restarted
  by a naive `/health` check. The readiness probe is what the tree needs, and each workload's comment
  says what a liveness probe would have to prove before it could exist.
- **Nothing touches the namespace or the NetworkPolicy #1034 declared.** The suite's flow-pair
  assertion must stay green.

## Tasks

| ID | Task | Spec | Route | Commit |
|----|------|------|-------|--------|
| T1 | Pod-level `securityContext`, container `securityContext`, `imagePullPolicy` and `resources` for the 8 workloads | S2, S3, S5 | worker | `chore(k8s): run the workloads unprivileged and bounded` |
| T2 | `readinessProbe` per workload, with the liveness comment at the probe site | S4 | worker | same |
| T3 | `readOnlyRootFilesystem` plus the `tmpfs` each image named, or the measured reason it stays writable | S6 | worker | same |
| T4 | `prepare-data` init container for the three `hostPath` volumes | S2, S7 | worker | same |
| T5 | Extend `.github/scripts/tests/k8s-manifests.test.sh`: per-Deployment assertions for every construct, plus a rejection fixture each | S8 | worker | `test(k8s): freeze the hardening of every workload` |
| T6 | Run the offline suites; write the `**Status**` paragraph and the evidence log | all | parent | `docs(odd): record the k8s hardening unit` |

## Log

- **L1** — user: «si dale»
- **L2** — measured before any edit, against `c6ac6595`: **8 Deployments, 8 containers**, 4 `resources:`
  hits (`infra/mysql.yml:79`, `infra/keycloak-mysql.yml:81`, `infra/tempo.yml:94` are PVCs;
  `kustomization.yaml:26` is the kustomization's own key), and every hardening construct at **0**. The
  issue's "15 Deployments" and its references to the duplicated directories were stale; corrected, and
  only then labelled `status:approved`.
- **L3** — the image measurements are the table above. Two false starts are recorded rather than
  hidden: the first probe passed each program's arguments **before** the image, so docker parsed them
  as its own flags (`invalid argument "onfig.file=…" for "-c, --cpu-shares"`, `pull access denied for
  start-dev`), and the third run had to mount a genuinely read-only directory over prometheus's data
  path because `--read-only` still gives a declared `VOLUME` an anonymous writable one. Rows produced
  by a broken probe were re-measured, not carried over.
- **L4** — decision: `prepare-data` init container for the three `hostPath` volumes (see above).
- **L5** — the arm64 gateway image is a **separate defect**, not part of this unit and not fixed here.
- **L6** — not measured and therefore not claimed: nothing about a real cluster. `kubectl`, `kind` and
  `kustomize` are absent, so S7 stays an operator step; the offline half is S2–S6 and S8.
- **L7** — the worker's read-only rows were incomplete for the two MySQL workloads and the parent's
  second measurement caught it. The first read-only run stops at the **first** path it cannot write, so
  "MySQL names `/tmp` and nothing else" was a true statement about one run and a false one about the
  image: with `/tmp` as a tmpfs it names the socket directory next —
  `Setup of socket: '/var/run/mysqld/mysqlx.sock' failed, can't create lock file
  /var/run/mysqld/mysqlx.sock.lock` — and only with both does it reach `ready for connections`. Both
  MySQL Deployments gained a second memory-backed `emptyDir` at `/var/run/mysqld`, both comments now
  record the two rounds instead of one, and `READ_ONLY_ROOTFS` in the suite lists the second path
  (mysql and keycloak-mysql take two lines; the rest one). Shipping the manifest as written would have
  produced exactly the restart loop S7 calls a failure.
- **L8** — implementation, measured: the suite went from **40 passed / 0 failed** (its state before
  this unit) to **67 passed / 0 failed**. The RED is not a narrative: the worker materialised the base
  tree (`git archive c6ac6595 k8s/manifests`) and ran the new checkers against it — **57 passed / 9
  failed**, eight of them for the intended reason (`declares pod runAsUser "" and its image supports
  1002`, `drops capabilities "none"`, `declares no imagePullPolicy`, `declares no non-zero
  requests.cpu`, `declares readiness probe "none"`, `does not say at the probe site what one would have
  to prove`, `declares readOnlyRootFilesystem "none"`, `prepare-data runs as "none"`).
- **L9** — all offline suites: 54 suites, and the only red is this record's own missing `**Status**`
  paragraph (`odd-status-headers`, 77 passed / 1 failed) — written above, green with it. The two other
  suites that read `k8s/` stayed green on purpose: `docker/scripts/tests/image-pins.test.sh` (87) because
  `prepare-data` reuses each workload's own pinned image instead of introducing a new reference, and
  `.github/scripts/tests/workflow-pins.test.sh` (16) because the lint surface is unchanged.
- **L10** — one transient that is recorded rather than smoothed over: a full-glob run reported exit 2
  for `k8s-manifests.test.sh` immediately after the suite had printed `RESULT: OK`, because the parent
  was rewriting the suite's own frozen table while that run read it. The deterministic re-run is `rc=0`,
  **67 passed / 0 failed**, and the verdict that counts is the re-run.
- **L11** — slice boundary and delivery shape: **~1063 added lines over nine files**, past the ~400-line
  slice, of which the suite is 624. The behavior is one slice (one rule about identity, limits and
  probes, applied eight times), the suite is the second at 624 lines and its cost is 18 rejection
  fixtures, one per way each property can be made false. One pull request whose slices are the commits,
  for the same reason unit 2 gave: a chain that reviewed the suite apart from the code it locks would
  be reviewing the same property twice.
- **L12** — measured, not inferred, after the manifests were written: `prepare-data` runs as uid 0 and
  needs a `chown` in the image. Checked with `docker run --rm --network none --entrypoint /bin/sh <image>
  -c 'command -v chown'`: `mysql:8.3.0` → `/usr/bin/chown`, `grafana/tempo:2.2.2` → `/bin/chown`. Loki
  has no `/bin/sh` at all, which is why it is not an init image and needs no handover.
- **L13** — `shellcheck` is not installed on this machine, so the local verdict was "not run" and CI
  gave the real one: the `ShellCheck` step failed with `SC2034: chown appears unused` inside
  `prepare_data_findings`, a leftover name in a `local` declaration — the chown target is asserted from
  `args_value`, so the check worked and the variable was only dead. Removed, and the pinned image the
  workflow uses (`koalaman/shellcheck:v0.11.0`) was then run over **all 28 scripts** CI lints (`find
  .github/scripts -name '*.sh'`, `.githooks/*`, `k8s/**/*.sh`): exit 0. The lesson recorded here rather
  than in a commit: "absent locally" means the lint verdict belongs to CI, so the local green on the
  rest of the suite was never evidence about shellcheck.
