# ODD feature: odd-header-without-transport

**Repository**: LifeControl — the ODD record contract (`AGENTS.md`, `CONTRIBUTING.md`,
`.agents/skills/project-conventions/SKILL.md` and `references/feature-records.md`, `references/index.md`), the
guard that enforces it (`.github/scripts/tests/odd-status-headers.test.sh`) and two record headers that carry the
contradiction the new rule rejects. **No production code, no workflow, no dependency, no `.env*` file, and no
other record's narrative.**
**Status**: implemented — the doctrine, the guard and its fixtures are written and measured, and this record
travels in the pull request that carries them, which is the practice S1 establishes. Nothing is deferred.
**Created**: 2026-10-20 · **Base**: `main` @ `0b02d60` · **Branch**: `docs/odd-header-without-transport` ·
**Worktree**: `~/workspace/LifeControl-worktrees/docs-odd-header-without-transport` (herdr workspace `w58`)
**Risk**: **item 3 of the task-size list, argued down rather than waved away.** The change moves a contract other
records are written against, and it *relaxes* one half of a CI gate while tightening the other: a reviewer has to
believe the tightened half still catches the failure mode the gate exists for. The mitigation is that nothing is
relaxed on the failure mode itself — the delivery-state vocabulary fails **unconditionally** from now on, where
today it passes when a merge commit is present — and only the *requirement* that a header name delivery evidence
is dropped. Every existing record is re-audited by the suite's first assertion, which fails before any fixture
runs if the tree is not green.

## Objective

Stop paying a second pull request, a second issue and a second review per unit of work, and keep exactly the
guarantee the second pull request was buying: **no record header can be falsified by a later merge.**

## The measurement this change answers

