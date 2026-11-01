# ODD feature: deploy-clean-bind-mounts

**Repository**: LifeControl — `docker/` only: `scripts/_common.sh`, `scripts/deploy.sh`, `scripts/cleanup.sh`, a
new suite in `scripts/tests/`, and the Docker Scripts sections of the root `AGENTS.md` and `README.md`. No
application code, no schema, no endpoint, no image pin moves.
**Status**: implemented — the honest `clean` message, the `--data` path behind a second confirmation, the shared
bind-mount helpers, the guard in front of the deletion, the suite and the docs are the branch's content. Nothing
is deferred. The branch is delivered as two review slices (D5), boundary at `e13af2a7`.
**Created**: 2026-11-01 · **Risk**: **item 1 (irreversible data loss)** — the unit adds `--data`, the only path
that deletes `$VOLUMES_ROOT` from a deploy command, and it rewrites the messages of a command users trust to
have destroyed their databases. `clean` itself only touches Docker objects; the host directories are the state,
and an off-by-one in the guard would delete a production datadir.
**Issue**: #723.

## Specs

**S1** — *the user, 2026-11-01, verbatim*: «puedes continuar con fix(docker): deploy.sh clean reports 'volumes
cleaned' but leaves the bind-mounted data directory intact #723»

**S2** — *issue #723 (parent), "What happens", verbatim*:

> `./docker/scripts/deploy.sh dev clean` prints `[WARNING] This will remove all volumes and data!` and then
>
> ```
> [SUCCESS] Services and volumes cleaned!
> ```
>
> and it is false for the database. Measured immediately after it ran:
>
> - the containers were still **Up** (`docker ps` listed the whole stack, the API still restarting);
> - `docker/volumes-dev/postgres/lifecontrol/data/18/docker` was still there, `root`-owned, dated **Sep 9** —
>   the old cluster, untouched;
> - it had removed the network **`lifecontrol_default`**, which is not this stack's project
>   (`lifecontrol-dev_default` at the time).

**S3** — *issue #723, "Why it matters", verbatim*:

> This is not cosmetic. In the same session I trusted that message, deleted nothing else, and the next `start`
> came up against a cluster that had survived — with the pre-fix roles — which read exactly like "the fix does
> not work". The failure cost two extra cycles and one wrong diagnosis before the `PG_VERSION` timestamp proved
> the data directory was older than the change under test.
>
> The mechanical reason: the database directory is a **bind mount**
> (`${VOLUMES_ROOT}/postgres/lifecontrol/data:/var/lib/postgresql`), and `docker compose down --volumes` removes
> **named volumes** only. Bind mounts are never touched, but the success message does not distinguish them. The
> same holds for the other bind-mounted directories under `VOLUMES_ROOT` (keycloak-postgres, keycloak realms,
> loki, tempo, prometheus).

**S4** — *issue #723, "Proposed fix" 1, verbatim*: «Make the message honest about what it did: name the
bind-mounted directories it left in place, or report them on the next `start` as "using an existing data
directory from <date>".»

**S5** — *issue #723, "Proposed fix" 2, verbatim*: «Optionally remove them behind an explicit flag, with the
destructive warning restated — `clean` is already gated by a confirmation prompt, so a second explicit
confirmation for `$VOLUMES_ROOT` would be proportionate.»

**S6** — *issue #723, "Proposed fix", project-name paragraph, verbatim*: «And the project-name detail: `clean`
should resolve the compose project the same way `start` does (`COMPOSE_PROJECT_NAME` from the env file in use),
so it stops removing a network that belongs to nobody.»

**S7** — *issue #723, "Acceptance", verbatim*:

> - After `deploy.sh <env> clean`, either the data directory is gone or the output says in so many words that it
>   is still there and where.
> - A test in `docker/scripts/tests/` pins the message against the bind-mount case.

## Tasks

- **T1** (S4, S5, S6) — `_common.sh` bind-mount helpers (`volumes_root_host_path`, `volumes_root_is_outside_repo`,
  `volumes_root_entries`, `volumes_root_is_deletable`, `volumes_root_env_dir_name`), `deploy.sh clean` rewritten
  around them, `cleanup.sh local` resolving through the same helpers, help text, `AGENTS.md` and `README.md`.
  Route: inline. Commit: `e13af2a7`.
- **T2** (S2, S3, S7) — `docker/scripts/tests/clean-bind-mounts.test.sh`, 127 assertions over a throwaway tree
  with a stubbed `docker`: message, argv, data on disk, refusal matrix, second confirmation, both
  `cleanup.sh local` verdicts. Route: inline. Commit: `b83a41b7`.
