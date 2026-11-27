# k8s: Keycloak reaches the MySQL that declares its database

**Status**: implemented — Keycloak's `DB_ADDR` names the MySQL that declares `MYSQL_DATABASE: "keycloak"`,
the MySQL nothing read is gone instead of staying deployed, the NetworkPolicy names the surviving flow,
and the suite fails on the shape of the defect (a Service a ConfigMap names that does not declare the
database that ConfigMap asks for) with its rejecting fixture. The tree declares seven Deployments
instead of eight, which is what S2 requires and what S5's wording forbids: the contradiction is named
in L3 instead of being left for a reader to find.

## Specs

- **S1** — "`grep -n 'DB_ADDR' k8s/manifests/infra/keycloak.yml` names the Service whose ConfigMap
  declares `MYSQL_DATABASE: "keycloak"`, not `mysql`." (issue #1054, acceptance criterion, verbatim)
- **S2** — "Either `keycloak-mysql` is the one Keycloak uses, or it stops being deployed — one MySQL per
  workload, and `kubectl get deploy -n life-control` shows no unreferenced database." (issue #1054,
  acceptance criterion, verbatim). **This is the criterion that removes a Deployment:** the tree
  declares two MySQL servers, the `mysql` one creates `order_service` and `inventory_service`, and
  nothing in this repository reads either database (L2-7).
- **S3** — "`.github/scripts/tests/k8s-manifests.test.sh` fails if the Service a workload names in its
  ConfigMap does not exist in the tree, or does not declare the database that same ConfigMap asks for.
  It passes on the fixed tree." (issue #1054, acceptance criterion, verbatim)
- **S4** — "`k8s/manifests/network-policy.yml` still allows the flow that remains and stops naming the
  one that goes away; the suite's pair assertion stays green." (issue #1054, acceptance criterion,
  verbatim)
- **S5** — "The rest of the tree is untouched: the eight Deployments, the image tags frozen by
  `docker/scripts/tests/image-pins.test.sh` and the namespace declared by #1034." (issue #1054,
  acceptance criterion, verbatim — see L3).

## Tasks

| ID  | Spec   | Route                                                | Commit     |
| --- | ------ | ---------------------------------------------------- | ---------- |
| T1  | S1,S2  | inline — `DB_ADDR`, the dead manifest, its secret     | this slice |
| T2  | S4     | inline — the two policy flows and their prose         | this slice |
| T3  | S3     | inline — the new assertion and its fixture            | this slice |
| T4  | S2     | inline — the frozen tables that counted eight         | this slice |
| T5  | S5     | inline — every written count that this changes        | this slice |

## Log

- **L1** — the human's request, after asking what was pending: `con que seguimos?`, then the choice of
  `#1054 — el MySQL que Keycloak no usa` over verifying the cluster, closing #1056 or the #1035 leftover.
- **L2** — the facts, from a read-only exploration of `main` at `d984c8d5`:
  1. `k8s/manifests/infra/mysql.yml` creates `order_service` and `inventory_service` (`:163-167`) and its
     only environment is `MYSQL_ROOT_PASSWORD` from `mysql-secrets` (`:89-93`).
  2. `k8s/manifests/infra/keycloak-mysql.yml` declares `MYSQL_DATABASE: "keycloak"` (`:167`) and
     `MYSQL_USER: "keycloak"` (`:168`), consumes `keycloak-mysql-secrets` (`:86-106`), and reaches its
     database without any initdb SQL — the image creates it from those two env keys.
  3. `k8s/manifests/infra/keycloak.yml` declares `DB_ADDR: "mysql"` (`:132-137`), `DB_DATABASE:
     "keycloak"` (`:138`), `DB_USER: "keycloak"` (`:139`).
  4. `k8s/manifests/network-policy.yml` allows exactly two flows, both selecting `app: mysql`:
     `keycloak -> mysql:3306` egress (`:145-166`) and the ingress twin (`:281-296`). Its header
     (`:11-13`) *documents* the defect: "`keycloak-mysql` is a second MySQL in the same tree that no
     manifest reads, and it stays unreachable." No policy selects `app: keycloak-mysql`.
  5. `k8s/kind/create-secrets.sh` produces `mysql-secrets`, `keycloak-mysql-secrets` and
     `keycloak-secrets`, five key pairs in total (`:126-142`), and the suite freezes that count.
  6. The suite freezes: eight Deployments (`:722-727`), the flow set (`:645-658`), identities
     (`:861-870`), root filesystems (`:878-892`), readiness (`:895-902`), prepare-data (`:904-910`),
     the "other five Deployments" comment (`:1310`), and four fixtures that mutate `mysql.yml`
     (`:1519`, `:1578`, `:1599`) or `keycloak-mysql.yml` (`:1605`).
  7. **Nothing reads `order_service` or `inventory_service`**: a case-insensitive repo-wide search finds
     only the SQL that creates them and one prose line in `odd/tasks/k8s-namespace-netpol.md:143`.
  8. `mysql:8.3.0` stays used after deleting `mysql.yml`, by `keycloak-mysql.yml:30,49` and
     `k8s/kind/kind-load.sh:2,10`, so the frozen image table stays satisfied (no unused entry).
  9. The count is written in `.github/scripts/tests/k8s-manifests.test.sh:723,727,1336` and
     `README.md:371` ("los ocho workloads"); `kustomization.yaml:7` and `network-policy.yml:24` already
     say "seven", so after this unit those two stop being wrong.
