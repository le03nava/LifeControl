# Convention Checklist

Single control checklist. `Component` is `api`, `web`, or `both`. Answer each row yes/no, cite evidence, then set status.

| # | Control | Component | Passes? | Evidence (exact command / observed result / exact file path) | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Conventions discovered in docs, code, and CI before changes | both | yes / no |  | PASS / FAIL / N/A |
| 2 | Rule precedence applied (`AGENTS.md` > `CONTRIBUTING.md` > `docs/**` > implicit; an absent source is `GAP`) | both | yes / no |  | PASS / FAIL / N/A |
| 3 | No new pattern introduced where an internal standard exists | both | yes / no |  | PASS / FAIL / N/A |
| 4 | Impact classified (low / medium / high) with reason | both | yes / no |  | PASS / FAIL / N/A |
| 5 | Security gate applied for auth, permissions, or sensitive data | both | yes / no |  | PASS / FAIL / N/A |
| 6 | Contract compatibility or migration plan stated | api | yes / no |  | PASS / FAIL / N/A |
| 7 | Pipeline rollback plan and post-deploy verification stated | both | yes / no |  | PASS / FAIL / N/A |
| 8 | Dependency risk policy applied (SCA / SBOM when provided) | both | yes / no |  | PASS / FAIL / N/A |
| 9 | Hardcoded-secret check performed | both | yes / no |  | PASS / FAIL / N/A |
| 10 | Least privilege enforced for auth and role changes | both | yes / no |  | PASS / FAIL / N/A |
| 11 | Data handling rules applied (PII / PCI when applicable) | both | yes / no |  | PASS / FAIL / N/A |
| 12 | Test strategy defined per level (unit / integration / e2e per impact) | both | yes / no |  | PASS / FAIL / N/A |
| 13 | Determinism and non-flaky requirement met for critical changes | both | yes / no |  | PASS / FAIL / N/A |
| 14 | Regression criteria and repo-defined minimum coverage met | both | yes / no |  | PASS / FAIL / N/A |
| 15 | Observability minimum provided for operational changes | both | yes / no |  | PASS / FAIL / N/A |
| 16 | Applicable validations executed (lint / test / security) | both | yes / no |  | PASS / FAIL / N/A |
| 17 | Evidence traceable for every relevant decision | both | yes / no |  | PASS / FAIL / N/A |
| 18 | Exceptions recorded with justification | both | yes / no |  | PASS / FAIL / N/A |
| 19 | Unverifiable control marked `GAP` instead of assumed | both | yes / no |  | PASS / FAIL / N/A |

## Status rules

- Low risk: complete only the rows that apply to the change and mark the rest `N/A` with a reason. Medium and high risk: the full table applies.
- `PASS` requires local evidence: exact command, observed result, exact file path.
- `FAIL` requires the failing evidence and a required follow-up.
- `N/A` requires the reason the control does not apply.
- Unverifiable control: use `GAP`, never `PASS`.
