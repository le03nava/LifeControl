---
name: project-conventions
description: "Trigger: conventions, standards, security, compliance, architecture, testing, PR governance. Enforces enterprise repository conventions with auditable evidence."
license: Apache-2.0
metadata:
  author: "le03nava"
  version: "1.0"
---

## Activation Contract

Invoke when a change touches code, architecture, CI/CD, dependencies, security, or release processes in `life-control-api` or `life-control-app-angular`.

## Hard Rules

1. Rule precedence: `AGENTS.md` > `CONTRIBUTING.md` > `docs/**` > implicit conventions in code. Apply the highest existing source; record an absent source as `GAP`.
2. Do not introduce a new pattern when an internal standard already exists.
3. Do not use external references when local documentation exists.
4. A change without minimum evidence is non-conformant.

## Decision Gates

| Gate | Trigger | Required action |
| --- | --- | --- |
| Security | touches auth / permissions / sensitive data | mandatory security review |
| Contracts | changes contracts / API / schema | backward compatibility or a migration plan |
| Pipeline | changes pipeline / deploy | rollback plan and post-deploy verification |
| Chain | a PR branch is the base of another open PR | retarget the dependents before merging, or do not delete the branch |
| Unknown | documented conventions are missing | record the gap; never invent final policy |

## Execution Steps

1. Read the component reference: `references/api.md` for `life-control-api`, `references/angular.md` for `life-control-app-angular`, both when a change spans both.
2. When work is parallelized, read `references/worktrees.md` before creating a worktree or starting a second writer.
3. When a PR branch is the base of another open PR, read `references/pr-chains.md` before merging or deleting any branch in the chain.
4. Discover conventions (docs + code + CI).
5. Classify impact (low / medium / high risk).
6. Define the mandatory controls per change type.
7. Run applicable validations (lint / test / security checks).
8. Produce traceable evidence.

## Risk & Compliance Controls

### A. Security and compliance

Scan for secrets; enforce the hardcoded-secret policy. Apply the SCA or SBOM dependency risk policy when the repository provides one. Require least privilege for auth and role changes. Apply data handling rules for PII or PCI when applicable. Never merge critical changes without validation evidence.

### B. Quality and reliability

Define the test strategy per level (unit / integration / e2e per impact). Require non-flaky behavior for critical changes. Keep tests and contracts deterministic. State regression criteria and the minimum coverage.

### C. Operations and release

Require a rollback plan for medium and high risk. Use feature flags or canary when appropriate. Provide minimum observability (logs, metrics, alerts) for operational changes. Attach a post-deploy verification checklist.

### D. Traceability and audit

Leave evidence for every decision. Link the exact source-of-truth files. Record exceptions with justification. Report each control as PASS / FAIL / N/A.

## Evidence Contract

Minimum evidence is an exact command plus observed result plus exact file path. Mark any control that is not locally verifiable `GAP`; never assume it.

This skill carries process, not facts: it names the declaring file instead of repeating the value, so it cannot go stale. Never quote a version, threshold, or dependency from memory — read the declaring file.

## Output Contract

Gate the output on the risk level from `assets/risk-classification-matrix.md`. Six blocks exist: `Conventions Applied`; `Controls Evaluated` (table `control | status | evidence`); `Risk Classification`; `Gaps Detected`; `Required Follow-ups`; `Files/Paths Referenced`.

- **low risk**: emit only `Risk Classification` (low + reason), `Controls Evaluated` (controls that apply only), and `Gaps Detected` (relevant gaps, or `none`). The other three are optional when empty.
- **medium or high risk**: emit all six in the order above, `Files/Paths Referenced` listing exact local paths.

Uncertain risk counts as the higher level.

## References

- `references/index.md` — domain index, dispatch table and the gaps registry.
- `references/api.md`, `references/angular.md` — per-component conventions, gates and gaps. Read the matching one first.
- `references/worktrees.md` — isolation model for parallel work: invariants, path and naming, create and cleanup commands, the Pi trust requirement, and anti-patterns.
- `references/pr-chains.md` — chained and stacked PRs: the base-branch deletion failure mode, invariants, the pre-merge guard, the order of operations, and anti-patterns.
- `assets/convention-checklist.md`, `assets/security-gates.md`, `assets/pr-evidence-template.md`, `assets/risk-classification-matrix.md` — the operator artifacts.

Cite only existing local paths; record an absent path as `GAP` under a Gaps heading.
