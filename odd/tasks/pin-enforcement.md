**Status**: implemented — the four workflow scopes, the five SHA-pinned actions, the floating-tag sweep, the
two Dockerfile base images in the pin source, and the two suites that fail on drift (workflow and image pins,
and the Gradle half) are this branch's content. All six acceptance criteria are met, `S6` last: the deploy and
the health probe ran from a clean tree, and `L11` records what they printed. Nothing is deferred.
**Created**: 2026-11-14 · **Risk**: high — item 5 (CI and deploy configuration: a wrong pin or a narrowed
token decides what runs in every job) and item 6 (the whole point is that no test caught this drift).
**Issue**: #896 · **Branch**: `chore/pin-enforcement` · **Spec**: this file, `## Specs`.

## Specs

Verbatim acceptance criteria of issue #896. S3 and S4/S5 are the enforced halves; S1 and S2 are the
narrowing halves; S6 is the proof that narrowing did not break the stack.

- **S1**: `permissions: contents: read` appears at workflow level in all four workflows, and any job that
  needs more states why in a comment.
- **S2**: Every third-party action is pinned to a full commit SHA with the human-readable version in a
  trailing comment.
- **S3**: A grep for `:latest`, `:stable` and `:main` across `docker/`, `k8s/` and `.github/` returns only
  documented dev-only exceptions.
- **S4**: The two Dockerfile base images are declared in the docker environment template and checked by
  the pin suite exactly like the compose services, so bumping one without the other fails.
- **S5**: A suite fails when a `FROM` line, a k8s image reference or a Gradle plugin version drifts from
  the pin source, and passes on the current tree.
- **S6**: `./docker/scripts/deploy.sh dev start` and `dev health` still pass.

Non-goals, verbatim: "No image upgrades: this issue makes the existing versions enforceable, it does not
change them. No vulnerability scanner." Dockerfile base images in the two Java modules are named by S4; the
Angular `nginx:alpine` base is a `FROM` line, so S5 covers it and S4's "the two" stays the two Java ones.

## Tasks

| ID | Spec | What | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1 | `permissions: contents: read` at workflow level in `api-ci`, `angular-ci`, `gateway-ci`, `docker-build-integrity`; every job needing more documents why | inline | |
| T2 | S2 | The five third-party actions pinned to commit SHAs with the version in a trailing comment | inline | |
| T3 | S3 | `:latest` / `:stable` / `:main` swept out of `docker/`, `k8s/`, `.github/`, each surviving occurrence a documented dev-only exception | inline | |
| T4 | S4 | The two Java Dockerfile base images declared in `docker/.env.template` and the compose defaults, compared by the pin suite like the compose services | inline | |
| T5 | S5 | The suite: `FROM` lines, k8s image references and Gradle plugin versions checked against the pin source, green on the current tree | inline | |
| T6 | S1–S5 | The documented pin policy (`AGENTS.md`, `.env.template` comments) stops contradicting the tree | inline | |
| T7 | S6 | `deploy.sh dev start` and `dev health` on the changed compose config | inline | |
| T8 | all | Independent verification of the four suites plus the closed drift assertions | verify | |
| T9 | S5 (Gradle half) | A suite that fails when a wrapper `distributionUrl` or a Gradle plugin version literal drifts from the frozen table, green on today's tree and silent about the patches #894 owns aligning | writer | |

## Log

- **L1** — the human's request, verbatim: «puedes revisar los issues de chore y ver con cual continuamos?»
  and then «ok aprueba el 896 y dale».
- **L2** — #896 approved with `status:approved` on 2026-11-14; the worktree created with
  `./.github/scripts/new-unit.sh chore/pin-enforcement` from `origin/main` (`b3fc461e`).
- **L3** — premise re-measured before any write, so the issue is not implemented against a tree that moved:
  `permissions:` is absent from `api-ci.yml`, `angular-ci.yml`, `gateway-ci.yml` and
  `docker-build-integrity.yml` (present only in `pr-label-guard.yml:28`); `grafana/loki:main` sits in
  `k8s/manifests/infra/loki.yml:17` (and a second copy under `k8s/manifests/infrastructure/`),
  `kind-load.sh:3,11`, `saiupadhyayula007/new-api-gateway:latest` in `k8s/manifests/applications/api-gateway.yml:17`
  and `kind-load.sh:7,15`, `koalaman/shellcheck:stable` in `docker-build-integrity.yml:41` and
  `pr-label-guard.yml:89`, and `life-control-app-angular:latest` as the compose default for `WEB_APP_IMAGE`.
- **L5** — **slice boundary**: the CI half (T1, T2, T3's `.github/` surface, T5's workflow
  assertions) is one commit; the image half (T4, T5's `FROM` and k8s assertions, T3's `docker/` and
  `k8s/` surfaces, T6, T7) is a second, because the two halves are independently reviewable and the
  first one is what makes the second's failures readable. The split is by delivery surface, not by
  convenience: 485 authored lines in the first half, and the second half cannot be smaller than the
  template plus three Dockerfiles plus the k8s sweep.
