# ODD task: docker-images-rebuild

**Repository**: LifeControl — `life-control-api/`, `api-gateway/`, `life-control-app-angular/`
**Status**: in progress
**Created**: 2026-09-17
**Origin**: read-only audit of the dev Docker artifacts against `main` (`a72de5f`) revealed the API,
gateway and web images were all built from older sources while reporting `healthy`.

## Objective

Rebuild the three dev images from current sources and recreate the dev stack so the running system
matches `main`:

1. `lifecontrol-dev-lifecontrol-api:latest` — stale by 4 commits,
2. `lifecontrol-dev-api-gateway:latest` — stale by 3 commits,
3. `life-control-app-angular:latest` — stale by 6 commits.

## Problem

`healthy` is not evidence of freshness. Each image bakes its artifact at build time and nothing
in the stack compares that artifact with `HEAD`:

| Artifact | Built | Missing commits |
| --- | --- | --- |
| `lifecontrol-api` | 2026-09-15 00:00 | `221ff37` (V4 seed), `f61bcc1` (domain events), `ced2564` (store areas + V5), `7278a86` (flat lookup) |
| `api-gateway` | 2026-09-10 16:48 | `43c1f2c` (env URIs), `fec1e69` (JWT/CORS hardening), `7278a86` (store-areas route) |
| `web-app` | 2026-09-16 17:38 | `959f3f6`, `38a14de`, `89d9479` (09-16), `ed331a4`, `2262452`, `68e2600` (09-17) |

Evidence collected read-only:

- the API image jar has **zero** `StoreArea` classes while the host jar has 10;
- `flyway_schema_history` in `lifecontrol-postgres` stops at version **3** — V4/V5 never applied;
- the gateway image jar lacks `gateway/config/JwtIssuerAllowlistValidator.class` and its
  `routes/Routes.class` has no `/api/store-areas/**` route constant;
- unauthenticated `curl` probes are worthless here (Spring Security answers `401` for every path,
  including nonexistent ones).

## Why this shape

- **Rebuild from source, do not pin tags.** The images are built from the working tree
  (`compose build`), and the Java images `COPY build/libs/*.jar`, so the host `bootJar` must run
  first for `api-gateway` and `life-control-api`.
- **No host build for the front.** `life-control-app-angular/Dockerfile` is multi-stage and runs
  `npm ci` + `npm run build` inside the image, so `compose build web-app` is sufficient. Running
  `npm run build` on the host first (as `deploy.sh build_services` does) would be duplicated work.
- **Reuse the canonical compose invocation.** Same files and env file `_common.sh` resolves:
  `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml --env-file docker/.env.dev`.
- **`-x test` for parity with `deploy.sh`.** The jars are built the way the deploy script builds
  them. Tests are NOT run by this task; that is recorded as a skipped check, not as a pass.

## Scope

In scope: build the two jars, build the three images, recreate the dev containers, verify the
resulting artifacts and runtime state.

Out of scope: source code changes, commits, pushes, test runs, and any `staging`/`prod` environment.

## Constraints (non-negotiable)

- Dev environment only (`docker/.env.dev`, `dev` build profile).
- No source file is modified by this task.
- The API container restart applies Flyway `V4`/`V5` to the dev database; both are additive
  (a seed insert and a new table), which is accepted as part of the rebuild.
- Every claim of success must come from an observed check, never from a build log line alone.

## Task log

- [x] Audit image staleness read-only (image ids, jar hashes, jar class contents, Flyway history,
      commit dates per component).
- [x] Confirm the front image is stale and that its Dockerfile builds the app inside the image.
- [x] Build `api-gateway` and `life-control-api` jars (`./gradlew bootJar --no-daemon -Pprofile=dev -x test`).
      API jar rebuilt (`12:12:55`); gateway jar already `UP-TO-DATE` from `2026-09-17 00:19`.
- [x] Build the `api-gateway`, `lifecontrol-api` and `web-app` images. All three `Built`.
- [x] Recreate the dev containers (`up -d`) and let Flyway apply V4/V5.
- [x] Verify (see below).

## Verification

| Check | Evidence | Result |
| --- | --- | --- |
| API jar inside the image is the one just built | `sha256 a3975e6d…` host == image | pass |
| Gateway jar inside the image is the one just built | `sha256 1dc0caf1…` host == image | pass |
| `V4`/`V5` applied by the new jar | `flyway_schema_history` ranks 4–5, `success = t`, `2026-09-17 18:13:47` | pass |
| Flat lookup endpoint is in the running build | image `Routes.class` contains `/api/store-areas/**`; `store_areas` table created by `V5` | pass |
| Front image actually changed | old image `4010ac5719f6` has **0** chunks containing `store-areas`, new image has **6** | pass |
| Runtime health | `lifecontrol-api`, `api-gateway`, `web-app` all `healthy`; `web-app /healthz` → `200` | pass |
| Tests | not run — jars built with `-x test` for parity with `deploy.sh` | **skipped** |
| `staging`/`prod` | untouched | **not applicable** |

Note: `curl` is not usable as a freshness probe here — `/actuator/mappings` is not exposed on the
API (only `health` and `prometheus`) and Spring Security answers `401` on every path. Freshness was
established from the jar hashes and the migrations the running container applied.

