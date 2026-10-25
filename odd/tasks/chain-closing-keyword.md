# Chain-aware issue reference

**Status**: implemented — the guard accepts a chain declaration beside `Refs #N` on the default
branch, `CONTRIBUTING.md` §1 and the chain reference state the rule, and the suite pins both
directions. Nothing is deferred.
**Created**: 2026-10-25 · **Risk**: high — item 3 (the guard's verdict is a contract the workflow and
`CONTRIBUTING.md` already consume) and item 2 (the guard is a merge gate, and this change relaxes one
of its two verdicts under a stated condition).

Issue: #598 · Branch: `chore/chain-closing-keyword` · Label on the pull request: `type:chore`.

## Specs

- **S1 — Only the last slice of a chain carries the closing keyword.** `CONTRIBUTING.md` §1 must
  state the chain rule: only the last slice of a chain carries the closing keyword; every earlier
  slice references the issue with `Refs`, including after it is retargeted to the default branch, and
  the issue stays open until the last slice lands.
- **S2 — The guard accepts a chain slice and rejects a bare `Refs`.** `.github/scripts/check-issue-reference.sh`
  accepts a `Refs #N` on a pull request that targets the default branch **when the referenced issue is
  still open and approved and the pull request says it is part of a chain**, and rejects a `Refs` that
  is not accompanied by that statement, so the relaxation cannot be used to dodge the closing keyword
  for an ordinary pull request.
- **S3 — Every existing reject survives unchanged.** The guard still rejects, unchanged: no
  reference, a closed issue, a pull request reference, another repository, and an issue without
  `status:approved`.
- **S4 — The suite covers both directions and stays offline.**
  `.github/scripts/tests/check-issue-reference.test.sh` covers the new case in both directions — a
  chain slice with `Refs` on the default branch passes, an ordinary pull request with `Refs` on the
  default branch still fails — and the suite stays offline.
- **S5 — A chain can be retargeted and merged in order.** A chain of three pull requests against a
  scratch issue can be retargeted and merged in order with the issue staying open until the last one;
  a recorded sequence is enough to demonstrate it, and no fixtures beyond the ones the suite already
  builds are needed.

Decisions the issue left open, settled here and binding on the code:

- **D1 — The declaration is number-scoped.** The statement a chain slice carries is
  `Part of a chain: #N`, and the number must be one this body references, so a declaration cannot
  enable a `Refs` to a different issue. Recorded in `L2`.
- **D2 — The declaration is read from prose only.** It is scanned after fenced blocks and inline code
  spans are stripped, exactly like the keywords, so a pull request that documents the marker does not
  declare itself a chain slice.
- **D3 — The declaration only relaxes, never rejects.** Off the default branch `Refs #N` was already
  legal and the declaration is ignored there; beside a closing keyword it changes nothing. Absent a
  declaration, the default-branch verdict is the one it was before this change.
- **D4 — The declaration alone is not a reference.** A body carrying only `Part of a chain: #N` fails
  as "no issue reference": it names an issue without linking it.
- **D5 — The declaration is anchored to the start of a line, and that was found by verification, not by
  the issue.** The first implementation matched the phrase anywhere in a line, so
  `The migration is part of a chain #12 of refactors.` was read as a declaration while the header
  comment and `CONTRIBUTING.md` both said "a line of its own": the code was looser than the text that
  justified it. The marker is now anchored, and the sentence above is a case in the suite.
- **D6 — An HTML comment still counts as prose, by inheritance.** The gate does not strip comments, and
  the keyword path never did, so `Closes #12` inside a comment satisfies it today — that is pre-existing
  behaviour, not something the declaration introduced. The consequence is pinned instead of silently
  accepted: the pull request template carries the marker **without a number** (a declaration needs
  digits), and a case fails if a numbered declaration ever reaches the template, which would unlock every
  pull request that keeps the template's text.
