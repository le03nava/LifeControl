# k8s-manifests — one manifests tree, a renderable entry point, and no credentials in git

Issue #891 (Unit 1 of a three-unit chain; #1034 and #1035 depend on this tree). Branch
`fix/k8s-manifests`, worktree `~/workspace/LifeControl-worktrees/fix-k8s-manifests`, base
`origin/main` at the merge of #1033.

**Status**: implemented — the three units this record covers are the first of the chain; the
namespace/NetworkPolicy unit (#1034) and the hardening unit (#1035) are not part of it and stay open.
Nothing here claims delivery; the dated evidence log at the bottom of this file carries that.

## Specs

The user's own words, verbatim, opening the unit:

- **S1** — «puedes revisar el issue 891»
- **S2** — «ok que sugieres?»
- **S3** — «dale con 891»

The authorization S3 covers the scope described to the user immediately before it, which is issue
#891 as rewritten. Its acceptance criteria are quoted here verbatim from the issue body, because they
are the rules this unit is verified against:

- **S4** — `kubectl kustomize k8s/manifests` renders with exit code 0 and the output contains exactly
  **8** `kind: Deployment` documents (7 infra + `applications/api-gateway.yml`).
- **S5** — `find k8s/manifests -maxdepth 1 -type d` lists exactly `applications` and `infra`;
  `k8s/manifests/infrastructure/` no longer exists.
- **S6** — `grep -rn "GF_AUTH_ANONYMOUS_ENABLED" k8s/` matches only `"false"`, and neither
  `GF_AUTH_ANONYMOUS_ORG_ROLE` nor `GF_AUTH_DISABLE_LOGIN_FORM` appears anywhere under `k8s/`.
- **S7** — No manifest under `k8s/` contains a `kind: Secret` with a `data:` or `stringData:` key.
  Every Secret is created by `k8s/kind/create-secrets.sh`, which reads no credential from the
  repository and writes none to it. After a fresh run,
  `kubectl get secret keycloak-secrets -o jsonpath='{.data.KEYCLOAK_ADMIN_PASSWORD}' | base64 -d`
  returns a value that is not `admin`.
- **S8** — `k8s/kind/create-secrets.sh` is idempotent and non-destructive to the working tree: a
  second run changes nothing observable, and `git status --short` stays empty before and after.
- **S9** — The commands in `README.md` run as written against a fresh `kind` cluster, and a second
  `kubectl apply` is a no-op.
- **S10** — `.github/scripts/tests/k8s-manifests.test.sh` exists and fails when a `kind: Secret` with
  inline `data:` reappears under `k8s/`, when `GF_AUTH_ANONYMOUS_ENABLED` is anything but `"false"`,
  when a second manifests directory exists, and when an `image:` uses `:main` or `:latest` outside the
  allowlist `image-pins.test.sh:428` already names. It passes on the fixed tree, and the glob of
  `.github/scripts/tests/*.test.sh` picks it up without any new workflow step.

One measured constraint that S4 cannot be executed against on this machine, recorded so it is not
mistaken for a pass: `kubectl`, `kind` and `kustomize` are all absent from the environment
(`command -v` → nothing for the three). S4's render, S7's live `kubectl get secret` and S9's
end-to-end apply are **operator steps**, not assertions this unit ran.

## Tasks

| ID | Task | Spec | Route | Commit |
|----|------|------|-------|--------|
| T1 | Delete `k8s/manifests/infrastructure/`, keeping `infra/` as the single copy | S5 | worker | `fix(k8s): keep one manifests tree` |
| T2 | Add `k8s/manifests/kustomization.yaml` naming the 8 workloads | S4 | worker | same |
| T3 | Turn the Grafana anonymous admin off and drop the two keys that only served it | S6 | worker | `fix(k8s): remove the anonymous grafana admin and the inline credentials` |
| T4 | Add `k8s/kind/create-secrets.sh`; remove every inline `kind: Secret` from the manifests | S7, S8 | worker | same |
| T5 | Fix the documented entry point in `README.md`; correct the Kafka claim in `AGENTS.md` and in the `image-pins.test.sh` comment | S5, S9 | worker | `docs(k8s): name the entry point that exists` |
| T6 | Add `.github/scripts/tests/k8s-manifests.test.sh` | S10 | worker | `test(k8s): freeze the tree's shape` |
| T7 | Run every offline suite plus the new one; report what could not run | all | parent | — |

T7 is the parent's, not a worker's: the verdicts belong to whoever signs the record.

## Log

- **L1** — user: «puedes revisar el issue 891»
- **L2** — user: «ok que sugieres?»
- **L3** — user: «dale con 891»
- **L4** — measured before any edit: `kubectl`, `kind` and `kustomize` are missing from this
  environment, so S4, S7's live read and S9 cannot be executed here. They stay in the specs as
  operator steps and this record does not claim them.
- **L5** — measured before any edit: `docker/.env.template` is unreadable under the session safety
  policy, so any pin comparison reads the compose `${VAR:-default}` instead; `image-pins.test.sh`
  already proves that pair equal.
- **L6** — decision: the surviving tree is `infra/`, not `infrastructure/`. `infrastructure/` breaks
  the `.yml` extension convention with `mysql.yaml` and ships `kafka-mysql.yml`, which is
  `keycloak-mysql.yml` under a name that announces a service the tree does not run.
- **L7** — decision: the security work lives inside the dedupe unit rather than before it. Fixing two
  copies and then deleting one is diff that evaporates.
- **L8** — measured, first run of the new suite: 15 passed / 1 failed. The failure was in the suite's
  own floating-tag checker, whose `while` loop had no input source, so it read empty stdin and the
  green line about the tree was vacuous — the exact defect the fixtures exist to catch, caught by
  them. Fixed, and the fixture that caught it now passes on purpose.
- **L9** — independent verification (read-only, separate agent) returned: 54 of 54 offline suites
  exit 0 with zero `FAIL` lines; `k8s-manifests.test.sh` 21 passed / 0 failed at the time of the
  count; per-spec verdict met for S5, S6, S7, S8 (S8 judged by reading, not by running), S10, and
  met-as-far-as-executable for S4 and S9. Its finding that the suite covered only the first half of
  S7 — no inline Secret, but not that every `secretKeyRef` has a producer — was correct and is now
  the pair `secret_producers` / `unproduced_secrets` plus a rejection fixture. Its other finding,
  that `k8s/kind/*.sh` were mode 644 while `README.md` invoked them as `./k8s/kind/…`, was
  pre-existing and is what made S9 false by construction: the three scripts are executable now and
  the suite asserts it.
- **L10** — not executed in this environment, and not claimed: `kubectl kustomize` (S4's render),
  `kubectl get secret` (S7's live read), `kubectl apply -k` and its no-op re-apply (S9), the
  `create-secrets.sh` runtime (S8), and `shellcheck` on either script. `kubectl`, `kind`, `kustomize`
  and `shellcheck` are all absent here; CI pins the linter at the version its guard job names.
- **L11** — the pointer at the end of L10's evidence is the dated log itself: this record carries no
  commit id on purpose. `.github/scripts/tests/odd-citations.test.sh` requires every hex token that
  resolves as a commit in this repository to be an ancestor of `origin/main`, and a branch commit is
  not one, so the delivery evidence belongs to the pull request and to `git log`, not to a frozen
  line here.
- **L12** — slice boundary and delivery shape. The unit is five commits on `fix/k8s-manifests`: keep
  one tree and its entry point; remove the anonymous admin and the inline credentials; name the entry
  point that exists; freeze the tree's shape in a suite; record this unit. The first commit measures
  ~2.9k changed lines, which is past the ~400-line review slice, and the reason is that ~2.9k of them
  are **deletions** of a near-clone: a tree that duplicates seven workloads has no smaller way to stop
  duplicating them. Deletions review by reading the boundary, not by reading the lines, so the whole
  chain ships as **one pull request whose slices are those commits** rather than as a chain of PRs —
  each slice after the first depends on the tree the first one produces, and a chained PR that cannot
  be reviewed on its own is a second review of the same change.
