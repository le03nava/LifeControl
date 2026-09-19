# Domain Index — project-conventions

Shared repository references live here. Component-specific detail lives in `references/api.md` and `references/angular.md`.

## Governance

- `AGENTS.md` — repository-wide norms, skill registry, and component ownership.
- `README.md` — monorepo overview and entry commands.
- `life-control-api/AGENTS.md` — component governance for the Spring Boot API.
- `life-control-app-angular/AGENTS.md` — component governance for the Angular app (RBAC, visibility, dual-mode guard).
- Absent: `CONTRIBUTING.md`, `SECURITY.md`, `CODEOWNERS`, `docs/**` (see Gaps).

## Architecture

- `life-control-api/` — see `references/api.md`; overview in `life-control-api/README.md`.
- `life-control-app-angular/` — see `references/angular.md`; overview in `life-control-app-angular/README.md`.
- `docker/docker-compose.yml`, `docker/docker-compose.override.yml`, `docker/docker-compose.prod.yml` — runtime topology per environment.
- `k8s/manifests/applications/api-gateway.yml`, `k8s/manifests/applications/common-config.yml`, `k8s/manifests/infra/`, `k8s/manifests/infrastructure/` — cluster topology.

## Security

- `docker/secrets/` — the only password channel; secret files materialize from templates.
- `docker/entrypoints/` — wrappers that read secret files and export the expected env vars.
- `life-control-app-angular/src/core/security/`, `life-control-app-angular/src/core/guards/`, `life-control-app-angular/src/core/interceptors/` — web authorization surface.
- Component security detail: `references/api.md`, `references/angular.md`.
- Absent: secret-scanning configuration and SBOM configuration (see Gaps).

## Testing & Quality

- `life-control-api/build.gradle` — Spotless and SpotBugs configuration.
- `life-control-api/config/spotbugs/exclude.xml` — SpotBugs exclusion filter.
- `life-control-app-angular/scripts/check-coverage.mjs` — enforced coverage thresholds.
- `life-control-app-angular/eslint.config.js` — lint conventions.
- Component test detail: `references/api.md`, `references/angular.md`.

## CI/CD & Release

- `.github/workflows/api-ci.yml` — API pipeline and its test-results artifact.
- `.github/workflows/angular-ci.yml` — web pipeline and its coverage artifact.
- `.github/dependabot.yml` — dependency automation.
- `docker/scripts/deploy.sh`, `docker/scripts/cleanup.sh` — deploy and cleanup entry points.
- Component release detail: `references/api.md`, `references/angular.md`.

## Operations

- `docker/scripts/setup-env.sh`, `docker/scripts/validate-env.sh` — environment setup and validation.
- `docker/scripts/keycloak-setup.sh` — realm, client, and role provisioning.
- `k8s/kind/kind-config.yaml`, `k8s/kind/create-kind-cluster.sh`, `k8s/kind/kind-load.sh`, `k8s/kind/delete-kind-cluster.sh` — local cluster lifecycle.
- Component operations detail: `references/api.md`, `references/angular.md`.

## Dispatch

| Component | Reference | Gate command |
| --- | --- | --- |
| `life-control-api` | `references/api.md` | `./gradlew spotlessCheck spotbugsMain --no-daemon` then `./gradlew test --no-daemon` |
| `life-control-app-angular` | `references/angular.md` | `npm run lint`, `npm run build`, `npm run test:coverage:check`, `npm run test:e2e` |
| Both | `references/index.md` plus both component files | run both component gates |

Run each gate command from the component directory.

## Gaps

- `CONTRIBUTING.md`, `SECURITY.md`, `CODEOWNERS`, `docs/**` absent at root and component level.
- No secret-scanning configuration.
- No SBOM configuration.
- No documented rollback plan or post-deploy checklist.
- Provider-side settings (branch protection, required checks, review rules) are unverifiable from the repository.