- **D7 — The declaration is an author's assertion, and the gate says so.** The gate verifies the shape of
  the assertion, the number against the body, and the issue's state; it cannot verify that a later slice
  exists. A body can therefore still choose the closing keyword on a mid-chain slice on purpose, which is
  the same class of trust as `status:approved` being a maintainer's decision. `references/pr-chains.md`
  states that limit where the rule is written rather than letting the gate imply an authority it lacks.
- **D8 — The accepted form is the documented form and nothing wider.** The `risk` lens of the native review
  raised `R1-CHAIN-DECL-BYPASS`: the anchored marker still allowed `Part of a chain #12` without the colon
  and a line indented four spaces, which Markdown renders as a code block. Both unlocked the gate while the
  documents promised `Part of a chain: #N` on a line of its own, so the code was again wider than its own
  contract (`D5` was the same defect one level up). The marker now requires the colon and at most three
  leading spaces, and three cases pin the rejections. The two widening paths were candidate-caused, not
  base-only, which is why they were fixed rather than reported as follow-ups.

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S2, S4 | inline, test-first — RED seen first: 7 of 64 cases failed, all of them the new ones | `69adbe50` |
| T2 | S2, S3 | inline — GREEN: 72 of 72 after `D5` and `D8` | `69adbe50` |
| T3 | S1 | inline — `CONTRIBUTING.md` §1, `references/pr-chains.md`, `PULL_REQUEST_TEMPLATE.md` | this commit |
| T4 | S3, S5 | inline — independent verification (admitted on `Risk: item 2`/`item 3`), the full offline suite, shellcheck, a differential run and the recorded three-slice sequence | this commit |
| T5 | — | inline — this record and the `S2` repair in `odd/tasks/pr-issue-governance.md` (`L24` there) | this commit |

The commit of a row is the slice that carried it; the last row's is derived, never stored:
`git log -1 --format='%h' -- odd/tasks/chain-closing-keyword.md`.

## Evidence

The commands below were run on 2026-10-25 in this branch, at `69adbe50` for the two code rows.

- **The suite, both directions** — `./.github/scripts/tests/check-issue-reference.test.sh`: **72 passed,
  0 failed**. The base suite has 51 cases and this one has 72; 15 of the 21 added cases are the new
  behaviour or its narrowing, 6 are guardrails that already pass against the old code (the fenced
  declaration, the declaration with no reference, the stale declaration off the default branch, the
  template check, and `--print-refs` on a bare `Refs`). RED was observed first and it was exactly the
  new behaviour: 7 failures, every one a chain-declaration case.
- **Every other offline suite** — all 11 suites under `.github/scripts/tests/` pass: 14, 72, 26, 21, 34,
  24, 78, 33, 17, 28, 38 assertions, **385 total, 0 failed**.
- **Shellcheck** — the same command CI runs, `koalaman/shellcheck:stable --severity=warning`, over the
  16 shell files of `.github/scripts/` and `.githooks/` taken by glob: **no findings**.
- **Independent verification** — a read-only `gentle-ai-verify` pass, admitted on the two high-risk items
  the header names, re-ran both suites in a scratch copy and tried to defeat the rule with 27 hand-built
  bodies. It returned PASS on `S1`-`S5` and found no case where a body that passed the old gate fails the
  new one, plus three residuals it classified as advisory: the unanchored marker (`D5`, fixed), the HTML
  comment (`D6`, pinned), and the author-assertion limit (`D7`, documented). Its fourth finding was this
  record's own counting error — "up from 57 cases" was the RED-phase pass count, not the base suite's
  size — which is why the numbers above are stated against 51.
- **Native review** — lineage `review-fc09d6a1c5e88493`, tier `high`, one lens (`review-risk`), state
  **`escalated`**: one CRITICAL finding, `R1-CHAIN-DECL-BYPASS`, admitted with `unknown` causality and
  therefore not a correction plan. It was candidate-caused and it was real — the widened spellings `D8`
  lists — so it was fixed and pinned by cases; the lineage is terminal and informational, and a fresh
  review of the corrected candidate is a separate decision. The START rejected every multi-lens selection
  with `lenses-invalid` (one lens was accepted), so the reliability and readability lenses did not run;
  their concerns were covered inline, by the verification pass and by the suite.
