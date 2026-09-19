# Security Gates

Five mandatory gates. Select rows by component, apply the trigger, capture the exact evidence, then set `PASS / FAIL / N/A`.

## Gate 1 — Secrets

| Component | Trigger | Required evidence | Result |
| --- | --- | --- | --- |
| api | Change touches credential handling, `docker/secrets/`, or `docker/entrypoints/` | Hardcoded-secret check on the diff; `docker/scripts/validate-env.sh` result; reference to `docker/secrets/` as the only password channel | PASS / FAIL / N/A |
| web | Change touches `life-control-app-angular/nginx.conf`, `life-control-app-angular/entrypoint.sh`, `docker/secrets/`, or `docker/entrypoints/` | Hardcoded-secret check on the diff; `docker/scripts/validate-env.sh` result; runtime secret read path confirmed | PASS / FAIL / N/A |

## Gate 2 — Dependency risk

| Component | Trigger | Required evidence | Result |
| --- | --- | --- | --- |
| api | Dependency or plugin change in `life-control-api/build.gradle` | Confirm in `.github/dependabot.yml` whether this component's ecosystem is covered; SCA or SBOM output when the repository provides one; absent coverage is `GAP` | PASS / FAIL / N/A |
| web | Dependency change in `life-control-app-angular/package.json` or `life-control-app-angular/package-lock.json` | Confirm in `.github/dependabot.yml` whether this component's ecosystem is covered; SCA or SBOM output when the repository provides one; absent coverage is `GAP` | PASS / FAIL / N/A |

## Gate 3 — Least privilege

| Component | Trigger | Required evidence | Result |
| --- | --- | --- | --- |
| api | Auth, role, or Keycloak provisioning change | `docker/scripts/keycloak-setup.sh` result; role scope reviewed; least-privilege justification | PASS / FAIL / N/A |
| web | Route guard, interceptor, or visibility change | Code reviewed in `life-control-app-angular/src/core/guards/`, `life-control-app-angular/src/core/interceptors/`, `life-control-app-angular/src/core/security/`; RBAC rule from `life-control-app-angular/AGENTS.md`; least-privilege justification | PASS / FAIL / N/A |

## Gate 4 — Data handling

| Component | Trigger | Required evidence | Result |
| --- | --- | --- | --- |
| api | Change touches personal or payment data paths | Data classification (PII / PCI when applicable); handling rule cited from local documentation, or `GAP` if absent | PASS / FAIL / N/A |
| web | Change touches personal or payment data paths | Data classification (PII / PCI when applicable); handling rule cited from local documentation, or `GAP` if absent | PASS / FAIL / N/A |

## Gate 5 — Merge prohibition

| Component | Trigger | Required evidence | Result |
| --- | --- | --- | --- |
| api | Any critical API change proposed for merge | `./gradlew spotlessCheck spotbugsMain --no-daemon` result; `./gradlew test --no-daemon` result; security review evidence; rollback plan for medium/high risk | PASS / FAIL / N/A |
| web | Any critical web change proposed for merge | `npm run lint`, `npm run build`, `npm run test:coverage:check`, `npm run test:e2e` results; security review evidence; rollback plan for medium/high risk | PASS / FAIL / N/A |

## Enforcement baseline

- `api`: `./gradlew spotlessCheck spotbugsMain --no-daemon` then `./gradlew test --no-daemon`, run from `life-control-api`.
- `web`: `npm run lint`, `npm run build`, `npm run test:coverage:check`, `npm run test:e2e`, run from `life-control-app-angular`.

## Rules

- Never merge a critical change without validation evidence.
- Unverifiable control: `GAP`, never `PASS`.
- Missing security review or missing rollback plan at high risk: do not merge.
