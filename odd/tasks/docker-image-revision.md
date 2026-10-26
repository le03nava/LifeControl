# ODD feature: docker-image-revision

**Repository**: LifeControl — `docker/` only: `scripts/_common.sh`, `scripts/deploy.sh`, a new static suite in
`scripts/tests/`, and the Docker Scripts section of the root `AGENTS.md`. No application code, no schema, no
endpoint, no API contract, no image pin moves.
**Status**: implemented — the revision stamp, the `version` command, the drift report and the suite are the
branch's content. Nothing is deferred; the delivery steps (issue, pull request, review) are the maintainer's.
**Created**: 2026-10-25 · **Risk**: **item 5 (delivery/environment)** — `deploy.sh` is the start path of every
environment, and `health` is the gate a deploy is trusted on, so a defect here reports a green stack that is
running older code. No data, no security surface, no contract others consume: the only published shape is a new
`version` command and one extra warning line, and both are additive.

## Specs

**S1** — *the user, 2026-10-25, verbatim*: «ves alg que se pueda mejorar para checar la version de la imagen_»

**S2** — *the user, 2026-10-25, verbatim*: «ok si implementa los 5» — acceptance of the five improvements
proposed in the same turn, reproduced here as the scope of record:

1. **`deploy.sh <env> version`** — the command that answers "which code is running": for each locally built
   image, its image ID, the revision it was built from, and whether that revision is the commit at `HEAD`.
2. **A stale-revision report on the start paths** — `deploy.sh <env> up` and `start` with `SKIP_BUILD=true`
   today reach a green `health` over images built from any commit. The report warns, never fails by default,
   and one opt-in switch makes it a failure — the shape `report_stale_image_pins` already established.
3. **An honest stamp** — `build_images` stamps `git rev-parse HEAD` and nothing else, so an image built from a
   dirty tree is indistinguishable from the clean commit. The stamp carries the dirty state, and a missing or
   `unknown` label is never evidence of a match.
4. **Deterministic image identity** — the three locally built images are addressed by three different naming
   conventions today. The check reads the image a container actually runs from compose instead of hardcoding a
   name per service.
5. **A suite that freezes the contract** — nothing today asserts that the app images carry a revision or that
   the start paths expose it, so every item above can be lost to a refactor in silence.

Explicitly **out of scope**: moving any image pin (that is `odd/tasks/docker-infra-image-refresh.md`), changing
an image's name, and any application code.

## Tasks

- **T1** (S1, S2-3, S2-4) — helper library: the revision annotation name, the current commit, the dirty state,
  the image a service's running container uses, and the pure verdict. Route: inline. Commit: `70b1e30`.
- **T2** (S2-1, S2-4) — `deploy.sh <env> version`, its exit contract and its `usage` line. Route: inline.
  Commit: `f8134aa`.
- **T3** (S2-3) — `build_images` stamps the dirty state alongside the commit. Route: inline. Commit: `70b1e30`.
- **T4** (S2-2) — the drift report, wired once into `health_check` so `start`, `up`, `restart` and `health`
  share it, with the opt-in strict switch. Route: inline. Commit: `f8134aa`.
- **T5** (S2-5) — `docker/scripts/tests/image-revision.test.sh`: every verdict, the dirty stamp, the flag truth
  table, the wiring and the exit contract, offline. Route: inline. Commits: `70b1e30`, `f8134aa`.
- **T6** (S2-1, S2-2) — the Docker Scripts section of `AGENTS.md` and the `deploy.sh` help text. Route: inline.
  Commits: `f8134aa`, and the closing slice for the `AGENTS.md` rows.

## Log

- **L1** — *the user, 2026-10-25, verbatim*: «ves alg que se pueda mejorar para checar la version de la imagen_»
- **L2** — *the user, 2026-10-25, verbatim*: «ok si implementa los 5» — all five, in one unit.
- **L3** — evidence, 2026-10-25. The gap that motivates the unit, measured on this repository: the three app
  Dockerfiles bake `org.opencontainers.image.revision` and **nothing reads it** — `grep -rlnE
  'opencontainers.image.revision|GIT_COMMIT' docker/ .github/` returns the compose file, `deploy.sh` and two
  suites, and none of them inspects an image. Checking the revision required a hand-written
  `docker image inspect` in the previous session, which is not a procedure. Same measurement:
  `deploy.sh:356` (`up)`) and `deploy.sh:338` (`SKIP_BUILD=true`) reach `start_services` + `health_check` with
  no comparison at all, while `verify_artifact_freshness` (`deploy.sh:154`) guards only the JAR feeding `docker
  build`, never the image feeding the container.
- **L4** — decision, 2026-10-25. T3 needs **no Dockerfile change**: the three files already stamp
  `${GIT_COMMIT}`, so the dirty state travels in the value deploy.sh exports (`<sha>-dirty`, the `git describe`
  convention) and the label keeps one shape. A second build arg and a second label would have to be read by
  every consumer for the same fact.
- **L5** — decision, 2026-10-25, and a deliberate narrowing of item 4. Giving `api-gateway` and
  `lifecontrol-api` an explicit `image:` key to make their names deterministic would need the environment in the
  tag (`${API_GATEWAY_IMAGE:-…}` with a nested `${COMPOSE_PROJECT_NAME:-…}` fallback), or the three
  environments would share one local tag and a prod build would overwrite dev's image. Compose already names
  the image `<project>-<service>`, so the check asks compose what the container runs instead of asserting a name
  of its own — the same determinism, without a new failure mode in the corner where compose is invoked without
  `--env-file`.
- **L6** — decision, 2026-10-25. One writer, inline, no subagent: commit `B` reads helpers commit `A`
  introduces, so the two are dependent rather than independent, and the Writer rule asks for a delegated
  worker only for two or more independent units that do not depend on each other.
- **L7** — evidence, 2026-10-25, and a correction to this record's own first draft. The first commit was
  written as one slice and measured **706 authored changed lines**, past the ~400-line advisory budget, so it
  was re-cut by concern and re-committed: `A` = the honest stamp plus the reader plus the suite that pins them
  (`347` lines), `B` = the report, the `version` command, the `health` guard and the rest of the suite (`463`
  lines), `C` = this contract in `AGENTS.md` and this record. Measured with `git show --stat` per commit. The
  unit stays one pull request, deliberately over the advisory budget: **531 of its ~880 lines are the offline
  suite**, which pins five distinct contracts (verdict, stamp, readers, report, exit code) in one file, and the
  two code commits are the same contract read and written — `B` has no meaning without `A`. The chain the
  budget usually asks for exists for *independent* slices, and splitting here would buy two issues and two
  reviews for one readable unit. Reviewer order: `A`, then `B`, then `C`; within `A`, `_common.sh` first, then
  the one hunk in `build_images`.