- **T3** (S4, S5) — this record. Route: inline. Commit: the one that adds this file.

Out of scope, recorded here so the next session does not re-open it: reporting the surviving data directory on
`start` as well (S4's second alternative — the clean-time report answers the same question), deleting an
absolute `VOLUMES_ROOT` from a script (deliberately refused, D3), and giving `cleanup.sh local` a confirmation
prompt of its own.

## Log

- **L1** — *the user, 2026-11-01, verbatim*: «puedes continuar con fix(docker): deploy.sh clean reports 'volumes
  cleaned' but leaves the bind-mounted data directory intact #723»
- **L2** — Decision D1, the flag: `deploy.sh <env> clean [--data]`, not an env switch, because the destructive
  half is a different verb and an env var left exported in a shell is how the wrong command runs later.
- **L3** — Decision D2, no second deletion implementation: the bind-mount path resolution and the entry listing
  live in `_common.sh`, and `cleanup.sh local` consumes the same helpers. Two `rm -rf` bodies for one directory is
  how one of them drifts past the other.
- **L4** — Decision D3, an absolute `VOLUMES_ROOT` (prod, `/var/lib/lifecontrol/volumes`) is refused by `--data`,
  matching the decision `cleanup.sh cleanup_local` already takes: production data does not get deleted by a
  convenience command whose env file was materialized months ago. In prod the app database is still a bind mount
  (`docker-compose.prod.yml`), while keycloak-postgres, loki, prometheus, tempo and grafana use named volumes
  there — so in prod `down -v` **does** delete data, which is what the first warning says.
- **L5** — Evidence, project name (S6): already satisfied at `HEAD` by `compose_run`, which passes
  `--env-file "$ENV_FILE"` to every compose call, so `down -v` resolves `COMPOSE_PROJECT_NAME` from the env file
  in use — `docker compose ... --env-file docker/.env.dev config` reports `name: lifecontrol`, the project the
  running containers are labelled with. With the key deleted, compose falls back to `name: docker`; the message
  now says the key is not set instead of printing an empty project name. The suite pins the argv so a future
  `clean` cannot drop the flag.
- **L6** — Decision D6, the guard (from the independent verifier's findings F1/F2, both real): `--data` accepts
  only the environment's own `./volumes-<env>` — a real directory, not a symlink, resolving to
  `$DOCKER_DIR/volumes-<env>`. The first implementation accepted any strict descendant of `docker/`, and the
  verifier measured `VOLUMES_ROOT=./secrets` deleting `docker/secrets` and `./volumes-dev/../volumes-staging`
  deleting another environment's live database. A descendant test cannot tell those from the intended directory;
  exact equality with the directory `setup-env.sh` writes can.
- **L7** — Decision D7 (verifier findings F3/F4/F5): EOF at either prompt cancels with a message instead of
  aborting under `set -e` with no output; a missing `COMPOSE_PROJECT_NAME` is named as missing; a root that does
  not exist is reported as missing instead of "does not resolve".
- **L8** — Evidence, RED and GREEN over the same suite: with the implementation of `e13af2a7` in place and the
  original strict-descendant guard restored, the suite reports **101 passed, 26 failed**, and the failures
  include `Removed …/docker/secrets` and `Removed …/docker/volumes-staging` — the two deletions D6 exists to
  prevent — plus `expected '0', got '1'` for the EOF cancel and the `project ''` message. With the final guard:
  **127 passed, 0 failed**, `exit 0`.
- **L9** — Evidence, independent verification (read-only `gentle-ai-verify`, working on a scratch copy): S2, S4,
  S5, S6, S7 each **pass**, no blocking finding, and the baseline replayed from `origin/main` verbatim
  (`Services and volumes cleaned!` with the data directory still on disk). Follow-ups it raised are F1–F5, all
  addressed above. What it could not prove: the TOCTOU window between the guard's `pwd -P` and `rm -rf` (no
  realistic trigger), and anything needing a live daemon — no real `docker compose down -v` was ever run against
  this checkout.
- **L10** — Evidence, suites: 24 suites under `docker/scripts/tests/` and 11 under `.github/scripts/tests/` all
  green after the change, including the ODD header audit over this record. `shellcheck` is not installed on this
  machine; the `Shell & Artifact Integrity` job runs it in CI.
- **L11** — Decision D5, delivery: the branch is **600 authored lines in 2 commits**, past the ~400-line review
  budget, so it is delivered as two stacked slices — slice 1 `e13af2a7` (behaviour + docs, 222 lines), slice 2
  `b83a41b7` (the suite, 378 lines) plus this record. The suite is a coherent second slice: it runs the real
  scripts and asserts the outcome, so it can be reviewed on its own.
