# PR Evidence Template

Fill every slot. Replace placeholders; do not delete a block.

Risk gate: for **low risk** only blocks 2 (`Controls Evaluated`), 3 (`Risk Classification`) and 4 (`Gaps Detected`) are required, with only the controls that apply. **Medium** and **high** risk require every block. Set the risk level in the component declaration before filling anything in.

## Component declaration

- Component: api / web / both
- Reference used: `references/api.md` / `references/angular.md` / `references/index.md`
- Impact: low / medium / high
- Author:
- Date:

## 1. Conventions Applied

- Convention:
  - Source path:
  - Rule:

## 2. Controls Evaluated

| control | status (PASS / FAIL / N/A) | evidence (exact command / observed result / exact file path) |
|---|---|---|
| Conventions discovered |  |  |
| Rule precedence |  |  |
| Security gate |  |  |
| Contract compatibility |  |  |
| Pipeline rollback |  |  |
| Dependency risk |  |  |
| Secret handling |  |  |
| Least privilege |  |  |
| Data handling |  |  |
| Test strategy |  |  |
| Determinism / non-flaky |  |  |
| Regression / coverage |  |  |
| Observability |  |  |
| Validations executed |  |  |
| Evidence traceability |  |  |

## 3. Risk Classification

- Risk: low / medium / high
- Reason:
- Rollback plan (medium/high):

## 4. Gaps Detected

- Gap:
  - Missing convention:
  - Impact:
  - `GAP` marker: yes

## 5. Required Follow-ups

- Follow-up:
  - Owner:
  - Blocking: yes / no

## 6. Files/Paths Referenced

- exact/local/path/one
- exact/local/path/two

## Evidence slots

- Exact command:
  - Observed result:
  - Exact file path:
- Exact command:
  - Observed result:
  - Exact file path:

## Gate commands by component

- api: `./gradlew spotlessCheck spotbugsMain --no-daemon` then `./gradlew test --no-daemon`
- web: `npm run lint`, `npm run build`, `npm run test:coverage:check`, `npm run test:e2e`