- **Differential check against the base commit** (`3824aa2c`, guard copied into a scratch directory): 16
  bodies compared on exit code, stdout and stderr, half of them multi-line. The only differences are the
  intended ones — a declared chain slice (with and without slice detail) goes from `FAIL` to `PASS`, and
  the mid-sentence, colon-less and indented-block bodies keep their `FAIL` with the new message — plus the
  one the issue asks for: an undeclared `Refs` on the default branch still fails, now with a message that
  names both ways out. No case moves from `PASS` to `FAIL`.
- **The three-slice sequence (S5)**, run against the guard with the issue fixtures the suite already
  builds — an issue that is open and approved, and the same issue after it closes. It is a recorded
  sequence rather than three live pull requests because opening and merging them is a delivery decision
  this unit does not take; the state each step asserts is the one the gate reads.

```text
slice 1, opened against the tracker branch      Refs #12 + Part of a chain: #12   PASS  exit 0
slice 1, retargeted to main before the merge    Refs #12 + Part of a chain: #12   PASS  exit 0
slice 1 merged: the issue is still open         Refs #12 + Part of a chain: #12   PASS  exit 0
slice 2, retargeted to main                     Refs #12 + Part of a chain: #12   PASS  exit 0
slice 3 (the last one), against main            Closes #12                        PASS  exit 0
slice 3 merged: the issue is closed             Refs #12 + Part of a chain: #12   FAIL  exit 1
                                                FAIL: issue #12 is not open (state=closed)
```

Nothing was reopened and no reference was rewritten between the first row and the fifth: the issue stays
open through every mid-chain retarget, and the closing keyword lands once, on the last slice. The rows
are the gate's own verdicts on the bodies a chain would carry, replayed against the fixtures the suite
already builds; the pull requests themselves are the deliverer's action, not this unit's.

## Log

- **L1 — the request, verbatim.** «puedes arrancar con el issue 598, apruebalo»
- **L2 — the marker decision, verbatim from the answer given.** Asked: «¿Qué marcador declara "este
  PR es parte de una cadena" en el cuerpo del PR? El guard lo va a exigir para aceptar `Refs #N` sobre
  la rama por defecto.» Answer: `Part of a chain: #598 (recomendado)` — «Marcador con número: el guard
  exige que ese número sea el issue referenciado. Explícito y no se puede colar en un PR suelto.»
- **L3 — the issue is the requirement source.** #598 carries the acceptance criteria quoted in
  `S1`–`S5`; it was approved (`status:approved`) before this branch existed, which is the gate's own
  invariant applied to the unit that changes the gate.
- **L4 — the verification round, and what it changed.** The read-only verifier returned PASS on
  `S1`-`S5` and one defect that mattered: the marker was not anchored, so a prose sentence containing
  the phrase unlocked the gate while the comment and `CONTRIBUTING.md` promised a line of its own. The
  anchor was added (`D5`) with five cases that keep it anchored — mid-sentence, split across a line
  break, a leading zero, CRLF, and a declaration naming a pull request number — plus one that pins the
  shipped template against ever carrying a numbered declaration (`D6`). Its remaining findings were
  recorded rather than fixed: comments are not stripped (`D6`, pre-existing for the keywords too) and
  chain membership is an author's assertion the gate cannot verify (`D7`).
- **L5 — the native review, and what it changed.** The `risk` lens returned one CRITICAL finding with
  `unknown` causality (`R1-CHAIN-DECL-BYPASS`), which escalates instead of producing a correction plan.
  Measured against the code, the finding was right: the anchored marker still accepted the colon-less
  spelling and a four-space indent, both wider than the form the documents promise. `D8` narrowed the
  marker to the documented form and three cases pin the rejections. The lifecycle ended
  `escalated`/informational, so this fix is the maintainer action the closure names, and reviewing the
  corrected candidate again is a separate decision rather than something this unit assumes.