| Fact | Value | Command |
| --- | --- | --- |
| Merged pull requests that only touch `odd/tasks/*.md` | **51 of the last 150** (34%) | `gh pr list --state merged --limit 150 --json files` |
| Of those, rewriting exactly **one** record | **40** | same |
| Batch sweeps that exist only to repair skipped per-unit rewrites | **11** (up to 10 records each: #364, #436, #455, #471) | same |
| `docs(odd)` merged pull requests overall | **91** of 413 | `gh pr list --state merged --json title` |
| Extra gate per docs pull request | its own `status:approved` issue (#434, #454, #470, #491…) | `gh pr view` |

The shape measured on #488 → #492 (merged 16 minutes apart) is the whole defect in two lines: the feature pull
request already carries the record, and the follow-up only rewrites its `**Status**:` line to name a merge commit
`5fd86cf` that could not possibly have existed when the record was written.

## Specs

- **S1 — One pull request per unit.** The record travels in the pull request that carries the work, and its header
  must already be final there. No follow-up pull request exists for the purpose of moving a header to a merged
  shape. The user's decision, verbatim: *«si dale a la 1»*, choosing *«**Header sin transporte (recomendada).** El
  header dice qué es el trabajo y qué queda; nunca el estado de entrega… **Resultado: 1 PR por unidad, ~34% menos
  PRs**»*.
- **S2 — The header never asserts the transport.** It states what the work is and what remains; never whether it
  was pushed, opened, reviewed or merged. Delivery identity is *optional* and, when a later session touches the
  record for another reason, belongs to the dated evidence log.
- **S3 — `implemented` needs no evidence.** The guard requires **no** pull-request number and **no** merge commit
  before a header is compliant. The number did not exist when the record was written, and demanding it is what
  forced the second pull request — the guard's own `D1` note recorded that consequence as accepted cost.
- **S4 — The live-state vocabulary fails unconditionally.** No merge commit rescues `not merged`, `awaiting
  review`, `in progress`, `unstarted`, `not pushed`, `no PR is open`, `yet to land`, `pending review` or `what
  remains is the delivery`. Today each of those passes when the same paragraph names a merge commit; after this
  change none does. This is the half that gets **tighter**, and it is why dropping S3's evidence requirement does
  not weaken the gate.
- **S5 — Spanish is part of the vocabulary.** The five phrases `AGENTS.md` already names as forbidden — `sin
  pushear`, `no hay PR abierto`, `en progreso`, `esperando review`, `sin mergear` — are enforced, because the two
  most recent records were written in Spanish and `#492`'s own replaced header (`implementado y entregado para
  revisión … el merge es una decisión del mantenedor`) is a live claim the English-only denylist cannot see.
- **S6 — A disclaimer beside delivery evidence is a contradiction.** `This header makes no claim about push or PR
  state` next to a named merge commit is the sentence `#436` had to repair; it fails now.
- **S7 — Named delivery evidence is still verified.** A merge commit a header *chooses* to name must resolve as a
  commit and be an ancestor of `origin/main`, and the ancestry half still says `ancestry: skipped` out loud when
  the ref is absent.
- **S8 — The tree stays green and no narrative is rewritten.** All 83 records pass without an allowlist; the two
  whose headers carry S6's contradiction are repaired in the header only, and the repair is recorded in each, as
  `references/feature-records.md` invariant 4 requires.
- **S9 — Documentation and code cannot drift.** The suite asserts that **every** phrase `AGENTS.md`'s forbidden
  row names is actually caught, so the two sources cannot diverge silently.

## Tasks

| ID | Specs | Route | Outcome |
| --- | --- | --- | --- |
| T1 | S3, S6, S7 | inline, test-first | `odd-status-headers.test.sh`: the new fixture expectations observed **RED** against the current rule, then the rule rewritten and the suite **GREEN** |
| T2 | S2, S4, S5, S9 | inline | The Spanish vocabulary and the `AGENTS.md`-parity assertion, with their fixtures |
| T3 | S1, S2, S7 | inline | `references/feature-records.md`, `AGENTS.md`, `CONTRIBUTING.md` §5, `SKILL.md` rule 5, `references/index.md` |
| T4 | S8 | inline | The two contradicting headers repaired, with their recorded repair |
| T5 | all | inline | Nine suites and shellcheck over every `.sh`, with the counts re-measured rather than repeated |

## Decisions

- **D1 — The invariant is falsifiability, not topic.** Two header shapes stay legal because a future merge cannot
  falsify either: `implemented — <what remains>` (S3) and `merged — PR #N (@sha)`, date (S7). What becomes illegal
  is the claim that can still change: `awaiting review` is false fifteen minutes later, and that is the only
  failure mode the gate ever caught.
- **D2 — The escape hatch goes, the evidence requirement goes with it.** S3 alone would leave `not merged` + a
  merge commit passing, which is worse after the change than before it. The two halves move together on purpose.
- **D3 — The `ci-speed` shape flips from pass case to failure case.** The fixture existed to defend the position
  `#446` took; `#436` measured that same position producing a false sentence one day later. Its name is kept so the
  reversal is traceable.
- **D4 — No record is migrated to the new canonical shape.** 75 headers state a landed delivery with real
  evidence, which S7 keeps legal. Rewriting them would buy nothing and destroy the audit trail of the drift.

## Log

**L1** — the user's request, verbatim: *«puedes analizar los PR y ver si es correcto que despues de cada PR se
tenga que hacer otro de documentacion, es una practica recomendada o hay algo que se pueda hacer mejor»*, followed
by the decision *«si dale a la 1»* after the analysis was reported.

**L2** — the analysis this record implements was read-only, and its two findings are what the specs answer. First,
the practice was not a style choice: `AGENTS.md` asked the header to carry *terminal state with its evidence (PR,
merge commit, date)*, and a merge commit cannot exist inside the pull request that creates it, so the header could
only be correct in a second pull request. `references/feature-records.md` said the opposite in two places — its
invariant 3 puts delivery detail in the log, and its `## Gaps` section says *«the header still has to be designed
so that it does not need updating»* and *«Nothing updates the header at merge time»*. Second, the guard was not
what forced it: `odd-status-headers.test.sh` only demanded a merge commit for the delivery-state class, so the
pre-merge-safe shape already passed with a bare `#N`. The double pull request was doctrine, not enforcement.

**L3** — the guard was rewritten test-first and the difference was measured in four directions, because a
relaxation and a tightening that both touch one gate cannot be argued from a single green run.

| Run | Suite | Tree | Result |
| --- | --- | --- | --- |
| baseline | old (`main`) | base (`main`) | **25 passed, 0 failed** — the tree was green before this change |
| the removed requirement | old | this branch | **24 passed, 1 failed**: the only finding is this record's own `implemented` with no evidence — no other record on the tree depended on it |
| the tightening | new | base | **37 passed, 1 failed**: the two contradictions T4 repairs, and **nothing else** — the 14-phrase bilingual denylist produces zero false positives over 83 records |
| final | new | this branch | **56 passed, 0 failed**, `checked 84 record(s); 117 claimed merge commit(s); ancestry: ran` |

The RED was observed before the rule changed: the new fixtures failed against the old audit for exactly the
four reasons this change names — `implemented` without evidence failed, a live claim beside a merge commit
**passed**, a disclaimer beside evidence **passed**, and a disclaimer alone **failed**. All four flipped.

The rest of the gate, on the committed bytes: `for s in .github/scripts/tests/*.test.sh; do "$s"; done` →
**9 suites, 290 assertions, 0 failures** (this suite carries 56 of them), and shellcheck
`--severity=warning` over the **12** `.sh` files under `.github/scripts/` → clean. The `AGENTS.md` row that
repeated *9 suites, 245 aserciones* was **already 14 assertions stale before this change** (the tree measured
259); it now names the command that produces the count instead of the count, which is the rule #490 applied to
the workflow and this change applies to the row.