## Status

Done. The three dev images are rebuilt from `main` (`a72de5f`) and the dev stack runs on them; the
dev database is at Flyway `V5`. No source file was modified and nothing was committed.

## Next step

None required. If the new API surface is to be exercised manually, assign `lc-company-store-read`
to the dev user so `GET /api/store-areas/{areaId}` answers with data instead of `403`.

---

# Round 2 — rebuild against `516348a` (PR #116 merge)

**Trigger**: `main` moved past the round-1 target (`a72de5f`). The frontend slice #116 merged as
`516348a`, plus `dff6626` (store contract hardening) on the backend.

**User decision (scope)**: front + back, then recreate the dev stack. Dev only; `staging`/`prod`
untouched. No source file is modified; nothing is committed.

## Freshness gap at round 2 start

| Image | Built | ID | Missing commits |
| --- | --- | --- | --- |
| `life-control-app-angular:latest` | 2026-09-18 12:40 | `42a790550b5a` | `73113c3`, `788ef16`, `31bfa73`, `a417384`, `fd6b417`, `8f946c5`, `d6ce93b`/`516348a` |
| `lifecontrol-dev-api-gateway:latest` | 2026-09-18 12:40 | `02029e832982` | `dff6626` |
| `lifecontrol-dev-lifecontrol-api:latest` | 2026-09-18 12:40 | `a1d1a8ec9e4d` | `dff6626` |

## Round 2 verification (observed checks only)

| Check | Evidence | Result |
| --- | --- | --- |
| API image jar is the host jar | `sha256` in-image `48e94abc…` == host `48e94abc…` | **pass** |
| Gateway image jar is the host jar | in-image `f3ae30c6…` == host `f3ae30c6…` | **pass** |
| API image carries `dff6626` at resource level | in-image jar has `V8__store_optimistic_locking.sql` (old image: **0**) | **pass** |
| API image carries `dff6626` at class level | `EntityGraph` in `CompanyStoreRepository.class` — host **1**, old image **0**, new image **1** | **pass** |
| Front image carries slice #116 | ASCII probe `actualizada correctamente`: old image **1** context (`Orden`), new image **4** (`\xC1rea`, `Zona`, `caci\xF3n`, `Orden`) — the delta is exactly the three toasts added by `8f946c5` | **pass** |
| Containers run the new images | api `a6d75bf5ea7c` (recreated), web `4100be388431` (recreated); gateway container ID unchanged `1b4fd4b3ead9` | **pass** |
| Flyway reached V8 on the live DB | `flyway_schema_history` ranks 1–8, all `success = t`; `now at version v8` in the API log | **pass** |
| V8 columns exist | `company_stores` / `store_areas` / `store_zones` / `store_locations` each `version bigint NOT NULL DEFAULT 0` | **pass** |
| Runtime health | api, front, gateway all `healthy`; `curl :4200/healthz` → `200`; `curl :9000` → `401` (expected, unauthenticated) | **pass** |
| Tests | not run — jars built with `-x test` for parity with `deploy.sh` | **skipped** |
| `staging`/`prod` | untouched | **not applicable** |

### Resulting images

| Image | Before | After |
| --- | --- | --- |
| `lifecontrol-dev-lifecontrol-api:latest` | `a1d1a8ec9e4d` (12:40) | `a6d75bf5ea7c` (21:27) |
| `life-control-app-angular:latest` | `42a790550b5a` (12:40) | `4100be388431` (21:28) |
| `lifecontrol-dev-api-gateway:latest` | `02029e832982` (12:40) | `02029e832982` — **byte-identical, no rebuild needed** |

**Why the gateway image did not change.** `dff6626` touched only `life-control-api/`; no `api-gateway/`
source has changed since its jar was built (09-18 12:38), Gradle reported `bootJar UP-TO-DATE`, and the
rebuilt image kept the same content hash. `docker compose up -d` therefore left its container
`Running`. This is the expected outcome, not a skipped step.

### Probe methodology (two traps worth keeping)

1. **Commit dates are not freshness evidence.** `git log --since=<jar mtime>` lists `dff6626` as
   postdating the API jar, yet the jar contains its artifacts — the working tree held those changes
   before the jar was built and the commit was only created later. Freshness must come from content:
   Gradle's content-hash `UP-TO-DATE`, the jar sha256, and the presence of a known-new artifact
   (V8, `EntityGraph`).
2. **esbuild escapes non-ASCII in the bundle.** `grep 'Área actualizada'` returns 0 even in a bundle
   that contains it; the bytes are `\xC1rea`. Probe with an **ASCII-only** needle and print the
   surrounding byte context. The first front probe (`Salir sin guardar`) was also useless because the
   purchases guard already carried that copy.

### Unrelated in-flight work observed

`life-control-api/skills/project-conventions/` appeared untracked on `main` at 21:25 from another pi
session's `project-conventions-skill` feature (its doc lists T1 done, T2–T5 pending). It is **not**
part of this rebuild, was **not** in PR #116 (`git ls-tree -r HEAD | grep skills/` is empty), and was
left untouched. That session was notified that its recorded branch `feat/store-ux-a11y` no longer
exists.
