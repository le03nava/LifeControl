# k8s gateway: the workload starts on an amd64 node, and answers the probe it declares

**Status**: implemented — the pinned gateway image stays pinned and what changed is the node's ability
to execute it (the cluster entry point registers the arm64 emulator before the cluster exists, the
emulator is a frozen pin like every other image the tree names), plus the probe the workload can
actually answer: the path the tree declared answers `401` and could never have passed. Both halves are
asserted by `k8s-manifests.test.sh` with their rejecting fixtures. The `readOnlyRootFilesystem` verdict
that #1035 left unmeasurable is still unmeasured on purpose: this unit makes it measurable, and
deciding it is that unit's leftover. The cluster half of S1 was **not run** here — see L10.

## Specs

- **S1** — "The workload the tree declares for `api-gateway` starts on an amd64 `kind` node, so the
  playground's 'every pod reaches `Ready` in 10 minutes' holds for all eight workloads instead of
  seven." (issue #1056, expected outcome, verbatim)
- **S2** — "`docker image inspect -f '{{.Architecture}}/{{.Os}}' <the pinned gateway image>` reports
  `amd64/linux`, or the manifest pins a digest that does." (issue #1056, acceptance criterion,
  verbatim). **Amended, with the measurement in L3:** Docker Hub publishes one tag of
  `saiupadhyayula007/new-api-gateway` and its manifest is `arm64/linux` only, so no amd64 tag and no
  amd64 digest exists to pin. The image the tutorial teaches stays pinned and the node gains the
  emulator.
- **S3** — "`docker run --rm <the pinned gateway image>` on an amd64 host does not exit with
  `exec format error`; it reaches its own startup output." (issue #1056, acceptance criterion,
  verbatim)
- **S4** — "`docker/scripts/tests/image-pins.test.sh` still passes: changing the tag is editing the
  frozen table in the same commit, and the named `:latest` exception is either still named or replaced
  by a pinned tag with its reason." (issue #1056, acceptance criterion, verbatim)
- **S5** — "The rest of the tree is untouched: the eight Deployments, the namespace and network
  boundary from #1034, and the hardening from #1035." (issue #1056, acceptance criterion, verbatim)
- **S6** — the workload reaches `Ready`, which is what "every pod reaches `Ready`" means: the probe the
  tree holds for it has to be one the image answers below `400` without a token. Derived from S1, and
  it is a second defect, not a restatement of the first: the arch was why the pod died, the `401` is
  why it would have stayed `NotReady` after starting.

## Tasks

| ID  | Spec  | Route                              | Commit     |
| --- | ----- | ---------------------------------- | ---------- |
| T1  | S1    | inline — cluster entry point       | this slice |
| T2  | S2    | inline — the manifest's reason     | this slice |
| T3  | S4    | inline — the frozen k8s table      | this slice |
| T4  | S1,S3 | inline — the suite and its fixture | this slice |
| T5  | S5    | inline — README and AGENTS         | this slice |
| T6  | S6    | inline — the readiness path        | this slice |

## Log

- **L1** — the human's request: `si 1056`.
- **L2** — the direction, chosen by the human and recorded before the first write: of four shapes
  (emulate arm64 in kind; swap to the same author's amd64 `api-gateway:v1`; drop the workload from the
  tree; build this repository's gateway for the playground) the human answered `lo que recomiendes`,
  and the emulation is the one that keeps the image, the four network flows and every assertion of
  #1034/#1035 true instead of swapping in a previous generation of the app behind an older name.
- **L3** — measured, `https://hub.docker.com/v2/repositories/saiupadhyayula007/new-api-gateway/tags`:
  `count 1`, tag `latest`, images `arm64/linux`. There is no amd64 build to pin.
- **L4** — measured, the alternative S2 would have preferred: `saiupadhyayula007/api-gateway:v1` is
  `amd64/linux`, runs (`Netty started on port 8080`, `Started ApiGatewayApplication in 4.456
  seconds`) and answers `401` on every path — `/`, `/login`, `/actuator/health`. Its readiness probe
  would have to become `tcpSocket`, its `Config.User` is empty, and it is the previous generation of
  the app: three downgrades to satisfy one criterion literally.
- **L5** — `./docker/scripts/tests/image-pins.test.sh`: `88 passed, 0 failed`, naming
  `tonistiigi/binfmt:qemu-v10.2.3` as a frozen k8s pin, found by the new third reader in the
  `docker run` line.
- **L6** — `./.github/scripts/tests/k8s-manifests.test.sh`: `69 passed, 0 failed`, including
  `7b. the cluster entry point registers the emulator the gateway image needs` and its rejecting
  fixture `a cluster entry point that never registers the arm64 emulator`.
- **L7** — the registration is read back (`/proc/sys/fs/binfmt_misc/qemu-aarch64`) instead of trusted,
  because `tonistiigi/binfmt --install` exiting 0 and the kernel naming the interpreter are two
  different facts and only the second one makes the pod start.
- **L8** — measured on this amd64 host, RED then GREEN, which is S3: without the registration
  `docker run --rm saiupadhyayula007/new-api-gateway:latest` exits `exec /cnb/process/web: exec format
  error`; with it, `Tomcat started on port 9000 (http)` and `Started ApiGatewayApplication`. Two
  measured boots: **165 s** and **884 s**, so the playground's timing expectation is about a slow boot,
  not a hang.
- **L9** — measured, the path matrix that makes S6 a defect and not a hypothesis, all unauthenticated
  on that same running container: `/` `401`, `/actuator` `401`, `/actuator/health` `401`,
  `/actuator/health/liveness` `401`, `/actuator/health/readiness` `401`, `/actuator/info` `401`,
  `/favicon.ico` `401`, and `/actuator/prometheus` **`200`**. The tree's `readinessProbe` declared
  `/actuator/health`, which the kubelet reads as a failed probe forever; it now declares
  `/actuator/prometheus`, the same path `infra/prometheus.yml` already scrapes on that port.
- **L10** — the cluster half of S1 was **not run**: this machine has no `kind` and no `kubectl`
  (`kind: command not found`), so `kubectl get deploy -n life-control api-gateway` → `READY 1/1` on a
  fresh cluster has no evidence here. What is measured is the image's behaviour on an amd64 host and
  the static suites; the cluster run is the operator's step. One unverified interaction is named rather
  than assumed: the readiness probe is issued by the kubelet from the node, and the tree's
  default-deny ingress (#1034) governs traffic from pods — whether `kindnet` lets that node-originated
  probe through was **not** measured, and if it does not, the eight workloads' probes fail together.
- **L11** — the gateway's identity keeps `Config.User` as its source even now that the image runs: it
  carries no `id` (`exec: "id": executable file not found in $PATH`), so the declaration is the only
  reading, and the pod pins the same pair explicitly.
