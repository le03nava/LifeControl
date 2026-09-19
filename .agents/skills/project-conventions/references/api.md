# API Conventions — `life-control-api`

Component reference for the Spring Boot API. Run every gate command from `life-control-api`.

## Governance

- `life-control-api/AGENTS.md` — mandatory component patterns: Spring Boot 3, Java 21, no Lombok, records, constructor injection.
- `AGENTS.md` — repository-wide precedence and skill registry.
- `life-control-api/README.md` — component entry documentation.
- `life-control-api/sdd/` — SDD artifacts for this component. Local only: `sdd/` is in `.gitignore`, so they are not versioned.
- `life-control-api/settings.gradle` — project name and module boundary.
- Gap: `CONTRIBUTING.md`, `SECURITY.md`, `CODEOWNERS`, `docs/**` absent at root and component level.

## Architecture

- `life-control-api/build.gradle` — build, toolchain, plugins, and dependency surface.
- The Java toolchain and the Spring Boot version are declared in `life-control-api/build.gradle`. Read it instead of assuming a version.
- The observability and runtime control baseline (metrics registry, tracing, log shipping, request logging, rate limiting, caching) is declared in `life-control-api/build.gradle`. Read it to state the actual baseline.
- `life-control-api/Dockerfile` — image build.
- `docker/docker-compose.yml`, `docker/docker-compose.override.yml`, `docker/docker-compose.prod.yml` — runtime topology.
- `k8s/manifests/applications/api-gateway.yml`, `k8s/manifests/applications/common-config.yml` — gateway and shared config manifests.

## Security

- `docker/secrets/` — the only password channel; templates materialize read-only secret files.
- `docker/entrypoints/` — wrappers read the secret file and export the expected env var.
- `docker/scripts/validate-env.sh` — validates required variables and materialized secrets.
- `docker/scripts/keycloak-setup.sh` — realm, client, and role provisioning; apply least privilege to auth and role changes.
- Gap: no secret-scanning configuration; no SBOM configuration.

## Testing & Quality

- Gate (declared in `.github/workflows/api-ci.yml`): `./gradlew spotlessCheck spotbugsMain --no-daemon`, then `./gradlew test --no-daemon`.
- `life-control-api/build.gradle` declares the formatter, the static analyser's effort and report level, and whether a violation fails the build. Read it; do not restate its values here.
- `life-control-api/config/spotbugs/exclude.xml` — the static-analyser exclusion filter. Confirm in `life-control-api/build.gradle` which source sets the analysis covers.
- The test infrastructure (database containers, HTTP client, documentation snippets) is declared in `life-control-api/build.gradle`. Read it before describing the test stack.
- Gap: no coverage threshold for this component.

## CI/CD & Release

- `.github/workflows/api-ci.yml` — CI source; uploads the `api-test-results` artifact.
- `.github/dependabot.yml` — the declared dependency automation. Read it to confirm which ecosystem is actually covered for this component, and whether the schedule and commit convention still hold. Uncovered ecosystem is a `GAP`.
- `docker/scripts/deploy.sh`, `docker/scripts/setup-env.sh`, `docker/scripts/cleanup.sh` — deploy path and rollback surface.
- Gap: no documented rollback plan or post-deploy checklist.

## Operations

- Observability controls listed under Architecture (actuator, Prometheus, tracing, Loki, Logbook) are the minimum baseline for operational changes.
- `k8s/kind/kind-config.yaml`, `k8s/kind/create-kind-cluster.sh`, `k8s/kind/kind-load.sh`, `k8s/kind/delete-kind-cluster.sh` — local cluster lifecycle.
- `docker/scripts/validate-env.sh` — pre-start environment validation.
- Gap: provider-side settings unverifiable from the repository.

## Gaps

- No coverage threshold for this component.
- `sdd/` is in `.gitignore`: SDD artifacts and verify reports for this component are not versioned.
- No SBOM configuration.
- No secret-scanning configuration.
- No documented rollback plan or post-deploy checklist.
- `CONTRIBUTING.md`, `SECURITY.md`, `CODEOWNERS`, `docs/**` absent at root and component level.
- Provider-side settings (branch protection, required checks, review rules) unverifiable from the repository.