- **L6** — `docker/.env.template` is unreadable to every file tool in this session: the harness
  safety policy classifies `.env*` as a sensitive path and refuses the read, the same block
  `odd/tasks/docker-infra-image-refresh.md:64-71` recorded. S4 requires **writing** that file, so the
  image half is blocked on an authorization only the human can give, and no bash workaround was used
  to route around the guard.
- **L7** — the human authorized the image half by applying the three keys to `docker/.env.template`
  (`JAVA_BASE_IMAGE`, `NODE_BASE_IMAGE`, `NGINX_BASE_IMAGE`) in this worktree, which is the only
  way they could be written: every file tool in this session is refused that path by the harness
  safety policy. Re-measured after the edit: `image-pins` went from 81 passed / 4 failed — the three
  missing keys plus the `.nvmrc` comparison that cannot run on an empty value — to **85 passed / 0
  failed**, and the 28 suites of `docker/scripts/tests/` and the 16 of `.github/scripts/tests/` are
  all green. `docker compose config` renders the three build args identically in dev and in prod.
- **L8** — **delivery**: three commits, ~1.2k authored lines, and the largest single file in the diff is
  new suite code (360 and 464 lines) rather than production logic. The slices are the CI half (token
  scopes, action SHAs, the shellcheck pin, `workflow-pins.test.sh`), the Gradle pin suite, and the
  image half (base images, k8s sweep, sections 9 and 10 of `image-pins.test.sh`, docs). The `Commit`
  column above stays empty on purpose: citing a branch commit here fails `odd-citations.test.sh`,
  which resolves every citation as an ancestor of `origin/main`. The delivery detail belongs to the
  dated evidence entry written after the merge, not to a header this branch cannot verify.
- **L9** — **S6 is not satisfied yet**, and it is the only acceptance criterion that is neither met nor
  disproved: `deploy.sh dev start` from this worktree shares `COMPOSE_PROJECT_NAME` with the dev stack
  the anchor is running, so running it here rebuilds the containers of an environment this unit does
  not own. It waits on the human's disposition, not on more code.
- **L4** — the five action SHAs resolved from the published tags on 2026-11-14, annotated tags dereferenced
  to commits: `actions/checkout` v7 `3d3c42e5aac5ba805825da76410c181273ba90b1`,
  `actions/setup-java` v6 `d1374f8ac74474200d16ef8ecee22c7ae7a51880`, `actions/setup-node` v7
  `949feb2413d6458794dcd2491c4babbbce0c15c1`, `actions/upload-artifact` v7
  `cf430e030ddbb5b0abf93d22962f4752f3646cd9`, `actions/download-artifact` v7
  `37930b1c2abaa49bbe596cd826c3c89aef350131`.
- **L10** — **`S6` was blocked by the environment, and `L9`'s premise is corrected by measurement.** The dev
  stack `L9` assumed belonged to the anchor was not running from the anchor at all: `docker inspect` showed
  every container of project `lifecontrol` mounting
  `.../LifeControl-worktrees/chore-pin-enforcement/docker/**`, so the environment a deploy from this worktree
  touches was already this worktree's, and the anchor's own state directory (1.7 GB, dated 2026-11-01) was
  never read or written by this unit. Two defects, both independent of the branch, kept the stack red:
  `lifecontrol-api` crash-looped on `FATAL: password authentication failed for user "lifecontrol_owner"`
  because the secret clean-up left `docker/secrets/lifecontrol_app_password` holding `CHANGEME` — which made
  `db-roles.sh dev` refuse to converge, by design and not by accident — and Loki crash-looped on
  `mkdir /loki/chunks: permission denied`, because its bind mount was owned by the host user at mode `755`
  while the 3.7.8 image runs as uid `10001`. The human materialized both passwords and the roles converged
  (`Database roles applied`), and the Loki mount was chowned to `10001:10001`. The repair, not this branch, is
  what made `S6` runnable, so neither finding is scope this unit claims.
- **L11** — **`S6` met, from a tree that is clean.** The first `deploy.sh dev start` went green but stamped
  every application image with the dirty suffix, and the flag came from this unit's own untracked helper for
  the environment repair, not from the branch; the helper was deleted, `git status --short` came back empty,
  and the run was repeated at the branch tip. That second run: `deploy.sh dev start` closed with *All services
  healthy!* — the probe waits on container health, on every Prometheus target being `up`, and on a readable
  Tempo trace, with Tempo's empty probe window treated as a notice — and `deploy.sh dev version` reports
  `match` for `api-gateway`, `lifecontrol-api` and `web-app` while exiting 0. The exit code is the assertion:
  `version` is non-zero on every other verdict.
- **L12** — the fourth commit, `test: assert the two exceptions the verification found unproven`, postdates
  `L8`'s count of three: the independent verification of `T8` found two closed-drift assertions that were
  stated but never exercised, and they were added rather than leaving the suite claiming coverage it did not
  have. No commit ids are cited in this entry on purpose — `L8` records why.
