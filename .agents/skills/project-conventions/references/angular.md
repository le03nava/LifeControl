# Angular Conventions — `life-control-app-angular`

Component reference for the Angular frontend. Run every gate command from `life-control-app-angular`.

## Governance

- `life-control-app-angular/AGENTS.md` — mandatory component patterns, RBAC, visibility rules, and the dual-mode guard.
- `AGENTS.md` — repository-wide precedence and skill registry.
- `life-control-app-angular/README.md` — component documentation.
- `life-control-app-angular/package.json` — scripts, dependencies, the Prettier configuration, and the `lint-staged` setup. Read it for the current formatter options.
- `life-control-app-angular/.editorconfig` — editor defaults.
- Gap: `CONTRIBUTING.md`, `SECURITY.md`, `CODEOWNERS`, `docs/**` absent at root and component level.

## Architecture

- `life-control-app-angular/angular.json` — build targets and the unit-test runner configuration (coverage reporting and setup file). The setup file is `life-control-app-angular/src/test-setup.ts`.
- `life-control-app-angular/tsconfig.json`, `life-control-app-angular/tsconfig.app.json`, `life-control-app-angular/tsconfig.spec.json` — TypeScript targets.
- `life-control-app-angular/src/features/`, `life-control-app-angular/src/shared/`, `life-control-app-angular/src/shared/styles/`, `life-control-app-angular/src/styles.scss` — feature and shared layout.
- `life-control-app-angular/src/core/security/`, `life-control-app-angular/src/core/guards/`, `life-control-app-angular/src/core/interceptors/` — auth, route guards, and HTTP interceptors.
- `life-control-app-angular/Dockerfile`, `life-control-app-angular/nginx.conf`, `life-control-app-angular/entrypoint.sh` — image and runtime.
- Gap: no Kubernetes manifest for this component under `k8s/manifests/applications/` (only `k8s/manifests/applications/api-gateway.yml` and `k8s/manifests/applications/common-config.yml` exist there).

## Security

- Authentication is Keycloak. The client libraries are declared in `life-control-app-angular/package.json`.
- `life-control-app-angular/src/core/security/`, `life-control-app-angular/src/core/guards/`, `life-control-app-angular/src/core/interceptors/` — authorization code; RBAC and the dual-mode guard are documented in `life-control-app-angular/AGENTS.md`.
- `life-control-app-angular/nginx.conf` and `life-control-app-angular/entrypoint.sh` — runtime secret handling surface; verify no hardcoded secret.
- `docker/secrets/` with `docker/entrypoints/` — the password channel; apply least privilege to role and visibility changes.
- Gap: no secret-scanning configuration; no SBOM configuration.

## Testing & Quality

- Gate (declared in `life-control-app-angular/package.json` and `.github/workflows/angular-ci.yml`): `npm run lint`, `npm run build`, `npm run test:coverage:check`, `npm run test:e2e`.
- `life-control-app-angular/scripts/check-coverage.mjs` — the repo-defined coverage enforcement. Read it for the enforced thresholds and the summary file it consumes; it fails the run when a metric is missing or below its minimum. Do not restate the numbers.
- `life-control-app-angular/angular.json` declares the build budgets (initial bundle and component-style limits). Read it rather than quoting a budget.
- `life-control-app-angular/eslint.config.js` — the enforced lint conventions. Two are load-bearing and must hold: the `app` selector prefix and the template accessibility rules. Read the file for the rest.
- `life-control-app-angular/.husky/pre-commit` — runs `npx lint-staged`; `lint-staged` runs `eslint --fix` and `prettier --write` on TypeScript sources and `prettier --write` on HTML and SCSS sources.
- `life-control-app-angular/playwright.config.ts` — E2E configuration; specs in `life-control-app-angular/e2e/specs/`, fixtures in `life-control-app-angular/e2e/fixtures/app.ts`, mocks in `life-control-app-angular/e2e/mocks/api.ts` and `life-control-app-angular/e2e/mocks/keycloak.ts`, tokens in `life-control-app-angular/e2e/support/tokens.ts`. Read this file before citing retry or worker settings; do not state a setting you have not confirmed there.
- `life-control-app-angular/src/test-setup.ts` — unit test setup file.
- Gap: no coverage-gate equivalent for a flakiness or quarantine policy.

## CI/CD & Release

- `.github/workflows/angular-ci.yml` — the CI steps (Node toolchain, `npm ci --legacy-peer-deps`, lint, build, `test:coverage:check`) and the `coverage-lcov` artifact. Read it for the toolchain version.
- `docker/scripts/deploy.sh`, `docker/scripts/setup-env.sh` — deploy path.
- Gap: no dependency automation for this component, because the npm ecosystem is not configured in `.github/dependabot.yml`; no documented rollback plan or post-deploy checklist.

## Operations

- `docker/scripts/validate-env.sh`, `docker/scripts/setup-env.sh` — environment validation and setup.
- `docker/docker-compose.yml`, `docker/docker-compose.prod.yml` — runtime composition.
- `k8s/manifests/infra/`, `k8s/manifests/infrastructure/` — cluster infrastructure manifests.
- `life-control-app-angular/nginx.conf`, `life-control-app-angular/entrypoint.sh` — runtime serving and entrypoint.
- Gap: no post-deploy verification checklist; provider-side settings unverifiable from the repository.

## Gaps

- No dependency automation for this component (npm ecosystem not configured in `.github/dependabot.yml`).
- No documented flakiness or quarantine policy on top of the coverage gate.
- No `CONTRIBUTING.md`, `SECURITY.md`, `CODEOWNERS`, `docs/**` at root or component level.
- No SBOM configuration.
- No secret-scanning configuration.
- No documented rollback plan or post-deploy checklist.
- No Kubernetes manifest for this component under `k8s/manifests/applications/` (only `k8s/manifests/applications/api-gateway.yml` and `k8s/manifests/applications/common-config.yml` exist).
- SDD artifacts for this component land under the gitignored `.opencode/` directory, so they are not versioned.
- Provider-side settings (branch protection, required checks, review rules) unverifiable from the repository.
