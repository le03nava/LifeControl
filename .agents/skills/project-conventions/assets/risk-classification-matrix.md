# Risk Classification Matrix

Classify the change, apply the required controls, and attach evidence.

| Change type | Risk | Required controls | Evidence |
| --- | --- | --- | --- |
| Documentation-only change (no code, config, or contract impact) | low | Conventions discovered; evidence traceable | Exact file path; review note |
| Test-only change, no production behavior | low | Test strategy; determinism / non-flaky | api: `./gradlew test --no-daemon`; web: `npm run test:coverage:check` |
| Formatting or lint-only change | low | Applicable validations executed | api: `./gradlew spotlessCheck --no-daemon`; web: `npm run lint` |
| New feature behind an existing pattern | medium | Security gate; test strategy; regression / coverage; rollback plan | api: `./gradlew test --no-daemon`; web: `npm run test:coverage:check`, `npm run test:e2e` |
| Dependency or version upgrade | medium | Dependency risk; validations; rollback plan | api: `./gradlew test --no-daemon`; web: `npm run build`, `npm run test:coverage:check`; SCA/SBOM or `GAP` |
| Config or environment change | medium | Least privilege; observability; pipeline rollback | `docker/scripts/validate-env.sh`; referenced config path |
| Auth, permissions, role, or guard change | high | Security review (mandatory); least privilege; test strategy; rollback plan | web: `npm run test:e2e`; api: `./gradlew test --no-daemon`; security review note |
| Contract, API, or schema change | high | Contract compatibility or migration plan; security review; rollback plan | Gate command result; compatibility or migration note |
| Pipeline or deploy change | high | Rollback plan (mandatory); post-deploy verification; observability | `.github/workflows/api-ci.yml` or `.github/workflows/angular-ci.yml` result; rollback note |
| Data handling change (PII / PCI) | high | Security review; data handling rules; least privilege | Data classification note; gate command result |

## Escalation rule

- Any required evidence that is unavailable becomes `GAP`; the control cannot be `PASS`.
- High risk without a security review or without a rollback plan must not merge.
- Unknown conventions: record the gap and do not invent final policy.
