# k8s-namespace-netpol — the tree names its namespace and blocks what it does not need

Issue #1034, Unit 2 of the three-unit k8s chain (#891 landed the single tree in PR #1052; #1035 is the
hardening unit and is not part of this one). Branch `chore/k8s-namespace-netpol`, worktree
`~/workspace/LifeControl-worktrees/chore-k8s-namespace-netpol`, base `origin/main` at `47fec77d`.

**Status**: implemented — the tree declares `life-control` in exactly two places, the seven
`*.default.svc.cluster.local` strings are short service names, and `network-policy.yml` denies both
directions by default and allows the flows the manifests declare, one policy per direction per flow.
The offline half is proven: the suite freezes every structural fact and its pair assertion was
falsified on purpose. The live half — `kubectl get ns`, the `kustomize` render, the flows themselves
and the 10-minute `Ready` — has no tooling here (`kubectl`, `kind` and `kustomize` are absent), so it
is an operator step named in the log and not claimed. Unit 3 of the chain (#1035) stays open and is
not part of this record.

## Specs

The user's own words, opening the unit:

- **S1** — «si dale al 1034»

That authorizes issue #1034 as corrected on 2026-11-24 before the label was applied: the issue said
"11 references" while listing 7 sites, and its line numbers for `infra/grafana.yml` were `71,82,100`
against a measured `59,70,88`. The corrected criteria are quoted verbatim below.

- **S2** — «A `Namespace` object for the tree's own namespace exists and is rendered by the
  kustomization, and `kubectl get ns` lists it after applying.»
- **S3** — «`grep -rn '\.svc\.cluster\.local' k8s/` returns nothing and no manifest relies on the
  implicit `default`: the 7 sites use **short service names** (`prometheus:9090`,
  `api-gateway:9000`), which resolve inside the pod's own declared namespace. Spelling
  `life-control.svc.cluster.local` instead would keep the rename-by-hand problem this issue exists to
  remove — a rename would still be seven hand edits. The namespace is therefore declared **once**
  (the `Namespace` object plus the kustomization's `namespace:` field) and never repeated in a DNS
  string.»
- **S4** — «`kubectl get networkpolicy -n <namespace>` lists a default-deny policy whose
  `spec.podSelector` is empty (`{}`) and whose `policyTypes` covers both `Ingress` and `Egress`.»
- **S5** — «**The flows the tree needs still work**, verified from inside the cluster and not
  inferred: after `kubectl apply`, Grafana reaches `prometheus:9090`, `tempo:3200` and `loki:3100`;
  Prometheus scrapes the `api-gateway` actuator; `api-gateway` resolves Keycloak; and Keycloak reaches
  its MySQL.»
- **S6** — «A flow the tree does **not** need is blocked: a `kubectl exec` from an unrelated pod to
  the MySQL port times out rather than connecting.»
- **S7** — «Every pod in the namespace reaches `Ready` within 10 minutes of a fresh `kubectl apply`
  on `kind`, with no `CrashLoopBackOff`.»
- **S8** — «`.github/scripts/tests/k8s-manifests.test.sh` (introduced by #891) gains assertions for the
  two structural facts: a `Namespace` object exists, and a default-deny `NetworkPolicy` exists whose
  `policyTypes` names both directions. The suite still runs from the existing glob, with no new
  workflow step.»
- **S9** — derived from S5 and not quotable from the issue: `k8s/kind/create-secrets.sh` runs
  **before** `kubectl apply -k` and today defaults its `--namespace` to `default`. Once the pods run in
  a declared namespace, a Secret created in `default` is unreadable by every pod the tree creates, so
  the script's default namespace is the declared one and the script creates that namespace when it is
  missing (it cannot rely on `create ns` run by an operator in a different order).
- **S10** — derived from S2: `README.md` documents the commands an operator runs, so it names the
  namespace the tree declares instead of leaving the reader to discover it.

Non-goals, verbatim from the issue, because they bound what this unit may touch: not making `k8s/` a
supported deployment path; not adding an Ingress, a service mesh, mTLS, RBAC or a `ServiceAccount` per
workload; not restricting egress to external hosts beyond what the allow rules already need; **not
touching `securityContext`, resource limits or probes — that is the third unit**; not changing any
image tag.

## Decisions taken before the first edit

- **The namespace is `life-control`**, matching the repository and the `life-control-*` service names.
- **DNS strings become short service names**, not `<namespace>.svc.cluster.local` (S3). A policy inside
  a namespace resolves a short name through cluster DNS, so the short form is namespace-agnostic while
  the FQDN form re-creates exactly the seven-hand-edits defect the issue names.
- **The namespace is declared twice and only twice**: `k8s/manifests/namespace.yml` (the object) and
  the kustomization's `namespace:` field (the transform). No other file writes it.
- **Cluster DNS is an explicit allow.** A default-deny egress policy blocks `53` and then every short
  name above fails to resolve — the failure would look like a broken NetworkPolicy rather than a
  missing rule, so S5's DNS hop is a rule with its own reason written next to it.
- **Policies are per flow, not "allow the namespace".** A blanket intra-namespace allow would make S6
  unprovable: any pod in the namespace could reach MySQL.
- **Measured limitation, before any edit**: `kubectl`, `kind`, `kustomize` and `shellcheck` are all
  absent from this environment (`command -v` → nothing for the four). S2's live `kubectl get ns`, S5,
  S6, S7 and the render behind S4 are **operator steps**; the offline half (S3, S4, S8, S9) is what
  this unit can prove.

## Tasks

| ID | Task | Spec | Route | Commit |
|----|------|------|-------|--------|
| T1 | Declare `life-control` in `k8s/manifests/namespace.yml` and via the kustomization's `namespace:`; rewrite the 7 `.default.svc.cluster.local` strings as short service names | S2, S3 | worker | `chore(k8s): declare the namespace and close the network by default` |
| T2 | `k8s/kind/create-secrets.sh`: default its namespace to the declared one and create it when missing | S9 | worker | same |
| T3 | `k8s/manifests/network-policy.yml`: default-deny plus one ingress and one egress rule per flow the tree needs, DNS included; register it in the kustomization | S4, S5, S6 | worker | same |
| T4 | Extend `.github/scripts/tests/k8s-manifests.test.sh` with the structural facts of S8 plus the pair-completeness assertion below | S8 | worker | `test(k8s): freeze the namespace and the network boundary` |
| T5 | `README.md`: the operator commands name the namespace | S10 | worker | same as T1 |
| T6 | Run every offline suite (`.github/scripts/tests/*.test.sh`, `docker/scripts/tests/*.test.sh`) and report what could not run; write the `**Status**` paragraph and this unit's evidence log | all | parent | `docs(odd): record the k8s namespace unit` |

T4's hardest assertion is the one nobody writes by accident: **every egress rule's destination has a
matching ingress rule and vice versa**. A policy that allows one direction is a policy that silently
does nothing — that is the defect this unit can catch offline, without a cluster.

T6 is the parent's: the verdicts belong to whoever signs the record.

## Log

- **L1** — user: «si dale al 1034»
- **L2** — measured before any edit, against `47fec77d`: `grep -rn '\.default\.svc\.cluster\.local' k8s/`
  returns **7** lines — `applications/common-config.yml:7-8`, `applications/api-gateway.yml:54`,
  `infra/grafana.yml:59,70,88`, `infra/prometheus.yml:57`. The issue claimed 11 and named grafana at
  `71,82,100`. Both statements were wrong; the issue body was corrected and only then labelled
  `status:approved`.
- **L3** — measured before any edit: `kubectl`, `kind`, `kustomize` and `shellcheck` are absent here,
  so the live half of S2/S4/S5/S6/S7 is an operator step and this record will not claim it.
- **L4** — decision: short service names rather than the declared-namespace FQDN (see S3).
- **L5** — decision: `create-secrets.sh` gains the ordering dependency (S9). It already accepted
  `[namespace]` and defaulted to `default`, which is the silent half of this unit: the Secrets would
  keep landing where no pod can read them.
- **L6** — implementation, measured: `bash .github/scripts/tests/k8s-manifests.test.sh` went from
  **23 passed / 8 failed** (`RESULT: FAILED`, the failures naming the seven FQDN sites by line, the
  absent `namespace:` field, the absent `Namespace` object and the absent policy) to **40 passed /
  0 failed** (`RESULT: OK`). The eight pre-existing fixtures stayed green, so what the suite already
  froze is still frozen.
- **L7** — the assertion that matters most was falsified on purpose, not assumed: deleting the
  `mysql-ingress` document alone (14 → 13 documents) turns the suite red with
  `FAIL: every allowed flow is allowed in both directions`, `39 passed, 1 failed`. The file was
  restored to the byte (`md5 d1949690ce3e204108410d8e1003e75f` before and after). A pair check that
  cannot fail is the defect #891's L8 recorded, and this one can.
- **L8** — every offline suite of the repository, one glob: 54 suites, **53 exit 0**, and the only red
  one was `.github/scripts/tests/odd-status-headers.test.sh` with `77 passed, 1 failed`, whose single
  finding was this file's missing `**Status**` paragraph — written above, and the suite is green with
  it. `docker/scripts/tests/image-pins.test.sh` (87 assertions) and
  `.github/scripts/tests/workflow-pins.test.sh` (16) — the two other suites that read `k8s/` — stayed
  green, so the frozen image table and the lint surface are untouched.
- **L9** — one deviation from S3, and the reason it is the better half: the DNS allow has no ingress
  half. The receiving side of a NetworkPolicy can only be written from the namespace the receiver
  lives in, and `kube-dns` lives in `kube-system`, which carries no policy from this tree. What the
  suite asserts instead is the exact shape: DNS on UDP **and** TCP 53 from every pod, and no other
  cross-namespace flow anywhere. The policy file and `README.md` both carry the reason.
- **L10** — slice boundary and delivery shape: **990 authored lines over two commits**, past the
  ~400-line slice. The behavior is one commit (the namespace, the short names, the boundary and the
  script's ordering are one story; splitting them would leave a commit whose README describes a policy
  the tree does not have), the suite is the second at 583 lines, and its cost is fixtures — nine, one
  per way each new property can be made false — not prose. The unit therefore ships as **one pull
  request whose slices are those commits**, plus this record as the third: a chain that split the
  suite from the behavior it locks would be tests that review apart from the code they protect.
- **L11** — found while reading the flows and deliberately **not** fixed here, because it is a
  different unit: `keycloak.yml` sets `DB_ADDR: "mysql"`, while the database Keycloak expects
  (`DB_DATABASE: "keycloak"`) is declared by the `keycloak-mysql` manifest and `mysql.yml`'s
  `initdb.sql` creates `order_service` and `inventory_service` only. The policy mirrors the flow the
  manifests declare, which is all #1034 can assert; which MySQL the realm actually uses is a defect
  the tree already had. Reported to the human rather than fixed inside this unit.
- **L12** — not executed here, measured against the environment: `kubectl`, `kind`, `kustomize` and
  `shellcheck` are all absent, so the render behind S4, `kubectl get ns` (S2), the flows (S5), the
  blocked MySQL (S6) and the 10-minute `Ready` (S7) were not run and are not claimed. No YAML parser
  is installed either, so the twelve new documents were checked structurally (indent shape,
  `apiVersion`/`kind`/`metadata`/`spec` present, no tabs), not parsed.