- **L3** — the contradiction S2 and S5 carry between them, resolved and not hidden: S5 asks that the
  eight Deployments stay untouched, S2 asks that no database stay unreferenced. Both cannot hold, and S2
  is the one that names the defect, so `mysql.yml` is deleted and the tree declares **seven**. What S5
  still holds is its substance: the seven surviving workloads keep their hardening, namespace and
  plumbing, and the frozen image tags do not move. The design chosen is the issue's first option —
  `keycloak-mysql` is the one Keycloak uses — over the alternative of teaching `mysql` a `keycloak`
  database and user, because the second would move a password between two Secrets and add SQL to a
  server whose only remaining purpose was the databases nobody read.
- **L4** — the implementation (delegated to a worker with this record as its spec) and its independent
  verification. What moved: `DB_ADDR` to `keycloak-mysql`; `mysql.yml` deleted with its `resources:`
  entry; `mysql-secrets` dropped and the script's own header moved from three Secrets to two; both
  policy flows and the policy object renamed `mysql-ingress` -> `keycloak-mysql-ingress` (beyond T2's
  letter, accepted: no check reads policy names, and a policy called `mysql-ingress` selecting
  `app: keycloak-mysql` would be the stale name this file exists to avoid); the reader
  `db_addr_facts`/`db_addr_findings` plus the tree check and two fixtures; and the seven frozen tables
  that counted eight, each losing exactly one row. Measured after it: `k8s-manifests` **70 passed, 0
  failed**; `image-pins` **87 passed, 0 failed**.
- **L5** — the numbers explained instead of trusted. Against an `origin/main` archive with no `.env.*`
  files the same two suites report **67** and **87**, so the deltas are **+3 checks** (the tree check
  and two fixtures) and **0 lost**; the anchor reports 114 for `image-pins` only because its `.env.dev`,
  `.env.staging` and `.env.prod` are materialized there (27 per-file pin assertions), which a fresh
  worktree does not have. The `k8s-manifests` PASS-set diff contains only the intended count and label
  rewrites.
- **L6** — the checker was proved on the defect itself, not only on its fixtures: restoring
  `infra/mysql.yml` from `origin/main` and putting `DB_ADDR: "mysql"` back makes the suite print
  `ConfigMap keycloak-realm-config asks Service mysql for DB_DATABASE "keycloak" and no ConfigMap behind
  that Service declares MYSQL_DATABASE "keycloak"` and fail. Shape (b) — the Service exists, the database
  does not — is the one that shipped, and the one a Service-existence check alone reads as clean.
- **L7** — cross-unit note, for whoever merges: this branch and `fix/k8s-gateway-arm64` (issue #1056)
  both edit `README.md` and `.github/scripts/tests/k8s-manifests.test.sh`, and neither overlaps anywhere
  else. Whichever lands second rebases over the other; the k8s tree, the frozen image table and the
  Docker side are disjoint.
